"""Mock disk measurements and exercise real verification entrypoints without builds."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("workspace_capacity", ROOT / "scripts/check-workspace-capacity.py")
CAPACITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPACITY)


class WorkspaceCapacityTest(unittest.TestCase):
    def run_measurement(self, available=None, error=None):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(CAPACITY.shutil, "disk_usage", return_value=SimpleNamespace(free=available), side_effect=error), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = CAPACITY.main(["/workspace"])
        return code, output.getvalue(), errors.getvalue()

    def test_at_or_above_fixed_minimum_passes_with_actual_and_required_values(self):
        for free in (CAPACITY.REQUIRED_BYTES, CAPACITY.REQUIRED_BYTES * 2):
            with self.subTest(free=free):
                code, output, errors = self.run_measurement(free)
                self.assertEqual(code, 0)
                self.assertIn(f"available={free} bytes", output)
                self.assertIn("required=1073741824 bytes", output)
                self.assertEqual(errors, "")

    def test_below_minimum_stops_and_limits_cleanup_to_completed_owned_dependencies(self):
        code, output, errors = self.run_measurement(CAPACITY.REQUIRED_BYTES - 1)
        self.assertEqual(code, 1)
        self.assertEqual(output, "")
        self.assertIn("available=1073741823 bytes", errors)
        self.assertIn("required=1073741824 bytes", errors)
        for instruction in ("owner-confirmed completed", "git check-ignore", "symlink", "Preserve", "evidence/logs"):
            self.assertIn(instruction, errors)

    def test_measurement_failure_cannot_pass_as_unknown_capacity(self):
        code, output, errors = self.run_measurement(error=OSError("fixture failure"))
        self.assertEqual(code, 2)
        self.assertEqual(output, "")
        self.assertIn("available=unknown", errors)
        self.assertIn("required=1073741824 bytes", errors)

    def run_entrypoint(self, script, capacity_exit, verify_exit=19):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(ROOT / "scripts" / script, scripts / script)
            (scripts / "toolchain-env.sh").write_text("")
            if script == "verify-cloud.sh":
                (scripts / "verify.sh").write_text(
                    f"#!/usr/bin/env bash\necho existing-verification-result\nexit {verify_exit}\n")
                (scripts / "verify.sh").chmod(0o755)
            fake_python = root / "python3"
            fake_python.write_text(
                "#!/usr/bin/env bash\n"
                "if [ \"$1\" != scripts/check-workspace-capacity.py ]; then exit 99; fi\n"
                f"echo capacity-preflight\nexit {capacity_exit}\n")
            fake_python.chmod(0o755)
            result = subprocess.run(["bash", str(scripts / script)], cwd="/tmp",
                                    env=os.environ | {"PATH": temporary + os.pathsep + os.environ["PATH"],
                                                      "E2E_BASE_URL": "http://fixture.invalid"},
                                    text=True, capture_output=True, check=False)
            log = root / "work/cloud-verify.log"
            return result, log.read_text() if log.exists() else None, (root / "work").exists()

    def test_cloud_preflight_failure_precedes_log_directory_and_verify(self):
        for code in (1, 2):
            with self.subTest(code=code):
                result, log, work_exists = self.run_entrypoint("verify-cloud.sh", code)
                self.assertEqual(result.returncode, code)
                self.assertIn("capacity-preflight", result.stdout)
                self.assertIsNone(log)
                self.assertFalse(work_exists)

    def test_cloud_wrapper_preserves_existing_verify_failure_and_log_after_preflight(self):
        for code in (0, 19):
            with self.subTest(code=code):
                result, log, _ = self.run_entrypoint("verify-cloud.sh", 0, verify_exit=code)
                self.assertEqual(result.returncode, code)
                self.assertEqual(log, "existing-verification-result\n")

    def test_verify_preflight_stops_before_heavy_work_and_preserves_existing_input_guard(self):
        for code in (1, 2):
            with self.subTest(code=code):
                result, _, work_exists = self.run_entrypoint("verify.sh", code)
                self.assertEqual(result.returncode, code)
                self.assertFalse(work_exists)
        result, _, _ = self.run_entrypoint("verify.sh", 0)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Unset E2E_BASE_URL", result.stderr)
