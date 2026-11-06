import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "consumer_verify", Path(__file__).resolve().parents[2] / "scripts/verify_maven_consumers.py")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class SdkDiscoveryTest(unittest.TestCase):
    def test_java_property_escaping_and_continuation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "local.properties"
            path.write_text("# comment\nsdk.dir=C\\:\\\\Android\\ SDK\\\\\u4e2d\n", encoding="utf-8")
            self.assertEqual("C:\\Android SDK\\中", verify.read_sdk_property(path))
            path.write_text("sdk.dir=old\nsdk.dir = /opt/\\\n  android\\u0020sdk\n", encoding="utf-8")
            self.assertEqual("/opt/android sdk", verify.read_sdk_property(path))

    def test_precedence_and_environment_normalization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("local", "home", "sdkroot", "explicit"):
                (root / name).mkdir()
            (root / "local.properties").write_text("sdk.dir=local\n", encoding="utf-8")
            cases = [({}, None, "local", "local.properties:sdk.dir"),
                     ({"ANDROID_SDK_ROOT": str(root / "sdkroot")}, None, "sdkroot", "ANDROID_SDK_ROOT"),
                     ({"ANDROID_HOME": str(root / "home"), "ANDROID_SDK_ROOT": str(root / "sdkroot")}, None, "home", "ANDROID_HOME"),
                     ({"ANDROID_HOME": str(root / "home")}, root / "explicit", "explicit", "--android-sdk")]
            for env, option, name, source in cases:
                self.assertEqual(source, verify.configure_android_sdk(env, option, root))
                self.assertEqual(str((root / name).resolve()), env["ANDROID_HOME"])
                self.assertEqual(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"])

    def test_missing_and_invalid_explicit_sdk_fail_early(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "SDK not found"):
                verify.configure_android_sdk({}, None, root)
            (root / "local.properties").write_text("sdk.dir=.\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "--android-sdk"):
                verify.configure_android_sdk({}, root / "missing", root)


if __name__ == "__main__":
    unittest.main()
