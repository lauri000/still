import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("zapstore.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class LocalSigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ["release.p12", "password.txt"]:
            file = self.root / name
            file.write_text("test-only-placeholder")
            file.chmod(0o600)
        self.release = {"version": "0.1.0", "source_commit": "a" * 40,
                        "certificate_sha256": "b" * 64, "apk": "app.apk"}

    def execute(self, mode, certificate=None):
        calls = []

        def invoke(args, env, output=None, input_text=None):
            calls.append((args, output))
            self.assertNotIn("nsec1test-secret", " ".join(args))
            if "--verify" in args:
                self.assertEqual(input_text, "npub1public\n")
                self.assertEqual(env["SIGN_WITH"], "npub1public")
                self.assertNotIn("KEYSTORE_PASSWORD", env)
            return [{"kind": 30509, "pubkey": "c" * 64,
                     "tags": [["d", certificate or self.release["certificate_sha256"]]]}]

        with patch.object(publisher, "release", return_value=(self.release, "npub1public")), \
             patch.object(publisher, "DIST", self.root), \
             patch.object(publisher, "invoke", side_effect=invoke), \
             patch.object(publisher, "link_identity", side_effect=invoke), \
             patch.object(publisher.sys.stdin, "isatty", return_value=True), \
             patch.object(publisher.getpass, "getpass", return_value="nsec1test-secret") as prompt, \
             patch.dict(os.environ, {"STILL_SIGNING_DIR": str(self.root)}):
            publisher.execute(mode)
            if mode == "check":
                prompt.assert_not_called()
        return calls

    def test_check_uses_no_private_key_or_network(self):
        calls = self.execute("check")
        self.assertEqual(len(calls), 1)
        self.assertIn("--offline", calls[0][0])

    def test_sign_only_creates_offline_events_and_identity_proof(self):
        calls = self.execute("sign")
        self.assertEqual([args[0] for args, _ in calls], ["publish", "identity"])
        self.assertTrue(all("--offline" in args for args, _ in calls))

    def test_publish_validates_offline_before_any_network_operation(self):
        calls = self.execute("publish")
        self.assertEqual(["--offline" in args for args, _ in calls], [True, True, False, False, False])
        self.assertIn("--verify", calls[3][0])

    def test_identity_menu_uses_the_callers_terminal(self):
        with patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as run:
            publisher.link_identity(["identity"], {})
        self.assertNotIn("capture_output", run.call_args.kwargs)

    def test_cancelled_identity_link_stops_publication(self):
        with patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=130)):
            with self.assertRaisesRegex(RuntimeError, "publication was stopped"):
                publisher.link_identity(["identity"], {})

    def test_wrong_keystore_never_reaches_online_publish(self):
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            self.execute("publish", certificate="wrong")

    def test_tool_errors_redact_private_credentials(self):
        result = SimpleNamespace(returncode=1, stdout="", stderr="failed nsec1test-secret password-value")
        with patch.object(publisher.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                publisher.invoke(["publish"], {"SIGN_WITH": "nsec1test-secret", "KEYSTORE_PASSWORD": "password-value"})
        self.assertNotIn("nsec1test-secret", str(error.exception))
        self.assertNotIn("password-value", str(error.exception))

    def test_changed_apk_is_rejected_before_signing(self):
        (self.root / "app.apk").write_bytes(b"changed APK")
        (self.root / "release.json").write_text(json.dumps({"apk": "app.apk", "sha256": "wrong"}))
        with patch.object(publisher, "DIST", self.root):
            with self.assertRaisesRegex(RuntimeError, "checksum differs"):
                publisher.release()


if __name__ == "__main__":
    unittest.main()
