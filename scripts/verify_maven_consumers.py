#!/usr/bin/env python3
"""Compile real, isolated Maven consumers using Gradle metadata and Maven POMs.

Uses a fresh SDK version/repository by default; never overwrites local-maven.
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
    require(metadata["component"]["version"] == version, str(module_path))
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
    parser.add_argument("--version", help="Fresh, previously unpublished validation version")
    parser.add_argument("--repository", type=Path, help="Isolated file Maven repository")
    parser.add_argument("--output", type=Path, help="Build logs and machine-readable report directory")
    parser.add_argument("--java-home", type=Path, help="JDK 17 home (otherwise JAVA_HOME)")
    parser.add_argument("--android-sdk", type=Path, help="SDK containing Android platform 34")
    parser.add_argument("--skip-publish", action="store_true", help="Use artifacts already published by this build")
    parser.add_argument("--offline", action="store_true", help="Use cached external Gradle dependencies")
    args = parser.parse_args()

    root_version = re.search(r"^VERSION_NAME=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1)
    version = args.version or (root_version.split("-")[0] + "-consumer-check-"
                               + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S%f"))
    require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version)), "Invalid validation version")
    require(version != root_version, "Use a fresh validation version, not VERSION_NAME")
    output = (args.output or ROOT / ".gradle" / "maven-consumer" / version).resolve()
    repository = (args.repository or output / "repository").resolve()
    old_repository = (ROOT / "local-maven").resolve()
    require(not repository.is_relative_to(old_repository), "Never use the existing local-maven repository")
    if not args.skip_publish:
        require(not (repository / "com" / "modernipc" / "ipc-api" / version).exists(),
                "Validation version already exists; use a fresh version")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
    if args.android_sdk:
        env["ANDROID_HOME"] = str(args.android_sdk.resolve())
        env["ANDROID_SDK_ROOT"] = str(args.android_sdk.resolve())
    require(bool(env.get("JAVA_HOME")), "JAVA_HOME or --java-home must select JDK 17")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    # The tracked Unix wrapper is mode 100644; invoke Bash without changing the checkout.
    wrapper_command = [str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]
    common = wrapper_command + ["--no-daemon", "--console=plain", "--max-workers=1",
              f"-Dorg.gradle.jvmargs={JVM_ARGS}", "-Pkotlin.compiler.execution.strategy=in-process"]
    if args.offline:
        common.append("--offline")
    report = {"version": version, "repository": str(repository), "deviceValidation": "NOT_RUN",
              "status": "RUNNING", "steps": [], "metadata": [], "consumers": {}}
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

    def run(name: str, project: Path, arguments: list[str]) -> None:
        log = output / f"{name}.txt"
        command = common + ["-p", str(project)] + arguments
        print(f"{name}: started (log: {log})", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                    shell=False, check=False)
        report["steps"].append({"name": name, "exitCode": result.returncode, "log": str(log)})
        require(result.returncode == 0, f"{name} failed with exit {result.returncode}; see {log}")
        print(f"{name}: passed", flush=True)

    try:
        if not args.skip_publish:
            publish = [f":{module}:publish{'Java' if module in ('ipc-annotations', 'ipc-compiler') else 'Release'}PublicationToProjectLocalRepository"
                       for module in MODULES]
            run("publish-sdk", ROOT, [f"-PVERSION_NAME={version}",
                                     f"-PprojectLocalRepository={repository}"] + publish)
        report["metadata"] = [verify_publication(repository, module, version) for module in MODULES]
        for mode in ("metadata", "pom"):
            build_root = output / mode / "build"
            properties = [f"-PipcVersion={version}", f"-PipcRepository={repository}",
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
        report["status"] = "PASSED"
    except (RuntimeError, OSError, ET.ParseError, KeyError) as error:
        report["status"] = "FAILED"
        report["error"] = str(error)
    finally:
        (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "version": version,
                      "report": str(output / "verification.json"), "error": report.get("error")}, ensure_ascii=False))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
