"""Run the real shell entrypoint with isolated native, metadata and Docker commands."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class StagedVerifyTest(unittest.TestCase):
    def entry(self, options=('--cloud-local', '--staged'), **settings):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts'
            scripts.mkdir()
            for name in ['docker-verify.sh', 'docker-cloud.sh']:
                shutil.copy(ROOT / 'scripts' / name, scripts / name)
            (scripts / 'build-local.sh').write_text(
                '#!/bin/sh\necho \'{"kind":"native"}\' >>"$TRACE"\nexit "${FAIL_NATIVE:-0}"\n')
            (scripts / 'build-local.sh').chmod(0o755)
            (scripts / 'check-docker-capacity.py').write_text('''import json,os,sys
from pathlib import Path
target=sys.argv[sys.argv.index('--service')+1]; phase=sys.argv[-1]
started=(Path(os.environ['STATE'])/target).exists()
with open(os.environ['TRACE'],'a') as f:
 f.write(json.dumps({'kind':'guard','phase':phase,'service':target,'started':started})+'\\n')
if os.environ.get('FAIL_GUARD')==phase+':'+target: sys.exit(7)
if started and os.environ.get('CORRUPT_READY')==target:
 print('{}');sys.exit(0)
print(json.dumps({'sufficient':True,'services':[{'service':target,'current_container':started,
 'healthy':started,'running':started}]}))
''')
            docker = '''import json,os,sys
from pathlib import Path
args=sys.argv[1:]
assert args.pop(0)=='--host=unix:///var/run/docker.sock'
with open(os.environ['TRACE'],'a') as f:
 f.write(json.dumps({'kind':'docker','args':args,'context':os.environ.get('DOCKER_CONTEXT')})+'\\n')
assert args[:2] in [['compose','build'],['compose','up']]
if os.environ.get('FAIL_DOCKER')==args[1]+':'+args[-1]: sys.exit(8)
if args[1]=='up':
 assert args[2:5]==['--no-build','--no-deps','-d']
 (Path(os.environ['STATE'])/args[-1]).touch()
'''
            (root / 'docker').write_text('#!' + sys.executable + '\n' + docker)
            shell = {
                'timeout': '#!/bin/sh\necho "{\\"kind\\":\\"timeout\\",\\"seconds\\":$1}" >>"$TRACE"\n'
                           '[ "${FAIL_READY_TIMEOUT:-0}" = 0 ] || exit 124\nshift\nexec "$@"\n',
                'curl': '#!/bin/sh\necho "{\\"kind\\":\\"curl\\",\\"url\\":\\"${!#}\\"}" >>"$TRACE"\n'
                        'exit "${FAIL_CURL:-0}"\n',
                'npm': '#!/bin/sh\necho \'{"kind":"e2e"}\' >>"$TRACE"\n',
                'sleep': '#!/bin/sh\nexit 9\n',
            }
            # bash supplies ${!#} for curl's final URL; no real sockets or sleeps.
            shell['curl'] = shell['curl'].replace('#!/bin/sh', '#!/bin/bash')
            for name, body in shell.items():
                (root / name).write_text(body)
            for name in ['docker', *shell]:
                (root / name).chmod(0o755)
            (root / 'state').mkdir()
            env = os.environ | {'PATH': str(root) + os.pathsep + os.environ['PATH'],
                                'TRACE': str(root / 'trace'), 'STATE': str(root / 'state'),
                                'DOCKER_CONTEXT': 'preserve-desktop', 'DOCKER_HOST': 'tcp://preserve.invalid',
                                **settings}
            result = subprocess.run(['bash', str(scripts / 'docker-verify.sh'), *options],
                                    cwd='/tmp', env=env, capture_output=True, text=True, timeout=10)
            events = [json.loads(line) for line in (root / 'trace').read_text().splitlines()] \
                if (root / 'trace').exists() else []
            return result.returncode, events, result.stderr + result.stdout

    def mutations(self, events):
        return [x['args'] for x in events if x['kind'] == 'docker']

    def test_exact_phase_targets_are_guarded_and_native_finishes_before_docker(self):
        status, events, error = self.entry()
        self.assertEqual(status, 0, error)
        expected = [['compose', 'build', 'backend'], ['compose', 'build', 'frontend'],
                    *[['compose', 'up', '--no-build', '--no-deps', '-d', name]
                      for name in ['postgres', 'backend', 'frontend']]]
        self.assertEqual(self.mutations(events), expected)
        self.assertEqual(events[0]['kind'], 'native')
        for index, event in enumerate(events):
            if event['kind'] == 'docker':
                guard = events[index - 1]
                self.assertEqual((guard['kind'], guard['phase'], guard['service']),
                                 ('guard', event['args'][1], event['args'][-1]))
                self.assertIsNone(event['context'])
        guards = [(x['service'], x['started']) for x in events if x['kind'] == 'guard']
        self.assertIn(('postgres', True), guards)
        self.assertIn(('backend', True), guards)
        timeouts = [x['seconds'] for x in events if x['kind'] == 'timeout']
        self.assertTrue(timeouts)
        self.assertTrue(all(0 < seconds <= 120 for seconds in timeouts))
        self.assertEqual([x['url'] for x in events if x['kind'] == 'curl'],
                         ['http://127.0.0.1:8088/', 'http://127.0.0.1:8088/api/csrf'])
        self.assertEqual(events[-1]['kind'], 'e2e')

    def test_every_phase_refusal_stops_before_its_mutation_and_all_later_commands(self):
        phases = ['build:backend', 'build:frontend', 'up:postgres', 'up:backend', 'up:frontend']
        for index, phase in enumerate(phases):
            with self.subTest(phase=phase):
                status, events, _ = self.entry(FAIL_GUARD=phase)
                self.assertEqual(status, 7)
                self.assertEqual(len(self.mutations(events)), index)
                self.assertNotIn('e2e', [x['kind'] for x in events])

    def test_prepare_only_has_identical_setup_and_public_readiness_without_claiming_acceptance(self):
        status, complete, error = self.entry()
        self.assertEqual(status, 0, error)
        status, prepared, error = self.entry(options=('--cloud-local', '--staged', '--prepare-only'))
        self.assertEqual(status, 0, error)
        self.assertIn('not a quality PASS', error)
        self.assertEqual(self.mutations(prepared), self.mutations(complete))
        self.assertEqual([x for x in prepared if x['kind'] != 'timeout'],
                         [x for x in complete if x['kind'] not in ['timeout', 'e2e']])
        self.assertNotIn('e2e', [x['kind'] for x in prepared])

    def test_native_or_build_failure_never_runs_later_phases(self):
        status, events, _ = self.entry(FAIL_NATIVE='6')
        self.assertEqual(status, 6)
        self.assertEqual([x['kind'] for x in events], ['native'])
        status, events, _ = self.entry(FAIL_DOCKER='build:frontend')
        self.assertEqual(status, 8)
        self.assertEqual(len(self.mutations(events)), 2)
        self.assertFalse(any(x['args'][1] == 'up' for x in events if x['kind'] == 'docker'))

    def test_readiness_timeout_or_invalid_metadata_blocks_backend_creation(self):
        for settings, expected in [({'FAIL_READY_TIMEOUT': '1'}, 124), ({'CORRUPT_READY': 'postgres'}, 2)]:
            with self.subTest(settings=settings):
                status, events, _ = self.entry(**settings)
                self.assertEqual(status, expected)
                self.assertEqual(len(self.mutations(events)), 3)
                self.assertNotIn('e2e', [x['kind'] for x in events])

    def test_failed_public_readiness_does_not_run_browser_acceptance(self):
        status, events, _ = self.entry(FAIL_CURL='1')
        self.assertEqual(status, 9)
        self.assertEqual(len(self.mutations(events)), 5)
        self.assertNotIn('e2e', [x['kind'] for x in events])

    def test_staged_requires_explicit_cloud_local_before_any_work(self):
        for options in [('--staged',), ('--staged', '--cloud-local'), ('--cloud-local', '--staged', 'backend'),
                        ('--prepare-only',), ('--cloud-local', '--prepare-only')]:
            with self.subTest(options=options):
                status, events, _ = self.entry(options=options)
                self.assertEqual(status, 2)
                self.assertEqual(events, [])


if __name__ == '__main__':
    unittest.main()
