"""Validate six SDKs, a shared example library and five APKs, then package a release.

No credentials or devices are accessed. Repository evidence includes only files
tracked under docs/benchmarks/runs at HEAD; ignored historical APKs are not
silently claimed to be present. Use --validate-only before committing changes,
then run packaging from a clean, committed worktree.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GROUP = "com.modernipc"
SDK_MODULES = {
    "ipc-api": "aar",
    "ipc-contract": "aar",
    "ipc-runtime-client": "aar",
    "ipc-runtime-server": "aar",
    "ipc-annotations": "jar",
    "ipc-compiler": "jar",
}
EXAMPLE_MODULES = {"demo-client-common": "aar"}
PUBLICATIONS = {**SDK_MODULES, **EXAMPLE_MODULES}
APK_MODULES = {
    "demo-app": "com.cn.ipc.demo",
    "app-server": "com.cn.ipc.server.app",
    "app-client1": "com.cn.ipc.client1",
    "app-client2": "com.cn.ipc.client2",
    "app-client3": "com.cn.ipc.client3",
}
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ReleaseError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def digest(path: Path, algorithm: str = "sha256") -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def command(argv: list[str], *, binary: bool = False) -> str | bytes:
    result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=not binary,
                            encoding=None if binary else "utf-8", errors=None if binary else "replace")
    if result.returncode:
        stderr = result.stderr.decode("utf-8", errors="replace") if binary else result.stderr
        raise ReleaseError(f"Command failed ({result.returncode}): {argv[0]}\n{stderr.strip()}")
    return result.stdout


def properties() -> dict[str, str]:
    return dict(re.findall(r"(?m)^\s*([^#\s=]+)\s*=\s*(.*?)\s*$",
                           (ROOT / "gradle.properties").read_text(encoding="utf-8-sig")))


def validate_version(version: str) -> int:
    require(bool(re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?", version)),
            "Version must be a semantic version, for example 3.0.0-rc.1")
    props = properties()
    require(props.get("VERSION_NAME") == version, "Version differs from gradle.properties VERSION_NAME")
    code = props.get("VERSION_CODE", "")
    require(code.isdigit() and int(code) > 0, "VERSION_CODE must be a positive integer")
    return int(code)


def validate_archive(path: Path) -> None:
    require(path.is_file() and path.stat().st_size > 0, f"Missing or empty artifact: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            require(archive.testzip() is None, f"Corrupt ZIP member in {path.name}")
            require(bool(archive.namelist()), f"Empty ZIP artifact: {path.name}")
    except zipfile.BadZipFile as error:
        raise ReleaseError(f"Invalid ZIP artifact: {path.name}") from error


def validate_publications(version: str) -> list[dict]:
    publications = []
    for module, extension in PUBLICATIONS.items():
        directory = ROOT / "local-maven" / "com" / "modernipc" / module / version
        stem = f"{module}-{version}"
        required_names = {
            f"{stem}.{extension}", f"{stem}-sources.jar", f"{stem}-javadoc.jar",
            f"{stem}.pom", f"{stem}.module",
        }
        require(directory.is_dir(), f"Missing current publication directory: {directory}")
        files = sorted(path for path in directory.iterdir() if path.is_file())
        require(required_names.issubset({path.name for path in files}), f"Incomplete publication: {module}")
        for path in files:
            require(not path.is_symlink(), f"Symlink is not allowed in publication: {path}")
            require(path.stat().st_size > 0, f"Empty publication file: {path.name}")
            require(path.name.startswith(stem + ".") or path.name.startswith(stem + "-"),
                    f"Unexpected publication filename: {path.name}")
            if path.suffix in {".jar", ".aar"}:
                validate_archive(path)
            if path.suffix in {".md5", ".sha1", ".sha256", ".sha512"}:
                original = path.with_suffix("")
                require(original.is_file(), f"Checksum has no corresponding artifact: {path.name}")
                actual = path.read_text(encoding="ascii").strip().lower().split()[0]
                require(actual == digest(original, path.suffix[1:]), f"Checksum differs: {path.name}")

        pom = ET.parse(directory / f"{stem}.pom").getroot()
        expected = {"groupId": GROUP, "artifactId": module, "version": version}
        for key, value in expected.items():
            require(pom.findtext("m:" + key, namespaces=POM_NS) == value,
                    f"POM {key} differs: {module}")
        for dependency in pom.findall("m:dependencies/m:dependency", POM_NS):
            if dependency.findtext("m:groupId", namespaces=POM_NS) == GROUP:
                name = dependency.findtext("m:artifactId", namespaces=POM_NS)
                dep_version = dependency.findtext("m:version", namespaces=POM_NS)
                require(name in PUBLICATIONS and dep_version == version,
                        f"POM internal dependency differs: {module} -> {name}:{dep_version}")

        metadata = json.loads((directory / f"{stem}.module").read_text(encoding="utf-8"))
        component = metadata.get("component", {})
        require(component.get("group") == GROUP and component.get("module") == module
                and component.get("version") == version, f"Gradle module coordinates differ: {module}")
        for variant in metadata.get("variants", []):
            for dependency in variant.get("dependencies", []):
                if dependency.get("group") == GROUP:
                    name = dependency.get("module")
                    dep_version = dependency.get("version", {})
                    require(name in PUBLICATIONS and isinstance(dep_version, dict)
                            and dep_version.get("requires") == version
                            and dep_version.get("strictly", version) == version
                            and dep_version.get("prefers", version) == version,
                            f"Gradle internal dependency differs: {module} -> {name}:{dep_version}")
            for artifact in variant.get("files", []):
                name = artifact.get("name", "")
                require(Path(name).name == name and artifact.get("url") == name,
                        f"Unexpected Gradle artifact path: {module}/{name}")
                path = directory / name
                require(path.is_file(), f"Gradle metadata references missing file: {module}/{name}")
                require(artifact.get("size") == path.stat().st_size, f"Gradle artifact size differs: {name}")
                for algorithm in ("sha256", "sha512", "sha1", "md5"):
                    if algorithm in artifact:
                        require(artifact[algorithm].lower() == digest(path, algorithm),
                                f"Gradle artifact {algorithm} differs: {name}")
        publications.append({
            "module": module, "kind": "sdk" if module in SDK_MODULES else "shared-example",
            "coordinate": f"{GROUP}:{module}:{version}", "directory": directory.relative_to(ROOT).as_posix(),
            "files": [{"name": path.name, "size": path.stat().st_size, "sha256": digest(path)}
                      for path in files],
        })
    return publications


def resolve_android_tools(args: argparse.Namespace) -> tuple[Path, Path]:
    sdk = args.android_sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    tool_dir = None
    if sdk:
        base = Path(sdk) / "build-tools"
        if base.is_dir():
            choices = [path for path in base.iterdir() if path.is_dir()]
            choices.sort(key=lambda path: tuple(int(value) for value in re.findall(r"\d+", path.name)), reverse=True)
            for path in choices:
                aapt = path / ("aapt.exe" if os.name == "nt" else "aapt")
                signer = path / ("apksigner.bat" if os.name == "nt" else "apksigner")
                if aapt.is_file() and signer.is_file():
                    tool_dir = path
                    break
    aapt = Path(args.aapt) if args.aapt else (tool_dir / ("aapt.exe" if os.name == "nt" else "aapt") if tool_dir else None)
    signer = Path(args.apksigner) if args.apksigner else (tool_dir / ("apksigner.bat" if os.name == "nt" else "apksigner") if tool_dir else None)
    require(aapt is not None and aapt.is_file(), "Provide --android-sdk or an existing --aapt path")
    require(signer is not None and signer.is_file(), "Provide --android-sdk or an existing --apksigner path")
    return aapt.resolve(), signer.resolve()


def signer_command(signer: Path) -> list[str]:
    if signer.suffix.lower() in {".bat", ".cmd"}:
        jar = signer.parent / "lib" / "apksigner.jar"
        require(jar.is_file(), f"Missing apksigner JAR: {jar}")
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin" / "java.exe") if java_home else shutil.which("java")
        require(bool(java), "Set JAVA_HOME or put Java on PATH to run apksigner")
        return [java, "-jar", str(jar)]
    return [str(signer)]


def validate_apks(version: str, code: int, aapt: Path, signer: Path) -> list[dict]:
    apks = []
    certificates = None
    for module, package in APK_MODULES.items():
        path = ROOT / module / "build" / "outputs" / "apk" / "debug" / f"{module}-debug.apk"
        validate_archive(path)
        badging = command([str(aapt), "dump", "badging", str(path)])
        match = re.search(r"(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging)
        require(match is not None, f"Cannot read APK identity: {module}")
        require(match.groups() == (package, str(code), version), f"APK identity/version differs: {module}")
        require("application-debuggable" in badging, f"Expected an explicitly debug APK: {module}")
        result = command(signer_command(signer) + ["verify", "--print-certs", str(path)])
        fingerprints = sorted(set(re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)\s*$", result)))
        require(bool(fingerprints) and all(len(item) == 64 for item in fingerprints), f"Cannot verify signer: {module}")
        fingerprints = [item.lower() for item in fingerprints]
        if certificates is None:
            certificates = fingerprints
        require(fingerprints == certificates, f"Five APKs do not share the same signing certificate: {module}")
        apks.append({"module": module, "package": package, "versionName": version,
                     "versionCode": code, "debuggable": True,
                     "sourcePath": path.relative_to(ROOT).as_posix(),
                     "releaseName": f"{module}-{version}-debug.apk", "size": path.stat().st_size,
                     "sha256": digest(path), "signerCertificateSha256": fingerprints})
    return apks


def validate_commit(version: str) -> str:
    dirty = command(["git", "status", "--porcelain", "--untracked-files=all"])
    require(not dirty.strip(), "Packaging requires a clean committed worktree; use --validate-only before committing")
    commit = command(["git", "rev-parse", "HEAD"]).strip()
    # Local preparation may precede tag creation. CI separately requires the tag.
    tags = command(["git", "tag", "--points-at", "HEAD"]).splitlines()
    matching = f"v{version}"
    if matching in command(["git", "tag", "--list", matching]).splitlines():
        require(matching in tags, f"Existing tag {matching} does not point to HEAD")
    return commit


def repository_evidence() -> list[Path]:
    raw = command(["git", "ls-files", "-z", "--", "docs/benchmarks/runs"], binary=True)
    paths = [ROOT / item.decode("utf-8") for item in raw.split(b"\0") if item]
    require(bool(paths), "No tracked benchmark evidence found")
    for path in paths:
        require(path.is_file() and not path.is_symlink(), f"Missing or unsafe evidence file: {path}")
        require(path.resolve().is_relative_to((ROOT / "docs" / "benchmarks" / "runs").resolve()),
                f"Evidence path escaped runs directory: {path}")
    return sorted(paths)


def package_release(args: argparse.Namespace, version: str, code: int, publications: list[dict], apks: list[dict]) -> Path:
    commit = validate_commit(version)
    evidence = repository_evidence()
    output = Path(args.output_dir).resolve() if args.output_dir else ROOT / ".gradle" / "release-assets" / version
    require(not output.exists() or (output.is_dir() and not any(output.iterdir())),
            f"Output directory must be absent or empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    repository = ROOT / "local-maven"
    with zipfile.ZipFile(output / "maven-repository.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for publication in publications:
            directory = ROOT / publication["directory"]
            for record in publication["files"]:
                path = directory / record["name"]
                archive.write(path, path.relative_to(repository).as_posix())
                if path.suffix in {".aar", ".jar", ".pom", ".module"}:
                    require(not (output / path.name).exists(), f"Release asset filename collision: {path.name}")
                    shutil.copyfile(path, output / path.name)
    for apk in apks:
        shutil.copyfile(ROOT / apk["sourcePath"], output / apk["releaseName"])
    source = output / "source.zip"
    command(["git", "archive", "--format=zip", f"--prefix=ModernIpc-{version}/", f"--output={source}", "HEAD"])
    apk_evidence = [path for path in evidence if path.suffix.lower() == ".apk"]
    with zipfile.ZipFile(output / "evidence-repository.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in evidence:
            archive.write(path, path.relative_to(ROOT).as_posix())
        archive.writestr("EVIDENCE_SCOPE.txt",
                         f"Release {version}; source commit {commit}\n"
                         "Contains only Git-tracked files under docs/benchmarks/runs at this commit.\n"
                         f"Evidence files: {len(evidence)}; tracked APK evidence files: {len(apk_evidence)}.\n"
                         "Ignored/untracked historical APKs are not included or implied.\n"
                         "Historical Xiaomi measurements belong to their recorded APK/source hashes.\n"
                         "Latest release APK device validation: NOT_RUN.\n")
    assets = [{"name": path.name, "size": path.stat().st_size, "sha256": digest(path)}
              for path in sorted(output.iterdir()) if path.is_file()]
    manifest = {
        "formatVersion": 1, "version": version, "tag": f"v{version}", "versionCode": code,
        "commit": commit, "createdUtc": datetime.now(timezone.utc).isoformat(),
        "sourceArchive": {"name": "source.zip", "method": "git archive HEAD", "worktreeClean": True},
        "sdkPublications": [item for item in publications if item["kind"] == "sdk"],
        "examplePublications": [item for item in publications if item["kind"] == "shared-example"],
        "apks": apks,
        "deviceValidation": {"serial": "925c23bb", "latestReleaseApks": "NOT_RUN",
                             "historicalEvidenceMustMatchRecordedApkAndSourceHashes": True},
        "evidence": {"archive": "evidence-repository.zip", "scope": "Git-tracked docs/benchmarks/runs at HEAD",
                     "fileCount": len(evidence), "apkFileCount": len(apk_evidence),
                     "includesApkFiles": bool(apk_evidence), "includesIgnoredHistoricalApks": False,
                     "files": [{"path": path.relative_to(ROOT).as_posix(), "size": path.stat().st_size,
                                "sha256": digest(path)} for path in evidence]},
        "assetsExcludingManifestAndChecksumFile": assets,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksum_lines = [f"{digest(path)}  {path.name}" for path in sorted(output.iterdir()) if path.is_file()]
    (output / "SHA256SUMS.txt").write_text("\n".join(checksum_lines) + "\n", encoding="ascii")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output-dir")
    parser.add_argument("--android-sdk")
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    parser.add_argument("--validate-only", action="store_true", help="Validate local artifacts without archiving HEAD or requiring a clean worktree")
    args = parser.parse_args()
    try:
        code = validate_version(args.version)
        publications = validate_publications(args.version)
        aapt, signer = resolve_android_tools(args)
        apks = validate_apks(args.version, code, aapt, signer)
        output = None if args.validate_only else package_release(args, args.version, code, publications, apks)
        print(json.dumps({"status": "VALIDATED" if args.validate_only else "PACKAGED", "version": args.version,
                          "sdkModules": len(SDK_MODULES), "exampleLibraries": len(EXAMPLE_MODULES), "debugApks": len(apks),
                          "apkSignerCertificateSha256": apks[0]["signerCertificateSha256"],
                          "deviceValidation": "NOT_RUN", "outputDirectory": str(output) if output else None}, ensure_ascii=True))
        return 0
    except (ReleaseError, OSError, ValueError, ET.ParseError) as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
