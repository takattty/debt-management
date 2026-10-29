"""Exercise the shipped Compose entry point without starting containers."""

import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ComposeSeedTest(unittest.TestCase):
    def config(self, password):
        environment = os.environ.copy()
        environment.pop("LOCAL_SEED_PASSWORD", None)
        if password is not None:
            environment["LOCAL_SEED_PASSWORD"] = password
        return subprocess.run(
            ["docker", "compose", "--env-file", "/dev/null", "config", "--quiet"],
            cwd=ROOT, env=environment, text=True, capture_output=True, check=False,
        )

    def test_missing_and_empty_password_reject_configuration(self):
        for password in (None, ""):
            with self.subTest(password_present=password is not None):
                result = self.config(password)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("LOCAL_SEED_PASSWORD", result.stderr)

    def test_explicit_fictional_password_accepts_configuration(self):
        self.assertEqual(self.config("debt_local_demo_2026").returncode, 0)
