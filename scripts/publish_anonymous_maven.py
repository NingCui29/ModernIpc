#!/usr/bin/env python3
"""Mirror immutable Release Maven bytes into a public Git branch, then verify anonymous reads.

This script never builds, authenticates, commits or pushes. The caller supplies an
existing Release ZIP and its manifest, and manages the destination branch.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("anonymous_release_validation", ROOT / ".github/scripts/prepare_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
PUBLICATIONS = release.PUBLICATIONS
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: dict) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def version_key(value: str) -> tuple:
    match = VERSION.fullmatch(value)
    require(match is not None and not value.endswith("-SNAPSHOT"), f"Invalid release version: {value}")
    major, minor, patch, suffix = match.groups()
    identifiers = tuple((0, int(part)) if part.isdigit() else (1, part) for part in (suffix or "").split("."))
    return int(major), int(minor), int(patch), suffix is None, identifiers


def safe_destination(root: Path, relative: str) -> Path:
    path = PurePosixPath(relative)
    require(not path.is_absolute() and path.parts and all(part not in (".", "..") for part in path.parts)
            and "\\" not in relative and ":" not in relative and path.as_posix() == relative,
            f"Unsafe repository path: {relative}")
    result = root.joinpath(*path.parts)
    current = result
    while current != root:
        require(not current.is_symlink(), f"Symlink is not allowed: {current}")
        current = current.parent
    require(result.resolve().is_relative_to(root.resolve()), f"Repository path escaped destination: {relative}")
    return result


def expected_files(manifest: dict, version: str) -> dict[str, dict]:
    version_key(version)
    require(manifest.get("version") == version and manifest.get("tag") == f"v{version}", "Manifest version/tag differs")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", manifest.get("commit", ""))), "Manifest source commit is invalid")
    publications = manifest.get("sdkPublications", []) + manifest.get("examplePublications", [])
    require(len(publications) == len(PUBLICATIONS) and {item.get("module") for item in publications} == set(PUBLICATIONS),
            "Release must contain exactly all seven publications")
    expected = {}
    for publication in publications:
        module = publication["module"]
        directory = f"com/modernipc/{module}/{version}"
        require(publication.get("directory") == f"local-maven/{directory}"
                and publication.get("coordinate") == f"com.modernipc:{module}:{version}",
                f"Manifest coordinates differ: {module}")
        stem = f"{module}-{version}"
        primary = {f"{stem}.{PUBLICATIONS[module]}", f"{stem}.pom", f"{stem}.module",
                   f"{stem}-sources.jar", f"{stem}-javadoc.jar"}
        allowed = primary | {f"{name}.{algorithm}" for name in primary for algorithm in CHECKSUMS}
        records = publication.get("files", [])
        names = {record.get("name") for record in records}
        require(primary <= names <= allowed and len(names) == len(records), f"Missing, duplicate or unexpected publication files: {module}")
        for record in records:
            require(type(record.get("size")) is int and record["size"] > 0
                    and bool(re.fullmatch(r"[0-9a-f]{64}", record.get("sha256", ""))), f"Invalid file record: {module}")
            expected[f"{directory}/{record['name']}"] = {key: record[key] for key in ("size", "sha256")}
    return expected


def prepare(archive_path: Path, manifest_path: Path, repository: Path, version: str, source_repository: str) -> dict:
    require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", source_repository)), "Invalid source repository")
    require(not repository.is_symlink(), "Repository root cannot be a symlink")
    repository = repository.resolve()
    manifest_data = manifest_path.read_bytes()
    manifest = json.loads(manifest_data)
    expected = expected_files(manifest, version)
    archive_records = [item for item in manifest.get("assetsExcludingManifestAndChecksumFile", [])
                       if item.get("name") == "maven-repository.zip"]
    archive_data = archive_path.read_bytes()
    require(len(archive_records) == 1 and archive_records[0].get("size") == len(archive_data)
            and archive_records[0].get("sha256") == sha256(archive_data), "Release Maven ZIP digest or size differs from manifest")
    created = datetime.fromisoformat(manifest["createdUtc"])
    require(created.tzinfo is not None, "Manifest date must include timezone")
    updated = created.astimezone(timezone.utc).strftime("%Y%m%d%H%M%S")
    receipt = {"formatVersion": 1, "version": version, "tag": f"v{version}", "sourceRepository": source_repository,
               "sourceCommit": manifest["commit"], "releaseCreatedUtc": manifest["createdUtc"],
               "archiveSha256": sha256(archive_data), "manifestSha256": sha256(manifest_data),
               "files": [{"path": path, **expected[path]} for path in sorted(expected)]}
    writes = {}
    with tempfile.TemporaryDirectory(prefix="modernipc-anonymous-") as temporary:
        staging = Path(temporary)
        with zipfile.ZipFile(archive_path) as archive:
            entries = archive.infolist()
            require(len(entries) == len(expected) and {item.filename for item in entries} == set(expected),
                    "ZIP members differ from the seven manifest publications (missing, duplicate or unexpected paths)")
            for item in entries:
                require(not item.is_dir() and not stat.S_ISLNK(item.external_attr >> 16), f"Unsafe ZIP member: {item.filename}")
                record = expected[item.filename]
                require(item.file_size == record["size"], f"ZIP member size differs: {item.filename}")
                data = archive.read(item)
                require(sha256(data) == record["sha256"], f"ZIP member digest differs: {item.filename}")
                destination = safe_destination(staging / "local-maven", item.filename)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                writes[item.filename] = data
        previous_root = release.ROOT
        try:
            release.ROOT = staging
            release.validate_publications(version)
        finally:
            release.ROOT = previous_root

    # Check the whole version before modifying any destination file. An existing
    # release is immutable even if only one classifier/checksum would change.
    for module in PUBLICATIONS:
        directory = safe_destination(repository, f"com/modernipc/{module}/{version}")
        if directory.exists():
            actual = {path.relative_to(repository).as_posix() for path in directory.rglob("*") if path.is_file()}
            wanted = {path for path in expected if path.startswith(f"com/modernipc/{module}/{version}/")}
            require(actual == wanted, f"Existing publication file set differs: {module}:{version}")
    receipt_path = f"receipts/{version}.json"
    immutable = {**writes, receipt_path: json_bytes(receipt)}
    for relative, data in immutable.items():
        target = safe_destination(repository, relative)
        require(not target.exists() or (target.is_file() and target.read_bytes() == data),
                f"Refusing to overwrite existing immutable release: {relative}")
    writes[receipt_path] = immutable[receipt_path]

    for module in PUBLICATIONS:
        artifact_dir = safe_destination(repository, f"com/modernipc/{module}")
        versions = {version}
        if artifact_dir.exists():
            for directory in artifact_dir.iterdir():
                require(not directory.is_symlink(), f"Symlink is not allowed: {directory}")
                if directory.is_dir():
                    version_key(directory.name)
                    require((directory / f"{module}-{directory.name}.pom").is_file(), f"Incomplete existing version: {directory}")
                    versions.add(directory.name)
        ordered = sorted(versions, key=version_key)
        metadata_path = f"com/modernipc/{module}/maven-metadata.xml"
        existing = safe_destination(repository, metadata_path)
        timestamp = updated
        if existing.exists():
            previous = ET.fromstring(existing.read_bytes())
            previous_stamp = previous.findtext("versioning/lastUpdated", "")
            require(bool(re.fullmatch(r"\d{14}", previous_stamp)), f"Invalid existing Maven metadata: {existing}")
            timestamp = max(timestamp, previous_stamp)
        metadata = ET.Element("metadata")
        ET.SubElement(metadata, "groupId").text = "com.modernipc"
        ET.SubElement(metadata, "artifactId").text = module
        versioning = ET.SubElement(metadata, "versioning")
        ET.SubElement(versioning, "latest").text = ordered[-1]
        ET.SubElement(versioning, "release").text = ordered[-1]
        listing = ET.SubElement(versioning, "versions")
        for value in ordered:
            ET.SubElement(listing, "version").text = value
        ET.SubElement(versioning, "lastUpdated").text = timestamp
        ET.indent(metadata, space="  ")
        data = ET.tostring(metadata, encoding="utf-8", xml_declaration=True) + b"\n"
        writes[metadata_path] = data
        for algorithm in CHECKSUMS:
            writes[f"{metadata_path}.{algorithm}"] = (hashlib.new(algorithm, data).hexdigest() + "\n").encode("ascii")

    readme = ("# ModernIPC public Maven repository\n\n"
              "This branch contains immutable Maven artifacts copied from verified GitHub Releases.\n"
              "Consumers do not need a GitHub account or token.\n\n"
              "```kotlin\nmaven(\"https://raw.githubusercontent.com/" + source_repository + "/maven\") {\n"
              "    content { includeGroup(\"com.modernipc\") }\n}\n```\n\n"
              "Use `com.modernipc:<module>:<version>`. All six SDK modules and `demo-client-common` are published together.\n"
              "The `receipts/` directory records the original Release archive and every Maven file hash.\n"
              "Source and usage: https://github.com/" + source_repository + "\n")
    writes["README.md"] = readme.encode("utf-8")
    # POMs, module metadata, receipts and checksums must keep their original bytes
    # even when the publishing checkout has core.autocrlf or global attributes.
    writes[".gitattributes"] = b"* -text\n"
    # Validate mutable paths too before starting the write phase.
    for relative in writes:
        target = safe_destination(repository, relative)
        require(not target.exists() or target.is_file(), f"Destination is not a file: {relative}")
    changed = []
    for relative, data in writes.items():
        target = safe_destination(repository, relative)
        if not target.exists() or target.read_bytes() != data:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            changed.append(relative)
    return {"status": "PREPARED", "version": version, "publicationCount": len(PUBLICATIONS),
            "artifactFileCount": len(expected), "changedFileCount": len(changed), "receipt": receipt_path,
            "archiveSha256": receipt["archiveSha256"]}


def verify(repository: Path, version: str, base_url: str, *, attempts: int = 4, workers: int = 4) -> dict:
    version_key(version)
    parsed = urllib.parse.urlsplit(base_url)
    require(parsed.scheme == "https" and parsed.netloc and parsed.username is None and parsed.password is None
            and not parsed.query and not parsed.fragment, "Anonymous Maven base URL must be HTTPS without credentials/query/fragment")
    require(1 <= attempts <= 6 and 1 <= workers <= 8, "Invalid attempts/workers")
    repository = repository.resolve()
    receipt_path = f"receipts/{version}.json"
    receipt_bytes = safe_destination(repository, receipt_path).read_bytes()
    receipt = json.loads(receipt_bytes)
    require(receipt.get("version") == version, "Receipt version differs")
    records = {item["path"]: {key: item[key] for key in ("size", "sha256")} for item in receipt["files"]}
    require(len(records) == len(receipt["files"]) and bool(records), "Invalid receipt file list")
    require({PurePosixPath(path).parts[2] for path in records} == set(PUBLICATIONS), "Receipt must contain all seven publications")
    for path, record in records.items():
        require(path.startswith("com/modernipc/") and PurePosixPath(path).parts[3] == version,
                f"Invalid receipt publication path: {path}")
        local = safe_destination(repository, path).read_bytes()
        require(len(local) == record["size"] and sha256(local) == record["sha256"], f"Local file differs from receipt: {path}")
    records[receipt_path] = {"size": len(receipt_bytes), "sha256": sha256(receipt_bytes)}
    for module in PUBLICATIONS:
        for suffix in ("", *(f".{name}" for name in CHECKSUMS)):
            path = f"com/modernipc/{module}/maven-metadata.xml{suffix}"
            data = safe_destination(repository, path).read_bytes()
            records[path] = {"size": len(data), "sha256": sha256(data)}

    # A dedicated opener has no auth/cookie handlers and ignores any globally
    # installed opener. Credentials in the environment are never inspected.
    opener = urllib.request.build_opener()

    def fetch(item: tuple[str, dict]) -> dict:
        relative, record = item
        url = base_url.rstrip("/") + "/" + urllib.parse.quote(relative, safe="/")
        error = ""
        for attempt in range(attempts):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "ModernIPC-Anonymous-Maven-Verification"})
                with opener.open(request, timeout=30) as response:
                    require(response.status == 200, f"Unexpected status {response.status}")
                    data = response.read(record["size"] + 1)
                require(len(data) == record["size"] and sha256(data) == record["sha256"], "Downloaded bytes differ")
                return {"path": relative, **record, "status": 200, "authorizationSent": False}
            except (OSError, ValueError, urllib.error.URLError) as exception:
                error = str(exception)
                if isinstance(exception, urllib.error.HTTPError):
                    exception.close()
                    if exception.code not in (404, 408, 429, 500, 502, 503, 504):
                        break
                if attempt + 1 < attempts:
                    time.sleep(min(2 ** attempt, 8))
        raise ValueError(f"Anonymous verification failed: {url}: {error}")

    with ThreadPoolExecutor(max_workers=workers) as executor:
        verified = list(executor.map(fetch, sorted(records.items())))
    return {"status": "VERIFIED", "version": version, "baseUrl": base_url,
            "verifiedUtc": datetime.now(timezone.utc).isoformat(), "authentication": "NONE",
            "publicationCount": len(PUBLICATIONS), "fileCount": len(verified), "files": verified}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("prepare", help="Validate and merge an existing Release bundle")
    stage.add_argument("--archive", type=Path, required=True)
    stage.add_argument("--manifest", type=Path, required=True)
    stage.add_argument("--source-repository", default="NingCui29/ModernIpc")
    remote = commands.add_parser("verify", help="Download and compare all files with no authentication")
    remote.add_argument("--base-url", required=True)
    remote.add_argument("--attempts", type=int, default=4)
    remote.add_argument("--workers", type=int, default=4)
    remote.add_argument("--output", type=Path, required=True)
    for command in (stage, remote):
        command.add_argument("--repository", type=Path, required=True)
        command.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.archive, args.manifest, args.repository, args.version, args.source_repository)
        else:
            result = verify(args.repository, args.version, args.base_url, attempts=args.attempts, workers=args.workers)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(json_bytes(result))
        print(json.dumps({key: value for key, value in result.items() if key != "files"}))
        return 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile, release.ReleaseError) as error:
        if args.command == "verify":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(json_bytes({"status": "FAILED", "version": args.version, "error": str(error)}))
        print(f"Anonymous Maven {args.command} failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
