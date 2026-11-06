import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "remote_consumer_verify", Path(__file__).resolve().parents[2] / "scripts/verify_maven_consumers.py")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class Response(io.BytesIO):
    status = 200


class RemoteMavenConsumerTest(unittest.TestCase):
    def publication(self):
        stem = "ipc-annotations-3.0.0-rc.3"
        artifact = b"anonymous published jar"
        metadata = {
            "component": {"group": "com.modernipc", "module": "ipc-annotations", "version": "3.0.0-rc.3"},
            "variants": [{"name": "apiElements", "attributes": {"org.gradle.usage": "java-api"},
                          "files": [{"name": stem + ".jar", "url": stem + ".jar",
                                     "sha256": hashlib.sha256(artifact).hexdigest()}]}],
        }
        pom = b'''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <groupId>com.modernipc</groupId><artifactId>ipc-annotations</artifactId>
          <version>3.0.0-rc.3</version></project>'''
        return stem, artifact, metadata, pom

    def test_anonymous_fetch_and_hash_verification(self):
        stem, artifact, metadata, pom = self.publication()
        opener = mock.Mock()
        requests = []

        def fetch(request, timeout):
            requests.append(request)
            if request.full_url.endswith(".pom"):
                return Response(pom)
            if request.full_url.endswith(".module"):
                return Response(json.dumps(metadata).encode())
            return Response(artifact)

        opener.open.side_effect = fetch
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(verify, "MODULES", ("ipc-annotations",)), \
                mock.patch.object(verify.urllib.request, "build_opener", return_value=opener):
            records = verify.download_remote_publications("https://example.test/maven", Path(directory), "3.0.0-rc.3")
            self.assertEqual(3, len(records))
            self.assertTrue(all(record["authorization"] is False for record in records))
            self.assertTrue(all(not request.has_header("Authorization") and not request.has_header("Cookie")
                                for request in requests))
            self.assertTrue(all(request.full_url.startswith("https://example.test/maven/com/modernipc/")
                                for request in requests))
            self.assertEqual(artifact, (Path(directory) / "com/modernipc/ipc-annotations/3.0.0-rc.3" / (stem + ".jar")).read_bytes())

    def test_unsafe_urls_rejected_before_fetch(self):
        for url in ("http://example.test/maven", "https://user:token@example.test/maven",
                    "https://example.test/maven?token=value", "https://example.test/maven#fragment"):
            with self.subTest(url=url), self.assertRaises(RuntimeError):
                verify.anonymous_repository_url(url)
        handler = verify.AnonymousHttpsRedirectHandler()
        with self.assertRaises(RuntimeError):
            handler.redirect_request(mock.Mock(), None, 302, "Found", {}, "http://example.test/maven")

    def test_remote_metadata_cannot_escape_or_change_coordinates(self):
        for failure in ("path", "coordinates", "hash"):
            with self.subTest(failure=failure):
                stem, artifact, metadata, pom = self.publication()
                if failure == "path":
                    metadata["variants"][0]["files"][0].update(name="../outside.jar", url="../outside.jar")
                elif failure == "coordinates":
                    metadata["component"]["group"] = "com.other"
                else:
                    artifact = b"corrupted bytes"
                opener = mock.Mock()
                opener.open.side_effect = [Response(pom), Response(json.dumps(metadata).encode()), Response(artifact)]
                with tempfile.TemporaryDirectory() as directory, \
                        mock.patch.object(verify, "MODULES", ("ipc-annotations",)), \
                        mock.patch.object(verify.urllib.request, "build_opener", return_value=opener), \
                        self.assertRaises(RuntimeError):
                    verify.download_remote_publications("https://example.test/maven", Path(directory), "3.0.0-rc.3")

    def test_remote_mode_uses_existing_version_and_separate_local_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text("VERSION_NAME=3.0.0-rc.3\n", encoding="utf-8")
            output = root / "verification"
            for mode in ("metadata", "pom"):
                generated = output / mode / "build/custom-contract/generated/ksp/release/kotlin"
                generated.mkdir(parents=True)
                for name in ("ICustomServiceClientAdapter.kt", "ICustomServiceServerStub.kt", "ICustomServiceIpcSchema.kt"):
                    (generated / name).touch()
            invocations = []

            def run(command, **kwargs):
                invocations.append((command, kwargs["env"]))
                if "-PinvalidFacade=nested" in command:
                    kwargs["stdout"].write("@IpcFacade requires a top-level interface\n")
                    return subprocess.CompletedProcess(command, 1)
                return subprocess.CompletedProcess(command, 0)

            argv = ["verify", "--remote-repository", "https://example.test/maven", "--version", "3.0.0-rc.3",
                    "--java-home", str(root), "--android-sdk", str(root), "--output", str(output)]
            with mock.patch.object(verify, "ROOT", root), mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(verify, "download_remote_publications", return_value=[]), \
                    mock.patch.object(verify, "verify_publication", return_value={}), \
                    mock.patch.object(verify, "verify_graph", return_value={}), \
                    mock.patch.object(verify.subprocess, "run", side_effect=run), \
                    mock.patch.dict(verify.os.environ, {"GITHUB_TOKEN": "test-token", "GH_TOKEN": "test-token"}), \
                    mock.patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, verify.main())
            self.assertEqual(5, len(invocations))
            for command, env in invocations:
                self.assertIn("-PipcRepository=https://example.test/maven", command)
                self.assertIn(f"-PfixtureRepository={output / 'fixture-repository'}", command)
                self.assertIn("--refresh-dependencies", command)
                self.assertIn("--rerun-tasks", command)
                self.assertFalse(any("PublicationToProjectLocalRepository" in arg for arg in command))
                self.assertNotIn("GITHUB_TOKEN", env)
                self.assertNotIn("GH_TOKEN", env)
            report = json.loads((output / "verification.json").read_text(encoding="utf-8"))
            self.assertEqual("PASSED", report["status"])
            self.assertEqual("remote-anonymous", report["mode"])
            self.assertEqual("https://example.test/maven", report["repository"])

    def test_remote_mode_rejects_offline_and_missing_explicit_version(self):
        for extra in ([], ["--version", "3.0.0-rc.3", "--offline"],
                      ["--version", "3.0.0-rc.3", "--repository", "local"]):
            with self.subTest(extra=extra), mock.patch.object(sys, "argv", [
                    "verify", "--remote-repository", "https://example.test/maven", *extra]), \
                    self.assertRaises(RuntimeError):
                verify.main()


if __name__ == "__main__":
    unittest.main()
