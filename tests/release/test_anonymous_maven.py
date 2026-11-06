"""Immutable anonymous distribution checks; no network, Gradle or device required."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("publish_anonymous_maven", ROOT / "scripts/publish_anonymous_maven.py")
mirror = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mirror)


class AnonymousMavenTest(unittest.TestCase):
    VERSION = "3.0.0-rc.3"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "destination"
        self.archive, self.manifest = self.bundle(self.VERSION)

    def bundle(self, version, transform=None):
        files = {}
        publications = []
        public = mirror.release.verify_publication.__globals__["PUBLIC"]
        for module, extension in mirror.PUBLICATIONS.items():
            directory = f"com/modernipc/{module}/{version}"
            stem = f"{module}-{version}"
            primary = {}
            for name in (f"{stem}.{extension}", f"{stem}-sources.jar", f"{stem}-javadoc.jar"):
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w") as archive:
                    archive.writestr("fixture.txt", f"{name} content")
                primary[name] = output.getvalue()
            pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
            for key, value in (("groupId", "com.modernipc"), ("artifactId", module), ("version", version)):
                ET.SubElement(pom, key).text = value
            pom_deps = ET.SubElement(pom, "dependencies")
            dependencies = []
            for group, artifact in sorted(public.get(module, set())):
                dep_version = version if group == "com.modernipc" else "1.7.3"
                node = ET.SubElement(pom_deps, "dependency")
                for key, value in (("groupId", group), ("artifactId", artifact), ("version", dep_version), ("scope", "compile")):
                    ET.SubElement(node, key).text = value
                dependencies.append({"group": group, "module": artifact, "version": {"requires": dep_version}})
            primary[f"{stem}.pom"] = ET.tostring(pom, encoding="utf-8")
            main = primary[f"{stem}.{extension}"]
            metadata = {"component": {"group": "com.modernipc", "module": module, "version": version},
                        "variants": [{"name": "api", "attributes": {"org.gradle.usage": "java-api"},
                                      "dependencies": dependencies, "files": [{"name": f"{stem}.{extension}",
                                          "url": f"{stem}.{extension}", "size": len(main), "sha256": mirror.sha256(main)}]}]}
            primary[f"{stem}.module"] = mirror.json_bytes(metadata)
            if transform:
                transform(module, primary)
            entries = dict(primary)
            for name, data in primary.items():
                for algorithm in mirror.CHECKSUMS:
                    entries[f"{name}.{algorithm}"] = hashlib.new(algorithm, data).hexdigest().encode("ascii")
            files.update({f"{directory}/{name}": data for name, data in entries.items()})
            publications.append({"module": module, "coordinate": f"com.modernipc:{module}:{version}",
                                 "directory": f"local-maven/{directory}", "files": [
                                     {"name": name, "size": len(data), "sha256": mirror.sha256(data)}
                                     for name, data in entries.items()]})
        archive_path = self.root / f"{version}.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        manifest_path = self.root / f"{version}.json"
        data = archive_path.read_bytes()
        manifest = {"version": version, "tag": f"v{version}", "commit": "a" * 40,
                    "createdUtc": "2026-11-06T03:35:17+00:00", "sdkPublications": publications[:6],
                    "examplePublications": publications[6:], "assetsExcludingManifestAndChecksumFile": [
                        {"name": "maven-repository.zip", "size": len(data), "sha256": mirror.sha256(data)}]}
        manifest_path.write_bytes(mirror.json_bytes(manifest))
        return archive_path, manifest_path

    def prepare(self, version=None, archive=None, manifest=None):
        return mirror.prepare(archive or self.archive, manifest or self.manifest, self.repository,
                              version or self.VERSION, "NingCui29/ModernIpc")

    def snapshot(self):
        if not self.repository.exists():
            return {}
        return {path.relative_to(self.repository).as_posix(): path.read_bytes()
                for path in self.repository.rglob("*") if path.is_file()}

    def update_archive_digest(self):
        manifest = json.loads(self.manifest.read_bytes())
        data = self.archive.read_bytes()
        manifest["assetsExcludingManifestAndChecksumFile"][0].update(size=len(data), sha256=mirror.sha256(data))
        self.manifest.write_bytes(mirror.json_bytes(manifest))

    def test_seven_complete_publications_are_copied_byte_for_byte_and_idempotent(self):
        result = self.prepare()
        self.assertEqual(7, result["publicationCount"])
        self.assertEqual(175, result["artifactFileCount"])
        self.assertEqual(b"* -text\n", (self.repository / ".gitattributes").read_bytes())
        with zipfile.ZipFile(self.archive) as archive:
            for name in archive.namelist():
                self.assertEqual(archive.read(name), (self.repository / name).read_bytes())
        before = self.snapshot()
        self.assertEqual(0, self.prepare()["changedFileCount"])
        self.assertEqual(before, self.snapshot())

    def test_archive_hash_mismatch_fails_before_writing(self):
        with self.archive.open("ab") as stream:
            stream.write(b"corrupt")
        with self.assertRaisesRegex(ValueError, "ZIP digest"):
            self.prepare()
        self.assertFalse(self.repository.exists())

    def test_missing_publication_rejected_before_writing(self):
        manifest = json.loads(self.manifest.read_bytes())
        manifest["examplePublications"] = []
        self.manifest.write_bytes(mirror.json_bytes(manifest))
        with self.assertRaisesRegex(ValueError, "seven publications"):
            self.prepare()
        self.assertFalse(self.repository.exists())

    def test_extra_traversal_member_rejected_even_with_correct_archive_digest(self):
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("../escape", "unsafe")
        self.update_archive_digest()
        with self.assertRaisesRegex(ValueError, "ZIP members differ"):
            self.prepare()
        self.assertFalse(self.repository.exists())
        self.assertFalse((self.root / "escape").exists())

    def test_symlink_archive_member_is_rejected(self):
        with zipfile.ZipFile(self.archive) as archive:
            entries = [(item, archive.read(item)) for item in archive.infolist()]
        entries[0][0].external_attr = (0o120777 << 16)
        with zipfile.ZipFile(self.archive, "w") as archive:
            for info, data in entries:
                archive.writestr(info, data)
        self.update_archive_digest()
        with self.assertRaisesRegex(ValueError, "Unsafe ZIP member"):
            self.prepare()
        self.assertFalse(self.repository.exists())

    def test_immutable_conflict_does_not_modify_other_files(self):
        self.prepare()
        victim = self.repository / f"com/modernipc/ipc-compiler/{self.VERSION}/ipc-compiler-{self.VERSION}.jar"
        victim.write_bytes(b"previous published different bytes")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
            self.prepare()
        self.assertEqual(before, self.snapshot())

    def test_existing_partial_version_is_not_silently_repaired(self):
        self.prepare()
        victim = self.repository / f"com/modernipc/ipc-api/{self.VERSION}/ipc-api-{self.VERSION}.pom"
        victim.unlink()
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "file set differs"):
            self.prepare()
        self.assertEqual(before, self.snapshot())

    def test_preserves_old_versions_semver_order_and_no_downgrade_on_backfill(self):
        self.prepare()
        original = {key: value for key, value in self.snapshot().items() if f"/{self.VERSION}/" in key}
        for version in ("3.0.0-rc.10", "3.0.0", "2.9.0"):
            archive, manifest = self.bundle(version)
            self.prepare(version, archive, manifest)
        metadata = ET.parse(self.repository / "com/modernipc/ipc-api/maven-metadata.xml")
        self.assertEqual(["2.9.0", "3.0.0-rc.3", "3.0.0-rc.10", "3.0.0"],
                         [node.text for node in metadata.findall("versioning/versions/version")])
        self.assertEqual("3.0.0", metadata.findtext("versioning/latest"))
        self.assertEqual("3.0.0", metadata.findtext("versioning/release"))
        for path, data in original.items():
            self.assertEqual(data, (self.repository / path).read_bytes())

    def test_valid_archive_hash_cannot_hide_invalid_internal_metadata(self):
        def mutate(module, files):
            if module == "ipc-api":
                name = f"{module}-{self.VERSION}.module"
                metadata = json.loads(files[name])
                metadata["variants"][0]["dependencies"][0]["version"]["requires"] = "0.0.0"
                files[name] = mirror.json_bytes(metadata)
        archive, manifest = self.bundle(self.VERSION, mutate)
        with self.assertRaisesRegex(mirror.release.ReleaseError, "internal dependency"):
            self.prepare(archive=archive, manifest=manifest)
        self.assertFalse(self.repository.exists())

    def test_anonymous_remote_verification_checks_artifacts_metadata_and_receipt(self):
        self.prepare()
        seen = []

        class Opener:
            def open(inner_self, request, timeout):
                self.assertEqual(30, timeout)
                self.assertFalse(request.has_header("Authorization"))
                self.assertFalse(request.has_header("Cookie"))
                relative = request.full_url.removeprefix("https://example.org/maven/")
                seen.append(relative)
                response = io.BytesIO((self.repository / relative).read_bytes())
                response.status = 200
                return response

        with patch.dict(os.environ, {"GITHUB_TOKEN": "must-not-be-used", "GITHUB_ACTOR": "user"}), \
                patch.object(mirror.urllib.request, "build_opener", return_value=Opener()):
            result = mirror.verify(self.repository, self.VERSION, "https://example.org/maven")
        self.assertEqual("VERIFIED", result["status"])
        self.assertEqual("NONE", result["authentication"])
        self.assertEqual(211, result["fileCount"])
        self.assertEqual(211, len(set(seen)))

    def test_remote_wrong_bytes_rejected_and_credentials_url_disallowed(self):
        self.prepare()

        class Opener:
            def open(inner_self, request, timeout):
                response = io.BytesIO(b"wrong bytes")
                response.status = 200
                return response

        with patch.object(mirror.urllib.request, "build_opener", return_value=Opener()), \
                self.assertRaisesRegex(ValueError, "Downloaded bytes differ"):
            mirror.verify(self.repository, self.VERSION, "https://example.org/maven", attempts=1)
        with self.assertRaisesRegex(ValueError, "without credentials"):
            mirror.verify(self.repository, self.VERSION, "https://user:token@example.org/maven")

    def test_remote_transient_failure_retries_without_credentials(self):
        self.prepare()
        failed = False

        class Opener:
            def open(inner_self, request, timeout):
                nonlocal failed
                self.assertFalse(request.has_header("Authorization"))
                if not failed:
                    failed = True
                    raise mirror.urllib.error.HTTPError(request.full_url, 404, "not cached yet", {}, None)
                relative = request.full_url.removeprefix("https://example.org/maven/")
                response = io.BytesIO((self.repository / relative).read_bytes())
                response.status = 200
                return response

        with patch.object(mirror.urllib.request, "build_opener", return_value=Opener()), patch.object(mirror.time, "sleep"):
            result = mirror.verify(self.repository, self.VERSION, "https://example.org/maven", attempts=2, workers=1)
        self.assertTrue(failed)
        self.assertEqual("VERIFIED", result["status"])


if __name__ == "__main__":
    unittest.main()
