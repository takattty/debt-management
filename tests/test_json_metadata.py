import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/json-metadata.py"
MODULE = runpy.run_path(str(SCRIPT))


class JsonMetadataTest(unittest.TestCase):
    def run_cli(self, raw, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], input=raw,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_nested_large_content_is_found_without_printing_either_string(self):
        secret = "nested-private-log-marker" * 10000
        value = {"content": [{"type": "text", "text": "Action completed."}],
                 "structuredContent": {"content": secret}}
        result = self.run_cli(json.dumps(value).encode())
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b"")
        self.assertLessEqual(len(result.stdout), 4096)
        self.assertNotIn(b"nested-private-log-marker", result.stdout)
        self.assertNotIn(b"Action completed.", result.stdout)
        document = json.loads(result.stdout)
        fields = document["summary"]["fields"]
        nested = next(field for field in fields if field["name"] == "structuredContent")
        content = nested["summary"]["fields"][0]["summary"]
        self.assertEqual(content, {"type": "string", "utf8Bytes": len(secret.encode())})

    def test_unicode_strings_count_utf8_bytes_and_never_emit_values(self):
        secret = "日本語🙂"
        result = self.run_cli(json.dumps(secret, ensure_ascii=False).encode())
        self.assertEqual(json.loads(result.stdout)["summary"],
                         {"type": "string", "utf8Bytes": len(secret.encode())})
        self.assertNotIn(secret.encode(), result.stdout)

    def test_wide_envelope_reduces_budget_to_complete_json_under_4k(self):
        value = {f"field-{i:03d}-" + "x" * 50: "opaque" * 1000 for i in range(150)}
        result = self.run_cli(json.dumps(value).encode(), "--max-nodes", "128")
        self.assertEqual(result.returncode, 0)
        self.assertLessEqual(len(result.stdout), 4096)
        metadata = json.loads(result.stdout)
        self.assertTrue(metadata["reducedForOutput"])
        self.assertLessEqual(metadata["visitedNodes"], metadata["limits"]["appliedMaxNodes"])
        self.assertEqual(metadata["summary"]["fieldCount"], 150)
        self.assertNotIn(b"opaque", result.stdout)

    def test_depth_and_node_limits_report_omissions(self):
        value = {"a": {"b": {"c": "hidden"}}, "list": [1, 2, 3]}
        result = self.run_cli(json.dumps(value).encode(), "--max-depth", "1")
        summary = json.loads(result.stdout)["summary"]
        self.assertTrue(all(field["summary"]["truncated"] == "depth" for field in summary["fields"]))
        result = self.run_cli(json.dumps(value).encode(), "--max-nodes", "1")
        metadata = json.loads(result.stdout)
        self.assertEqual(metadata["visitedNodes"], 1)
        self.assertEqual(metadata["summary"]["omittedFields"], 2)

    def test_oversized_field_names_are_byte_counts_without_name_prefix(self):
        name = "private-long-name" * 1000
        result = self.run_cli(json.dumps({name: "value"}).encode())
        self.assertNotIn(b"private-long-name", result.stdout)
        field = json.loads(result.stdout)["summary"]["fields"][0]
        self.assertEqual(field["nameUtf8Bytes"], len(name.encode()))
        self.assertNotIn("name", field)

    def test_arrays_and_primitives_do_not_echo_numbers_or_booleans(self):
        result = self.run_cli(b'[1234567890123456789,true,null,"private"]')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout)["summary"]["itemCount"], 4)
        self.assertNotIn(b"1234567890123456789", result.stdout)
        self.assertNotIn(b"private", result.stdout)

    def test_file_original_is_unchanged(self):
        raw = json.dumps({"structuredContent": {"content": "private" * 40000}}).encode()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "envelope.json"
            path.write_bytes(raw)
            before = hashlib.sha256(raw).hexdigest()
            result = self.run_cli(b"", str(path))
            self.assertEqual(result.returncode, 0)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)

    def test_invalid_json_unicode_and_constants_fail_closed(self):
        for raw in [b'{"private":"unterminated', b'"\xffprivate"', b'"\\ud800"',
                    b'{"value":NaN}']:
            with self.subTest(kind=len(raw)):
                result = self.run_cli(raw)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, b"")
                self.assertNotIn(b"private", result.stderr)
                self.assertLess(len(result.stderr), 4096)

    def test_deep_valid_input_stops_traversal_at_depth_limit(self):
        result = self.run_cli(b'[' * 100 + b'"private"' + b']' * 100)
        self.assertEqual(result.returncode, 0)
        metadata = json.loads(result.stdout)
        self.assertEqual(metadata["visitedNodes"], 5)
        summary = metadata["summary"]
        for _ in range(4):
            summary = summary["items"][0]["summary"]
        self.assertEqual(summary["truncated"], "depth")
        self.assertNotIn(b"private", result.stdout)

    def test_enormous_numeric_literals_are_only_types(self):
        result = self.run_cli(b'[' + b'9' * 100000 + b',1e999999]')
        self.assertEqual(result.returncode, 0)
        summaries = json.loads(result.stdout)["summary"]["items"]
        self.assertTrue(all(item["summary"] == {"type": "number"} for item in summaries))
        self.assertLessEqual(len(result.stdout), 4096)

    def test_invalid_limits_and_long_arguments_do_not_escape_to_output(self):
        for args in [("--max-depth", "9"), ("--max-nodes", "0"),
                     ("--max-nodes", "129"), ("--unknown-" + "private" * 2000,)]:
            result = self.run_cli(b'{}', *args)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, b"")
            self.assertNotIn(b"private", result.stderr)

    def test_input_size_limit_is_fail_closed(self):
        result = self.run_cli(b'"' + b'x' * MODULE["MAX_INPUT_BYTES"] + b'"')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")


if __name__ == "__main__":
    unittest.main()
