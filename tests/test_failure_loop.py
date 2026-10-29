import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/failure-loop.py'


class FailureLoopTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = Path(self.directory.name) / 'failures.json'

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), '--store', str(self.store), *arguments],
                              cwd=ROOT, capture_output=True, text=True)

    def record(self, event):
        result = self.run_cli('record', '--key', 'proxy-permission', '--event', event,
                              '--summary', 'sanitized failure', '--evidence', 'docs/github-gate.md')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_second_distinct_failure_blocks_and_recurrence_reopens(self):
        self.record('first')
        self.assertEqual(self.run_cli('check').returncode, 0)
        self.record('first')
        self.assertEqual(len(json.loads(self.store.read_text())['failures'][0]['events']), 1)
        self.record('second')
        self.assertEqual(self.run_cli('check').returncode, 1)
        resolved = self.run_cli('resolve', '--key', 'proxy-permission', '--cause', 'limited integration scope',
                                '--prevention', 'do not retry unchanged authorization',
                                '--verification', 'read success and write refusal distinguished',
                                '--guard', 'docs/github-gate.md')
        self.assertEqual(resolved.returncode, 0, resolved.stderr)
        self.assertEqual(self.run_cli('check').returncode, 0)
        self.record('third')
        self.assertEqual(self.run_cli('check').returncode, 1)

    def test_status_flag_and_missing_guard_cannot_bypass_check(self):
        self.record('first')
        self.record('second')
        data = json.loads(self.store.read_text())
        data['failures'][0]['status'] = 'resolved'
        self.store.write_text(json.dumps(data))
        self.assertEqual(self.run_cli('check').returncode, 1)
        rejected = self.run_cli('resolve', '--key', 'proxy-permission', '--cause', 'cause',
                                '--prevention', 'prevention', '--verification', 'evidence',
                                '--guard', 'missing-guard-file')
        self.assertEqual(rejected.returncode, 2)
        self.assertEqual(self.run_cli('check').returncode, 1)


if __name__ == '__main__':
    unittest.main()
