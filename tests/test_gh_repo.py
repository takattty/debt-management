"""Keep GitHub commands tied to an explicit repository outside a checkout."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class GithubRepositoryTest(unittest.TestCase):
    def run_cli(self, arguments):
        with tempfile.TemporaryDirectory() as temporary:
            fake = Path(temporary) / "gh"
            fake.write_text(
                "#!/usr/bin/env python3\nimport json, os, sys\n"
                "print(json.dumps({'repo': os.environ['GH_REPO'], 'args': sys.argv[1:]}))\n"
            )
            fake.chmod(0o755)
            environment = os.environ | {
                "PATH": temporary + os.pathsep + os.environ["PATH"],
                "GH_REPO": "wrong/repository",
            }
            return subprocess.run(
                [str(ROOT / "scripts/gh-repo.sh"), *arguments],
                cwd=temporary, env=environment, text=True, capture_output=True, check=False,
            )

    def test_explicit_repository_survives_an_unrelated_cwd_and_inherited_selector(self):
        result = self.run_cli(["takattty/debt-management", "run", "view", "123"])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout), {
            "repo": "takattty/debt-management", "args": ["run", "view", "123"],
        })

    def test_missing_repository_never_invokes_github(self):
        result = self.run_cli(["run", "view", "123"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")

    def test_unsupported_pr_field_is_rejected_before_github(self):
        for arguments in (
            ["--json", "number,url,headRefOid,baseRefOid,statusCheckRollup,reviews"],
            ["--json=baseRefOid,headRefOid"],
        ):
            with self.subTest(arguments=arguments):
                result = self.run_cli(["takattty/debt-management", "pr", "view", "38", *arguments])
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertIn("gh-pr-metadata.py", result.stderr)

    def test_supported_fields_and_other_commands_pass_through(self):
        for arguments in (
            ["pr", "view", "38", "--json", "number,headRefOid,reviews"],
            ["issue", "view", "38", "--json", "body"],
            ["pr", "create", "--body", "baseRefOid"],
        ):
            with self.subTest(arguments=arguments):
                result = self.run_cli(["takattty/debt-management", *arguments])
                self.assertEqual(result.returncode, 0)
                self.assertEqual(json.loads(result.stdout)["args"], arguments)
