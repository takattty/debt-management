"""Exercise evidence lookup from outside a checkout and reject ambiguous owners."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class EvidenceOwnerTest(unittest.TestCase):
    def exercise(self, duplicate=False, arguments=None):
        with tempfile.TemporaryDirectory() as temporary:
            location = Path(temporary)
            owners = [location / "first", location / "second"]
            for owner in owners:
                (owner / "work").mkdir(parents=True)
            (owners[0] / "work/proof.log").write_text("verified\n")
            if duplicate:
                (owners[1] / "work/proof.log").write_text("different\n")
            fake = location / "git"
            fake.write_text(
                "#!/usr/bin/env python3\nimport json, os, sys\n"
                "sys.stdout.write(''.join('worktree '+p+'\\0\\0' for p in json.loads(os.environ['TEST_WORKTREES'])))\n"
            )
            fake.chmod(0o755)
            environment = os.environ | {
                "PATH": temporary + os.pathsep + os.environ["PATH"],
                "TEST_WORKTREES": json.dumps([str(owner) for owner in owners]),
            }
            return subprocess.run(
                [sys.executable, str(ROOT / "scripts/read-evidence.py"), *(arguments or ["work/proof.log"])],
                cwd=temporary, env=environment, text=True, capture_output=True, check=False,
            )

    def test_unique_owner_is_found_from_an_unrelated_cwd(self):
        result = self.exercise()
        self.assertEqual(result.returncode, 0)
        self.assertIn("first/work/proof.log", result.stdout)
        self.assertTrue(result.stdout.endswith("verified\n"))

    def test_ambiguous_owner_is_rejected_without_reading_either_file(self):
        result = self.exercise(duplicate=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("found 2", result.stderr)

    def test_parent_traversal_is_rejected(self):
        result = self.exercise(arguments=["work/../private.log"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
