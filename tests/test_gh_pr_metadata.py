"""Exercise PR metadata over a fake gh executable; no API or credentials required."""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PULL = {
    "number": 38, "html_url": "https://github.com/example/project/pull/38",
    "state": "closed", "merged": True,
    "head": {"sha": "a" * 40, "ref": "fix/example"},
    "base": {"sha": "b" * 40, "ref": "main"},
    "body": "private metadata must never be echoed",
}


class GithubPrMetadataTest(unittest.TestCase):
    def run_cli(self, payload=PULL, arguments=("example/project", "38"), exit_code=0):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            invocation = directory / "invocation.json"
            (directory / "response.json").write_text(
                json.dumps(payload) if not isinstance(payload, str) else payload,
            )
            fake = directory / "gh"
            fake.write_text(
                "#!/usr/bin/env python3\nimport json, os, pathlib, sys\n"
                "pathlib.Path('invocation.json').write_text(json.dumps({"
                "'repo': os.environ['GH_REPO'], 'args': sys.argv[1:]}))\n"
                "print(pathlib.Path('response.json').read_text())\n"
                "if int(os.environ['FAKE_EXIT']): print('API denied', file=sys.stderr)\n"
                "sys.exit(int(os.environ['FAKE_EXIT']))\n"
            )
            fake.chmod(0o755)
            result = subprocess.run(
                [sys.executable, str(ROOT / "scripts/gh-pr-metadata.py"), *arguments],
                cwd=directory, text=True, capture_output=True, check=False,
                env=os.environ | {
                    "PATH": temporary + os.pathsep + os.environ["PATH"],
                    "GH_REPO": "wrong/repository", "GH_HOST": "wrong.example",
                    "FAKE_EXIT": str(exit_code),
                },
            )
            called = json.loads(invocation.read_text()) if invocation.exists() else None
            return result, called

    def test_fixed_rest_contract_and_explicit_repository_outside_checkout(self):
        result, called = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(called, {
            "repo": "example/project", "args": [
                "api", "--hostname", "github.com", "--method", "GET",
                "repos/example/project/pulls/38",
            ],
        })
        self.assertEqual(json.loads(result.stdout), {
            "number": 38, "url": PULL["html_url"], "state": "closed", "merged": True,
            "headRefOid": "a" * 40, "headRefName": "fix/example",
            "baseRefOid": "b" * 40, "baseRefName": "main",
        })

    def test_missing_or_invalid_contract_never_emits_partial_metadata(self):
        cases = [None, [], "bad json"]
        for key, value in (("number", True), ("number", 39), ("html_url", "https://github.com/wrong/repo/pull/38"),
                           ("state", "merged"), ("merged", None), ("head", None),
                           ("base", {"sha": "bad", "ref": "main"}),
                           ("head", {"sha": "a" * 40, "ref": ""})):
            payload = copy.deepcopy(PULL)
            payload[key] = value
            cases.append(payload)
        for side in ("head", "base"):
            payload = copy.deepcopy(PULL)
            del payload[side]["sha"]
            cases.append(payload)
        for payload in cases:
            with self.subTest(payload=payload):
                result, _ = self.run_cli(payload)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, "")
                self.assertIn("PR metadata rejected", result.stderr)
                self.assertNotIn(PULL["body"], result.stderr)

    def test_api_failure_preserves_exit_and_error_without_metadata(self):
        result, _ = self.run_cli(exit_code=7)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(result.stdout, "")
        self.assertIn("API denied", result.stderr)

    def test_explicit_repository_and_numeric_pr_are_required_before_api(self):
        for arguments in ((), ("38",), ("example/project", "0"), ("example/project", "../38"),
                          ("https://github.com/example/project", "38")):
            with self.subTest(arguments=arguments):
                result, called = self.run_cli(arguments=arguments)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertIsNone(called)
