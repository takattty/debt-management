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

    def test_single_huge_source_line_refuses_without_header_or_body(self):
        path = self.repository / "backend/InvoiceQueries.java"
        original = "private-source-marker" * 1000 + "\n"
        path.write_text(original)
        result = self.run_cli("backend/InvoiceQueries.java", "--end", "1")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("8192", result.stderr)
        self.assertIn("--start/--end", result.stderr)
        self.assertNotIn("private-source-marker", result.stderr)
        self.assertEqual(path.read_text(), original)

    def test_utf8_byte_limit_includes_path_header_and_line_number_at_boundary(self):
        relative = "backend/InvoiceQueries.java"
        overhead = len(f"{relative}:1-1\n1: \n".encode("utf-8"))
        budget = 8192 - overhead
        payload = "日" * (budget // 3) + "x" * (budget % 3)
        path = self.repository / relative
        path.write_text(payload + "\n", encoding="utf-8")
        result = self.run_cli(relative, "--end", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(result.stdout.encode("utf-8")), 8192)
        self.assertTrue(result.stdout.endswith(payload + "\n"))
        path.write_text(payload + "x\n", encoding="utf-8")
        result = self.run_cli(relative, "--end", "1")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")

    def test_japanese_characters_cannot_bypass_byte_budget(self):
        (self.repository / "backend/InvoiceQueries.java").write_text("名" * 3000, encoding="utf-8")
        result = self.run_cli("backend/InvoiceQueries.java")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")

    def test_many_catalog_matches_refuse_in_list_and_ambiguity_modes(self):
        names = []
        for number in range(120):
            name = f"backend/Many{number:03d}" + "X" * 64 + ".java"
            (self.repository / name).write_text("catalog source body must not escape\n")
            names.append(name)
        subprocess.run(["git", "-C", str(self.repository), "add", "backend"], check=True)
        for args in (("Many", "--list"), ("Many",)):
            result = self.run_cli(*args)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, "")
            self.assertIn("narrow the query", result.stderr)
        result = self.run_cli(names[0], "--list")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, names[0] + "\n")

    def test_oversized_candidate_fallback_also_refuses_without_partial_catalog(self):
        for number in range(10):
            path = self.repository / ("long" + "x" * 170) / ("nested" + "y" * 170) / ("more" + "z" * 170)
            path /= "deep" + "q" * 170
            path /= "extra" + "r" * 170
            path.mkdir(parents=True, exist_ok=True)
            (path / ("Similar" + str(number) + ".java")).write_text("hidden body\n")
        subprocess.run(["git", "-C", str(self.repository), "add", "."], check=True)
        result = self.run_cli("SimilarMissing.java")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("8192", result.stderr)
        self.assertNotIn("hidden body", result.stderr)

    def test_line_number_bytes_are_bounded_and_narrow_ranges_keep_exact_lines(self):
        path = self.repository / "backend/InvoiceQueries.java"
        path.write_text("0123456789\n" * 600)
        self.assertLess(path.stat().st_size, 8192)
        result = self.run_cli("backend/InvoiceQueries.java", "--end", "600")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        result = self.run_cli("backend/InvoiceQueries.java", "--start", "591", "--end", "600")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[1:], [f"{n}: 0123456789" for n in range(591, 601)])

    def test_huge_secret_source_stays_excluded_instead_of_reading_its_payload(self):
        secret = self.repository / "backend/Passwords.java"
        secret.write_text("private-token-body" * 1000)
        subprocess.run(["git", "-C", str(self.repository), "add", str(secret)], check=True)
        result = self.run_cli("Passwords.java")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Passwords.java", result.stdout)
        self.assertNotIn("private-token-body", result.stdout + result.stderr)

    def test_invalid_utf8_rejects_without_leaking_a_header_or_partial_source(self):
        (self.repository / "backend/InvoiceQueries.java").write_bytes(b"first\n\xffprivate\n")
        result = self.run_cli("backend/InvoiceQueries.java")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
