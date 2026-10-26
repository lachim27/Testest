import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'restore-signing.py'

class SigningConfigurationTest(unittest.TestCase):
    def run_case(self, raw):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        env = dict(os.environ, ANDROID_SIGNING_BUNDLE=raw, RUNNER_TEMP=str(root),
                   GITHUB_OUTPUT=str(root/'output'), GITHUB_ENV=str(root/'env'),
                   GITHUB_STEP_SUMMARY=str(root/'summary'))
        result = subprocess.run(['python3', str(SCRIPT)], env=env, capture_output=True, text=True)
        return result, root

    def test_missing_secret_does_not_publish_or_create_a_key(self):
        result, root = self.run_case('')
        self.assertEqual(result.returncode, 0)
        self.assertEqual((root/'output').read_text(), 'configured=false\n')
        self.assertFalse((root/'zlap-signing.p12').exists())

    def test_invalid_secret_fails_without_printing_it(self):
        raw = '{not-a-secret-bundle}'
        result, root = self.run_case(raw)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(raw, result.stdout + result.stderr)
        self.assertFalse((root/'output').exists())

    def test_restore_preserves_exact_key_and_private_permissions(self):
        data = b'test fixture, not a private key'
        raw = json.dumps(dict(keystore=base64.b64encode(data).decode(), password='test-password', alias='test-alias'))
        result, root = self.run_case(raw)
        self.assertEqual(result.returncode, 0)
        self.assertEqual((root/'zlap-signing.p12').read_bytes(), data)
        self.assertEqual((root/'zlap-signing.p12').stat().st_mode & 0o777, 0o600)
        self.assertIn('configured=true', (root/'output').read_text())

    def test_environment_newlines_are_rejected(self):
        raw = json.dumps(dict(keystore='YWJj', password='pw\nOTHER=bad', alias='test'))
        result, root = self.run_case(raw)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((root/'env').exists())
