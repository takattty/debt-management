"""Run the real verify script with small processes instead of Java/npm/Docker."""

import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAKE_COMMAND = r'''
import hashlib, json, os, pathlib, signal, sys, time
root = pathlib.Path.cwd()
events_file = root / "events.jsonl"
command, args = sys.argv[1], sys.argv[2:]
mode = os.environ["FIXTURE_MODE"]
def events():
    return [json.loads(line) for line in events_file.read_text().splitlines()] if events_file.exists() else []
def record(name, **details):
    with events_file.open("a") as target:
        target.write(json.dumps({"event": name, **details}) + "\n")
def starts():
    return [event for event in events() if event["event"] == "start"]
def latest():
    return starts()[-1]["pid"]
def replace_artifact():
    (root / "backend/build/libs/debt-management.jar").write_text("different artifact")
if command == "java":
    index = len(starts()) + 1
    record("start", pid=os.getpid(), index=index, db=os.environ["DB_URL"],
           artifact=hashlib.sha256(pathlib.Path(args[1]).read_bytes()).hexdigest())
    if mode == "restart-fails" and index == 2:
        sys.exit(17)
    def stop(signum, frame):
        record("stop", pid=os.getpid())
        sys.exit(9 if mode == "bad-stop" and index == 1 else 0)
    signal.signal(signal.SIGTERM, stop)
    if mode != "never-ready":
        print("Started DebtApplication", flush=True)
    while True:
        time.sleep(0.01)
elif command == "python3":
    if args[0] == "scripts/assert-ports-free.py":
        if mode == "port-residual" and any(event["event"] == "stop" for event in events()):
            sys.exit(1)
    if args[0] == "scripts/check-workspace-capacity.py":
        if mode == "restart-capacity" and any(event["event"] == "stop" for event in events()):
            sys.exit(1)
elif command == "runn":
    record("api", books=args)
    if mode == "dead-api":
        os.kill(latest(), signal.SIGKILL)
        time.sleep(0.05)
elif command == "proxy":
    record("proxy")
    if mode == "swap-api":
        replace_artifact()
elif command == "npm" and args[-2:] == ["run", "test:e2e"]:
    record("e2e")
    if mode == "e2e-fails":
        sys.exit(23)
    if mode == "swap-e2e":
        replace_artifact()
    if mode == "dead-e2e":
        os.kill(latest(), signal.SIGKILL)
        time.sleep(0.05)
elif command == "curl":
    os.kill(latest(), 0)
elif command == "sleep":
    time.sleep(0.01)
'''


class VerifyPhasesTest(unittest.TestCase):
    def run_verify(self, mode="normal"):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(ROOT / "scripts/verify.sh", scripts / "verify.sh")
            (scripts / "toolchain-env.sh").write_text("")
            (root / "backend/build/libs").mkdir(parents=True)
            (root / "backend/build/libs/debt-management.jar").write_text("same artifact")
            (root / "backend/runn").mkdir()
            (root / "backend/runn/authentication.yml").write_text("fixture")
            shutil.copyfile(ROOT / "backend/runn/zz-existing-invoices.yml",
                            root / "backend/runn/zz-existing-invoices.yml")
            (root / "backend/gradlew").write_text("#!/bin/sh\nexit 0\n")
            (root / "backend/gradlew").chmod(0o755)
            fake = root / "fake.py"
            fake.write_text(FAKE_COMMAND)
            executables = root / "bin"
            executables.mkdir()
            for command in ("java", "python3", "npm", "node", "curl", "sleep", "runn", "proxy"):
                path = executables / command
                path.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "{command}" "$@"\n')
                path.chmod(0o755)
            shutil.copyfile(executables / "runn", scripts / "runn.sh")
            (scripts / "runn.sh").chmod(0o755)
            (scripts / "verify-proxy.sh").write_text(f'#!/bin/sh\nexec "{executables / "proxy"}"\n')
            environment = os.environ | {
                "PATH": str(executables) + os.pathsep + os.environ["PATH"],
                "FIXTURE_MODE": mode, "DB_URL": "jdbc:fictional:persistent-db",
                "E2E_BASE_URL": "", "API_PROXY_TARGET": "",
            }
            try:
                result = subprocess.run(["bash", str(scripts / "verify.sh")], cwd="/tmp", env=environment,
                                        text=True, capture_output=True, check=False, timeout=15)
                events = [json.loads(line) for line in (root / "events.jsonl").read_text().splitlines()]
                for event in events:
                    if event["event"] == "start":
                        with self.assertRaises(ProcessLookupError, msg=f"backend {event['pid']} leaked"):
                            os.kill(event["pid"], 0)
                return result, events
            finally:
                if (root / "events.jsonl").exists():
                    for line in (root / "events.jsonl").read_text().splitlines():
                        event = json.loads(line)
                        if event["event"] == "start":
                            try:
                                os.kill(event["pid"], signal.SIGKILL)
                            except ProcessLookupError:
                                pass

    def test_two_backend_lifecycles_keep_artifact_and_database_and_reap_both_pids(self):
        result, events = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([event["event"] for event in events], ["start", "api", "proxy", "stop", "start", "e2e", "stop"])
        starts = [event for event in events if event["event"] == "start"]
        self.assertNotEqual(starts[0]["pid"], starts[1]["pid"])
        self.assertEqual(starts[0]["artifact"], starts[1]["artifact"])
        self.assertEqual(starts[0]["db"], starts[1]["db"])
        api = next(event for event in events if event["event"] == "api")
        self.assertIn("backend/runn/zz-existing-invoices.yml", api["books"])

    def test_restart_failure_never_runs_e2e_and_cleans_both_pids(self):
        result, events = self.run_verify("restart-fails")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("e2e", [event["event"] for event in events])
        self.assertEqual(len([event for event in events if event["event"] == "start"]), 2)

    def test_e2e_failure_exit_is_preserved_after_backend_cleanup(self):
        result, _ = self.run_verify("e2e-fails")
        self.assertEqual(result.returncode, 23)

    def test_dead_backend_cannot_be_reported_as_a_successful_phase(self):
        for mode in ("dead-api", "dead-e2e"):
            with self.subTest(mode=mode):
                result, _ = self.run_verify(mode)
                self.assertNotEqual(result.returncode, 0)

    def test_artifact_change_in_either_phase_fails(self):
        for mode in ("swap-api", "swap-e2e"):
            with self.subTest(mode=mode):
                result, _ = self.run_verify(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Backend artifact changed", result.stderr)

    def test_port_residue_capacity_or_bad_shutdown_prevents_the_next_phase(self):
        for mode in ("port-residual", "restart-capacity", "bad-stop"):
            with self.subTest(mode=mode):
                result, events = self.run_verify(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("e2e", [event["event"] for event in events])
                self.assertEqual(len([event for event in events if event["event"] == "start"]), 1)

    def test_ready_timeout_is_failure_and_cleans_its_process(self):
        result, events = self.run_verify("never-ready")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("e2e", [event["event"] for event in events])
