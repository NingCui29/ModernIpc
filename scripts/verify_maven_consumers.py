#!/usr/bin/env python3
"""Compile real, isolated Maven consumers using Gradle metadata and Maven POMs.

Uses a fresh SDK version/repository by default; never overwrites local-maven.
--remote-repository verifies an existing HTTPS publication anonymously instead.
No Android device, GitHub credentials or remote publication is involved.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests" / "maven-consumer"
MODULES = ("ipc-annotations", "ipc-compiler", "ipc-contract", "ipc-runtime-client",
           "ipc-runtime-server", "ipc-api")
PUBLIC = {
    "ipc-api": {("com.modernipc", item) for item in (
        "ipc-contract", "ipc-annotations", "ipc-runtime-client", "ipc-runtime-server")}
        | {("org.jetbrains.kotlinx", "kotlinx-coroutines-core")},
    "ipc-runtime-client": {("com.modernipc", "ipc-contract"),
                           ("org.jetbrains.kotlinx", "kotlinx-coroutines-core")},
    "ipc-runtime-server": {("com.modernipc", "ipc-contract"),
                           ("org.jetbrains.kotlinx", "kotlinx-coroutines-core")},
}
JVM_ARGS = ("-Xmx2048m -XX:MaxMetaspaceSize=768m -XX:TieredStopAtLevel=1 "
            "-Dfile.encoding=UTF-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def anonymous_repository_url(value: str) -> str:
    """Require a public HTTPS repository URL without embedded credentials."""
    parsed = urllib.parse.urlsplit(value)
    require(parsed.scheme == "https" and bool(parsed.hostname),
            "Remote repository must be an HTTPS URL")
    require(parsed.username is None and parsed.password is None
            and not parsed.query and not parsed.fragment,
            "Remote repository must not contain credentials, query parameters or fragments")
    return value.rstrip("/")


class AnonymousHttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        anonymous_repository_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_remote_publications(base_url: str, repository: Path, version: str) -> list[dict]:
    """Fetch metadata and its artifacts with no token, cookie or auth handler."""
    base_url = anonymous_repository_url(base_url)
    opener = urllib.request.build_opener(AnonymousHttpsRedirectHandler())
    downloads = []

    def download(relative: str) -> bytes:
        url = base_url + "/" + urllib.parse.quote(relative, safe="/")
        request = urllib.request.Request(url, headers={"User-Agent": "ModernIpc-Anonymous-Consumer-Check"})
        with opener.open(request, timeout=60) as response:
            require(response.status == 200, f"Anonymous GET failed: {url}")
            data = response.read()
        require(bool(data), f"Empty remote artifact: {url}")
        path = repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        downloads.append({"url": url, "size": len(data),
                          "sha256": hashlib.sha256(data).hexdigest(), "authorization": False})
        return data

    for module in MODULES:
        relative = f"com/modernipc/{module}/{version}/"
        stem = f"{module}-{version}"
        download(relative + stem + ".pom")
        metadata = json.loads(download(relative + stem + ".module"))
        component = metadata.get("component", {})
        require(component.get("group") == "com.modernipc"
                and component.get("module") == module and component.get("version") == version,
                f"Remote module coordinates differ: {module}")
        names = set()
        for variant in metadata.get("variants", []):
            for artifact in variant.get("files", []):
                name = artifact.get("url", "")
                require(isinstance(name, str) and bool(re.fullmatch(r"[A-Za-z0-9._-]+", name))
                        and (name.startswith(stem + ".") or name.startswith(stem + "-"))
                        and artifact.get("name") == name,
                        f"Unsafe remote artifact path in {module}")
                names.add(name)
        require(bool(names), f"No remote artifacts in {module}")
        for name in sorted(names):
            download(relative + name)
        # Check all artifact hashes before invoking Gradle against the remote URL.
        verify_publication(repository, module, version)
    return downloads


def read_sdk_property(path: Path) -> str | None:
    """Read sdk.dir using Java properties escapes, including Windows drive paths."""
    if not path.is_file():
        return None

    def unescape(value: str) -> str:
        def replace(match: re.Match) -> str:
            token = match.group(1)
            if token.startswith("u"):
                return chr(int(token[1:], 16))
            return {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(token, token)
        return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", replace, value)

    logical = ""
    result = None
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        logical += line.lstrip()
        trailing = len(logical) - len(logical.rstrip("\\"))
        if trailing % 2:
            logical = logical[:-1]
            continue
        if not logical.startswith(("#", "!")):
            match = re.match(r"sdk\.dir(?:\s*[:=]\s*|\s+)(.*)$", logical)
            if match:
                result = unescape(match.group(1))
        logical = ""
    return result


def configure_android_sdk(env: dict[str, str], explicit: Path | None, root: Path = ROOT) -> str:
    """Precedence: explicit option, ANDROID_HOME, ANDROID_SDK_ROOT, root local.properties."""
    choices = [
        ("--android-sdk", str(explicit) if explicit is not None else None),
        ("ANDROID_HOME", env.get("ANDROID_HOME")),
        ("ANDROID_SDK_ROOT", env.get("ANDROID_SDK_ROOT")),
        ("local.properties:sdk.dir", read_sdk_property(root / "local.properties")),
    ]
    source, value = next(((key, value) for key, value in choices if value), ("", None))
    require(value is not None, "Android SDK not found: use --android-sdk, ANDROID_HOME, "
            "ANDROID_SDK_ROOT or root local.properties sdk.dir")
    sdk = Path(value).expanduser()
    if not sdk.is_absolute():
        sdk = (root if source == "local.properties:sdk.dir" else Path.cwd()) / sdk
    sdk = sdk.resolve()
    require(sdk.is_dir(), f"Android SDK directory from {source} does not exist: {sdk}")
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    return source


def verify_publication(repository: Path, module: str, version: str) -> dict:
    base = repository / "com" / "modernipc" / module / version / f"{module}-{version}"
    # Version strings may contain dots: Path.with_suffix would truncate such strings.
    pom_path, module_path = Path(str(base) + ".pom"), Path(str(base) + ".module")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(pom_path).getroot()
    require(pom.findtext("m:groupId", namespaces=ns) == "com.modernipc", str(pom_path))
    require(pom.findtext("m:artifactId", namespaces=ns) == module, str(pom_path))
    require(pom.findtext("m:version", namespaces=ns) == version, str(pom_path))
    dependencies = []
    for entry in pom.findall("m:dependencies/m:dependency", ns):
        dependency = {key: entry.findtext(f"m:{key}", namespaces=ns)
                      for key in ("groupId", "artifactId", "version", "scope")}
        if dependency["groupId"] == "com.modernipc":
            require(dependency["version"] == version, f"Wrong internal version in {pom_path}")
        dependencies.append(dependency)
    compile_dependencies = {(item["groupId"], item["artifactId"]) for item in dependencies
                            if item["scope"] in (None, "compile")}
    require(PUBLIC.get(module, set()) <= compile_dependencies,
            f"Public dependencies missing from {module} POM compile scope: "
            f"{PUBLIC.get(module, set()) - compile_dependencies}")

    metadata = json.loads(module_path.read_text(encoding="utf-8"))
    component = metadata["component"]
    require(component.get("group") == "com.modernipc" and component.get("module") == module
            and component.get("version") == version, str(module_path))
    api_variants = [item for item in metadata["variants"]
                    if item.get("attributes", {}).get("org.gradle.usage") == "java-api"]
    require(bool(api_variants), f"No API variant in {module_path}")
    for variant in api_variants:
        exported = {(item["group"], item["module"]) for item in variant.get("dependencies", [])}
        require(PUBLIC.get(module, set()) <= exported,
                f"Public dependencies missing from {module} API metadata: "
                f"{PUBLIC.get(module, set()) - exported}")
    for variant in metadata["variants"]:
        for item in variant.get("dependencies", []):
            if item["group"] == "com.modernipc":
                require(item["version"].get("requires") == version,
                        f"Wrong internal version in {module_path}")
        for artifact in variant.get("files", []):
            require(artifact.get("url") == artifact.get("name")
                    and bool(re.fullmatch(r"[A-Za-z0-9._-]+", artifact.get("url", "")))
                    and artifact["url"] not in (".", ".."),
                    f"Unsafe artifact path in {module_path}")
            path = module_path.parent / artifact["url"]
            require(path.is_file(), f"Missing artifact: {path}")
            require(hashlib.sha256(path.read_bytes()).hexdigest() == artifact["sha256"],
                    f"Artifact hash mismatch: {path}")
    return {"module": module, "pomCompileExports": sorted(":".join(item)
            for item in compile_dependencies), "apiVariants": [item["name"] for item in api_variants]}


def verify_graph(path: Path, direct: str, version: str) -> dict:
    lines = path.read_text(encoding="utf-8").splitlines()
    direct_entries = [item.removeprefix("direct=") for item in lines if item.startswith("direct=")]
    require(direct_entries == [direct], f"Unexpected direct dependencies in {path}: {direct_entries}")
    resolved = {item.removeprefix("resolved=") for item in lines if item.startswith("resolved=")}
    expected = {f"com.modernipc:{module}:{version}" for module in MODULES if module != "ipc-compiler"}
    require(expected <= resolved, f"Missing published compile types in {path}: {expected - resolved}")
    require(any(item.startswith("org.jetbrains.kotlinx:kotlinx-coroutines-core") for item in resolved),
            f"No coroutine API on compile classpath: {path}")
    return {"directDependencies": direct_entries, "resolvedCompileModules": sorted(resolved)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="Fresh local validation version, or explicit existing remote version")
    parser.add_argument("--repository", type=Path, help="Isolated file Maven repository")
    parser.add_argument("--remote-repository", help="Anonymous HTTPS Maven URL; skips SDK publishing")
    parser.add_argument("--output", type=Path, help="Build logs and machine-readable report directory")
    parser.add_argument("--java-home", type=Path, help="JDK 17 home (otherwise JAVA_HOME)")
    parser.add_argument("--android-sdk", type=Path, help="SDK containing Android platform 34")
    parser.add_argument("--skip-publish", action="store_true", help="Use artifacts already published by this build")
    parser.add_argument("--offline", action="store_true", help="Use cached external Gradle dependencies")
    args = parser.parse_args()

    remote = anonymous_repository_url(args.remote_repository) if args.remote_repository else None
    if remote:
        require(bool(args.version), "--remote-repository requires an explicit --version")
        require(args.repository is None, "--repository cannot be combined with --remote-repository")
        require(not args.offline, "Remote verification cannot use --offline")

    root_version = re.search(r"^VERSION_NAME=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1)
    version = args.version or (root_version.split("-")[0] + "-consumer-check-"
                               + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S%f"))
    require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version)), "Invalid validation version")
    if not remote:
        require(version != root_version, "Use a fresh validation version, not VERSION_NAME")
    run_name = version if not remote else version + "-remote-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S%f")
    output = (args.output or ROOT / ".gradle" / "maven-consumer" / run_name).resolve()
    repository = (args.repository or output / ("remote-publications" if remote else "repository")).resolve()
    fixture_repository = output / "fixture-repository" if remote else repository
    consumer_repository = remote or str(repository)
    old_repository = (ROOT / "local-maven").resolve()
    require(not repository.is_relative_to(old_repository), "Never use the existing local-maven repository")
    if not args.skip_publish and not remote:
        require(not (repository / "com" / "modernipc" / "ipc-api" / version).exists(),
                "Validation version already exists; use a fresh version")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    if remote:
        for name in ("GITHUB_TOKEN", "GH_TOKEN", "GITHUB_ACTOR", "ORG_GRADLE_PROJECT_gpr.key", "ORG_GRADLE_PROJECT_gpr.user"):
            env.pop(name, None)
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
    sdk_source = configure_android_sdk(env, args.android_sdk)
    require(bool(env.get("JAVA_HOME")), "JAVA_HOME or --java-home must select JDK 17")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    # The tracked Unix wrapper is mode 100644; invoke Bash without changing the checkout.
    wrapper_command = [str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]
    common = wrapper_command + ["--no-daemon", "--console=plain", "--max-workers=1",
              f"-Dorg.gradle.jvmargs={JVM_ARGS}", "-Pkotlin.compiler.execution.strategy=in-process"]
    if args.offline:
        common.append("--offline")
    if remote:
        common.extend(["--refresh-dependencies", "--rerun-tasks", "--no-build-cache"])
    report = {"version": version, "repository": consumer_repository, "deviceValidation": "NOT_RUN",
              "status": "RUNNING", "steps": [], "metadata": [], "consumers": {},
              "androidSdkSource": sdk_source, "mode": "remote-anonymous" if remote else "local",
              "fixtureRepository": str(fixture_repository)}
    baseline_pom = (old_repository / "com" / "modernipc" / "ipc-api" / root_version
                    / f"ipc-api-{root_version}.pom")
    report["existingReleaseNegativeControl"] = {"version": root_version, "readOnly": True,
                                               "status": "NOT_AVAILABLE"}
    if baseline_pom.is_file():
        try:
            verify_publication(old_repository, "ipc-api", root_version)
            report["existingReleaseNegativeControl"]["status"] = "ACCEPTED"
        except (RuntimeError, OSError, ET.ParseError, KeyError) as error:
            report["existingReleaseNegativeControl"].update(status="REJECTED", reason=str(error))

    def run(name: str, project: Path, arguments: list[str], expected_error: str | None = None) -> None:
        log = output / f"{name}.txt"
        command = common + ["-p", str(project)] + arguments
        print(f"{name}: started (log: {log})", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                    shell=False, check=False)
        report["steps"].append({"name": name, "exitCode": result.returncode, "log": str(log)})
        if expected_error is None:
            require(result.returncode == 0, f"{name} failed with exit {result.returncode}; see {log}")
        else:
            diagnostic = log.read_text(encoding="utf-8")
            require(result.returncode != 0 and expected_error in diagnostic,
                    f"{name} must reject the fixture with the expected diagnostic; see {log}")
            require("FileAlreadyExistsException" not in diagnostic,
                    f"{name} crashed instead of validating the facade; see {log}")
            report["steps"][-1]["expectedFailure"] = True
        print(f"{name}: passed", flush=True)

    try:
        if remote:
            report["anonymousDownloads"] = download_remote_publications(remote, repository, version)
        elif not args.skip_publish:
            publish = [f":{module}:publish{'Java' if module in ('ipc-annotations', 'ipc-compiler') else 'Release'}PublicationToProjectLocalRepository"
                       for module in MODULES]
            run("publish-sdk", ROOT, [f"-PVERSION_NAME={version}",
                                     f"-PprojectLocalRepository={repository}"] + publish)
        report["metadata"] = [verify_publication(repository, module, version) for module in MODULES]
        for mode in ("metadata", "pom"):
            build_root = output / mode / "build"
            properties = [f"-PipcVersion={version}", f"-PipcRepository={consumer_repository}",
                          f"-PfixtureRepository={fixture_repository}",
                          f"-PmetadataMode={mode}", f"-PconsumerBuildRoot={build_root}",
                          "--project-cache-dir", str(output / mode / "project-cache")]
            run(f"{mode}-api-and-contract", FIXTURE, properties + [
                ":api-consumer:compileReleaseKotlin", ":api-consumer:verifyPublishedCompileGraph",
                ":custom-contract:publishReleasePublicationToFixtureRepository"])
            generated = build_root / "custom-contract" / "generated" / "ksp" / "release" / "kotlin"
            for name in ("ICustomServiceClientAdapter.kt", "ICustomServiceServerStub.kt", "ICustomServiceIpcSchema.kt"):
                require(any(generated.rglob(name)), f"KSP did not generate {name} ({mode})")
            run(f"{mode}-contract-consumer", FIXTURE, properties + [
                ":contract-consumer:compileReleaseKotlin", ":contract-consumer:verifyPublishedCompileGraph"])
            report["consumers"][mode] = {
                "apiOnly": verify_graph(build_root / "api-consumer" / "consumer-compile-graph.txt",
                    f"com.modernipc:ipc-api:{version}", version),
                "contractOnly": verify_graph(build_root / "contract-consumer" / "consumer-compile-graph.txt",
                    f"com.modernipc.consumer.fixture:custom-contract:{version}", version),
                "asyncAndFlowKsp": "PASSED",
            }
        run("reject-nested-facades", FIXTURE, [
            f"-PipcVersion={version}", f"-PipcRepository={consumer_repository}",
            f"-PfixtureRepository={fixture_repository}", "-PmetadataMode=metadata",
            f"-PconsumerBuildRoot={output / 'negative' / 'build'}", "-PinvalidFacade=nested",
            "--project-cache-dir", str(output / "negative" / "project-cache"),
            ":custom-contract:compileReleaseKotlin"],
            expected_error="@IpcFacade requires a top-level interface")
        report["status"] = "PASSED"
    except (RuntimeError, OSError, ET.ParseError, KeyError, ValueError, TypeError) as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
    finally:
        (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "version": version,
                      "report": str(output / "verification.json"), "error": report.get("error")}, ensure_ascii=False))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
