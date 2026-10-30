"""Exercise evidence lookup from outside a checkout and reject ambiguous owners."""

import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


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


class EvidenceBoundsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.location = Path(self.temporary.name)
        self.owners = [self.location / "first", self.location / "second"]
        for owner in self.owners:
            (owner / "work").mkdir(parents=True)
        self.proof = self.owners[0] / "work/proof.log"
        self.proof.write_bytes(b"verified\n")
        fake = self.location / "git"
        fake.write_text(
            "#!/usr/bin/env python3\nimport json, os, sys\n"
            "sys.stdout.write(''.join('worktree '+p+'\\0\\0' for p in json.loads(os.environ['TEST_WORKTREES'])))\n"
        )
        fake.chmod(0o755)
        self.environment = os.environ | {"PATH": str(self.location) + os.pathsep + os.environ["PATH"],
                                        "TEST_WORKTREES": json.dumps(list(map(str, self.owners)))}

    def read(self, *arguments, artifact="work/proof.log"):
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts/read-evidence.py"), artifact, *map(str, arguments)],
            cwd=self.location, env=self.environment, capture_output=True, check=False,
        )

    def rejected(self, result):
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(result.stdout, b"")

    def test_single_oversize_line_refuses_without_disclosing_any_payload(self):
        data = b"PRIVATE-ARTIFACT-" * 10000
        self.proof.write_bytes(data)
        result = self.read()
        self.rejected(result)
        self.assertIn(b"--json-chunk", result.stderr)
        self.assertNotIn(b"PRIVATE-ARTIFACT", result.stderr)
        self.assertEqual(self.proof.read_bytes(), data)

    def test_text_limit_includes_header_and_utf8_bytes_at_exact_boundary(self):
        budget = 16384 - len(f"Evidence: {self.proof}\n".encode())
        payload = "あ" * (budget // 3) + "x" * (budget % 3)
        self.proof.write_text(payload, encoding="utf-8")
        result = self.read()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(result.stdout), 16384)
        self.assertTrue(result.stdout.endswith(payload.encode()))
        self.proof.write_text(payload + "x", encoding="utf-8")
        self.rejected(self.read())

    def test_japanese_character_count_cannot_bypass_byte_cap(self):
        self.proof.write_text("日" * 6000, encoding="utf-8")
        self.rejected(self.read())

    def test_invalid_utf8_replacement_expansion_is_also_bounded(self):
        self.proof.write_bytes(b"\xff" * 6000)
        self.rejected(self.read())

    def test_small_tail_after_a_huge_old_line_preserves_crlf_behavior(self):
        self.proof.write_bytes(b"old" * 100000 + b"\r\nlast1\r\nlast2\r\n")
        result = self.read("--lines", 2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(result.stdout.endswith(b"last1\nlast2\n"))
        self.assertLess(len(result.stdout), 16384)

    def test_empty_text_and_line_boundaries(self):
        self.proof.write_bytes(b"")
        self.assertEqual(self.read().stdout, f"Evidence: {self.proof}\n".encode())
        self.proof.write_bytes(b"row\n" * 201)
        for count in (1, 200):
            result = self.read("--lines", count)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.count(b"row\n"), count)
        for count in (0, 201):
            self.rejected(self.read("--lines", count))

    def test_all_binary_and_unicode_chunks_reassemble_with_whole_sha(self):
        data = bytes(range(256)) * 20 + "日本語\n".encode() * 1000
        self.proof.write_bytes(data)
        restored = bytearray()
        while len(restored) < len(data):
            result = self.read("--json-chunk", "--offset", len(restored), "--size", 4096)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertLessEqual(len(result.stdout), 16384)
            item = json.loads(result.stdout)
            self.assertEqual(item["offset"], len(restored))
            self.assertEqual(item["total"], len(data))
            self.assertEqual(item["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(item["encoding"], "base64")
            chunk = base64.b64decode(item["dataBase64"], validate=True)
            self.assertEqual(item["size"], len(chunk))
            self.assertLessEqual(len(chunk), 4096)
            restored.extend(chunk)
        self.assertEqual(bytes(restored), data)
        self.assertEqual(hashlib.sha256(restored).hexdigest(), item["sha256"])

    def test_empty_chunk_eof_and_byte_offset_inside_multibyte_text(self):
        self.proof.write_bytes(b"")
        item = json.loads(self.read("--json-chunk").stdout)
        self.assertEqual((item["offset"], item["size"], item["total"], item["dataBase64"]), (0, 0, 0, ""))
        self.assertEqual(item["sha256"], hashlib.sha256(b"").hexdigest())
        self.proof.write_text("日", encoding="utf-8")
        item = json.loads(self.read("--json-chunk", "--offset", 1, "--size", 1).stdout)
        self.assertEqual(base64.b64decode(item["dataBase64"]), "日".encode()[1:2])
        item = json.loads(self.read("--json-chunk", "--offset", 3).stdout)
        self.assertEqual((item["size"], item["total"]), (0, 3))

    def test_invalid_chunk_ranges_and_mixed_modes_reject_with_empty_stdout(self):
        for args in (("--json-chunk", "--offset", -1), ("--json-chunk", "--offset", 10),
                     ("--json-chunk", "--size", 0), ("--json-chunk", "--size", -1),
                     ("--json-chunk", "--size", 4097), ("--json-chunk", "--offset", "bad"),
                     ("--json-chunk", "--lines", 20), ("--offset", 0), ("--size", 1)):
            with self.subTest(args=args):
                self.rejected(self.read(*args))

    def test_chunk_owner_selection_and_ambiguity_have_no_payload_leak(self):
        (self.owners[1] / "work/proof.log").write_bytes(b"second")
        self.rejected(self.read("--json-chunk"))
        result = self.read("--json-chunk", "--worktree", self.owners[1])
        self.assertEqual(base64.b64decode(json.loads(result.stdout)["dataBase64"]), b"second")
        self.rejected(self.read("--json-chunk", "--worktree", self.location))

    def test_both_modes_reject_path_and_work_directory_symlink_escape(self):
        outside = self.location / "outside"
        outside.mkdir()
        (outside / "proof.log").write_bytes(b"outside-private")
        self.proof.unlink()
        self.proof.symlink_to(outside / "proof.log")
        for mode in ((), ("--json-chunk",)):
            self.rejected(self.read(*mode))
            self.rejected(self.read(*mode, artifact="work/../outside/proof.log"))
            self.rejected(self.read(*mode, artifact=str(outside / "proof.log")))
        self.proof.unlink()
        self.proof.parent.rmdir()
        self.proof.parent.symlink_to(outside, target_is_directory=True)
        for mode in ((), ("--json-chunk",)):
            self.rejected(self.read(*mode))

    def test_chunk_rejects_a_file_changed_during_hashing(self):
        spec = importlib.util.spec_from_file_location("evidence_reader", ROOT / "scripts/read-evidence.py")
        reader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reader)
        original = self.proof.stat()
        class Changed:
            st_dev, st_ino, st_size = original.st_dev, original.st_ino, original.st_size
            st_mtime_ns, st_ctime_ns = original.st_mtime_ns + 1, original.st_ctime_ns
        with patch.object(reader.os, "fstat", side_effect=[original, Changed()]):
            with self.assertRaisesRegex(ValueError, "changed while reading"):
                reader.read_chunk(self.proof, 0, 4096)
