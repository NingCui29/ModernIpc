"""Release checks which do not need Gradle, Android tools, devices or credentials."""
from __future__ import annotations

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
SPEC = importlib.util.spec_from_file_location("prepare_release", ROOT / ".github/scripts/prepare_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SignerTest(unittest.TestCase):
    FIRST, SECOND = "ab" * 32, "cd" * 32

    def test_numbered_signer_normalizes_case_and_ignores_source_stamp(self):
        output = (f"Signer #1 certificate SHA-256 digest: {self.FIRST.upper()}\r\n"
                  f"Source Stamp Signer certificate SHA-256 digest: {self.SECOND}\r\n")
        self.assertEqual([self.FIRST], release.signer_fingerprints(output, "demo"))

    def test_sdk_range_signers_are_deduplicated(self):
        output = (f"Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {self.FIRST}\n"
                  f"Signer(minSdkVersion=33) certificate SHA-256 digest: {self.FIRST.upper()}\n"
                  f"Signer #2 certificate SHA-256 digest: {self.SECOND}\n")
        self.assertEqual([self.FIRST, self.SECOND], release.signer_fingerprints(output, "demo"))

    def test_source_stamp_alone_is_rejected_with_diagnostic(self):
        output = f"Source Stamp Signer certificate SHA-256 digest: {self.SECOND}\n"
        with self.assertRaises(release.ReleaseError) as raised:
            release.signer_fingerprints(output, "demo")
        self.assertIn(repr(output), str(raised.exception))

    def test_malformed_second_application_signer_is_not_silently_ignored(self):
        for invalid in ("f" * 63, "g" * 64, "f" * 65):
            with self.subTest(invalid=invalid), self.assertRaises(release.ReleaseError):
                release.signer_fingerprints(
                    f"Signer #1 certificate SHA-256 digest: {self.FIRST}\n"
                    f"Signer #2 certificate SHA-256 digest: {invalid}\n", "demo")

    def test_unrecognized_signer_prefix_is_rejected(self):
        with self.assertRaises(release.ReleaseError):
            release.signer_fingerprints(f"Signer #0 certificate SHA-256 digest: {self.FIRST}\n", "demo")


class PublicationTest(unittest.TestCase):
    VERSION = "3.0.0-release-validation-test"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        root_patch = patch.object(release, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        for module, extension in release.PUBLICATIONS.items():
            directory = self.directory(module)
            directory.mkdir(parents=True)
            stem = f"{module}-{self.VERSION}"
            for name in (f"{stem}.{extension}", f"{stem}-sources.jar", f"{stem}-javadoc.jar"):
                with zipfile.ZipFile(directory / name, "w") as archive:
                    archive.writestr("fixture.txt", f"{module} {name}")
            dependencies = []
            pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
            for name, value in (("groupId", release.GROUP), ("artifactId", module), ("version", self.VERSION)):
                ET.SubElement(pom, name).text = value
            pom_deps = ET.SubElement(pom, "dependencies")
            public = release.verify_publication.__globals__["PUBLIC"].get(module, set())
            for group, artifact in sorted(public):
                version = self.VERSION if group == release.GROUP else "1.7.3"
                dependency = ET.SubElement(pom_deps, "dependency")
                for name, value in (("groupId", group), ("artifactId", artifact),
                                    ("version", version), ("scope", "compile")):
                    ET.SubElement(dependency, name).text = value
                dependencies.append({"group": group, "module": artifact, "version": {"requires": version}})
            ET.ElementTree(pom).write(directory / f"{stem}.pom", encoding="utf-8")
            main = directory / f"{stem}.{extension}"
            metadata = {"component": {"group": release.GROUP, "module": module, "version": self.VERSION},
                        "variants": [{"name": "api", "attributes": {"org.gradle.usage": "java-api"},
                                      "dependencies": dependencies,
                                      "files": [{"name": main.name, "url": main.name,
                                                 "size": main.stat().st_size, "sha256": release.digest(main)}]}]}
            (directory / f"{stem}.module").write_text(json.dumps(metadata), encoding="utf-8")

    def directory(self, module):
        return self.root / "local-maven/com/modernipc" / module / self.VERSION

    def test_complete_publications_verify_all_sdk_api_exports(self):
        publications = release.validate_publications(self.VERSION)
        sdk = [item for item in publications if item["kind"] == "sdk"]
        self.assertEqual(6, len(sdk))
        self.assertTrue(all(item["publicApiVerification"] for item in sdk))
        api = next(item for item in sdk if item["module"] == "ipc-api")
        self.assertIn("org.jetbrains.kotlinx:kotlinx-coroutines-core",
                      api["publicApiVerification"]["pomCompileExports"])

    def test_runtime_only_coroutines_in_pom_are_rejected(self):
        path = self.directory("ipc-api") / f"ipc-api-{self.VERSION}.pom"
        pom = ET.parse(path)
        ns = release.POM_NS
        for dependency in pom.getroot().findall("m:dependencies/m:dependency", ns):
            if dependency.findtext("m:artifactId", namespaces=ns) == "kotlinx-coroutines-core":
                dependency.find("m:scope", ns).text = "runtime"
        pom.write(path, encoding="utf-8")
        with self.assertRaisesRegex(release.ReleaseError, "POM compile scope"):
            release.validate_publications(self.VERSION)

    def test_missing_runtime_export_in_api_metadata_is_rejected(self):
        path = self.directory("ipc-api") / f"ipc-api-{self.VERSION}.module"
        metadata = json.loads(path.read_text(encoding="utf-8"))
        metadata["variants"][0]["dependencies"] = [
            item for item in metadata["variants"][0]["dependencies"] if item["module"] != "ipc-runtime-server"]
        path.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaisesRegex(release.ReleaseError, "API metadata"):
            release.validate_publications(self.VERSION)

    def test_remote_verification_checks_all_35_files_and_keeps_auth_off_redirects(self):
        publications = release.validate_publications(self.VERSION)
        responses = [path.read_bytes() for item in publications for path in sorted(
            (self.root / item["directory"]).iterdir()) if path.suffix in {".aar", ".jar", ".pom", ".module"}]

        def response(request, timeout):
            self.assertEqual(60, timeout)
            self.assertNotIn("Authorization", request.headers)
            self.assertIn("Authorization", request.unredirected_hdrs)
            result = io.BytesIO(responses.pop(0))
            result.status = 200
            return result

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_ACTOR": "user",
                                     "GITHUB_TOKEN": "test-only-token"}), \
                patch.object(release.urllib.request, "urlopen", side_effect=response), patch("builtins.print"):
            verified = release.verify_remote_publications(publications)
        self.assertEqual(35, len(verified))
        self.assertFalse(responses)

    def test_remote_artifact_with_changed_bytes_is_rejected(self):
        publication = release.validate_publications(self.VERSION)[0]
        changed = io.BytesIO(b"changed artifact")
        changed.status = 200
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_ACTOR": "user",
                                     "GITHUB_TOKEN": "test-only-token"}), \
                patch.object(release.urllib.request, "urlopen", return_value=changed), \
                self.assertRaisesRegex(release.ReleaseError, "Published artifact bytes differ"):
            release.verify_remote_publications([publication])


if __name__ == "__main__":
    unittest.main()
