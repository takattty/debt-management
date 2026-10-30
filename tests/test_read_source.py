"""Read only unique, real paths discovered from the selected Git source catalog."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SourceReaderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.repository = self.directory / "checkout"
        self.repository.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repository)], check=True)
        for name, content in {
            "backend/InvoiceQueries.java": "first\nsecond\nthird\n",
            "backend/CustomerQueries.java": "customer source\n",
            "frontend/CustomerDetail.tsx": "frontend source\n",
            "work/private.py": "secret work content\n",
            ".aws/credentials.py": "secret credentials\n",
            "backend/secrets.py": "secret source config\n",
            "backend/config.env": "secret environment\n",
            "backend/private.key": "secret key\n",
            "backend/deleted.java": "deleted tracked file\n",
        }.items():
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        external = self.directory / "outside.java"
        external.write_text("outside secret\n")
        (self.repository / "backend/linked.java").symlink_to(external)
        (self.repository / "backend/local.java").symlink_to("InvoiceQueries.java")
        subprocess.run(["git", "-C", str(self.repository), "add", "."], check=True)
        (self.repository / "backend/deleted.java").unlink()
        (self.repository / "backend/Untracked.java").write_text("untracked secret\n")

    def run_cli(self, *arguments):
        return subprocess.run([
            sys.executable, str(ROOT / "scripts/read-source.py"), *arguments,
            "--worktree", str(self.repository),
        ], cwd=self.directory, env=os.environ | {"GIT_CONFIG_NOSYSTEM": "1"},
            text=True, capture_output=True, check=False)

    def test_catalog_then_observed_path_range_from_unrelated_cwd(self):
        catalog = self.run_cli("Invoice", "--list")
        self.assertEqual(catalog.returncode, 0, catalog.stderr)
        self.assertEqual(catalog.stdout, "backend/InvoiceQueries.java\n")
        result = self.run_cli(catalog.stdout.strip(), "--start", "2", "--end", "3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "backend/InvoiceQueries.java:2-3\n2: second\n3: third\n")

    def test_regex_discovery_and_ambiguity_show_paths_without_contents(self):
        result = self.run_cli(r"(Invoice|Customer)Queries\.java$", "--regex")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout.splitlines(), ["backend/CustomerQueries.java", "backend/InvoiceQueries.java"])
        self.assertIn("select an observed path", result.stderr)
        self.assertNotIn("customer source", result.stdout)
        self.assertNotIn("first", result.stdout)

    def test_missing_guessed_name_shows_real_candidates_without_contents(self):
        result = self.run_cli("InvoiceDetailsService.java")
        self.assertEqual(result.returncode, 1)
        self.assertIn("backend/InvoiceQueries.java", result.stdout)
        self.assertIn("Candidate paths only", result.stderr)
        self.assertNotIn("first", result.stdout)

    def test_catalog_excludes_secret_untracked_deleted_and_symlink_files(self):
        result = self.run_cli(".*", "--regex", "--list")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "backend/CustomerQueries.java", "backend/InvoiceQueries.java", "frontend/CustomerDetail.tsx",
        ])
        self.assertNotIn("secret", result.stdout)

    def test_absolute_traversal_bad_regex_and_bad_ranges_are_rejected(self):
        for arguments in (("/etc/passwd",), ("../outside.java",), ("backend/../outside.java",),
                          ("Invoice", "--start", "0"), ("Invoice", "--start", "4", "--end", "3"),
                          ("[", "--regex")):
            with self.subTest(arguments=arguments):
                result = self.run_cli(*arguments)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")
                self.assertNotIn("outside secret", result.stderr)

    def test_nested_worktree_selector_does_not_implicitly_select_a_repository(self):
        result = subprocess.run([
            sys.executable, str(ROOT / "scripts/read-source.py"), "Invoice",
            "--worktree", str(self.repository / "backend"),
        ], text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
