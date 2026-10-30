"""Exercise real read-only CLI wiring with fake metadata and filesystem capacity."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('docker_capacity', ROOT / 'scripts/check-docker-capacity.py')
CAPACITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPACITY)


class DockerCapacityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(ROOT / 'scripts/docker-cloud.sh', self.root / 'scripts/docker-cloud.sh')
        (self.root / 'marker').touch()
        (self.root / 'docker-root').mkdir()
        self.trace = self.root / 'trace'
        for service in ['backend', 'frontend']:
            context = self.root / service
            context.mkdir()
            (context / 'payload').write_bytes(b'X' * 100)
            (context / 'Dockerfile').write_text('FROM base\nWORKDIR /app\nCOPY payload /app/payload\nUSER 10001\n')
        services = {name: {'image': name} for name in ['backend', 'frontend', 'postgres']}
        self.fixture = {'info': {'Driver': 'vfs', 'DockerRootDir': str(self.root / 'docker-root')},
                        'model': {'name': 'fixture', 'services': services}, 'containers': [],
                        'images': {name: {'Id': 'sha256:' + name, 'Size': size} for name, size in
                                   [('postgres', 456000000), ('backend', 350000000), ('frontend', 48500000), ('base', 200000000)]}}
        self.fake = self.root / 'docker'
        self.fake.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['TRACE'],'a') as f: f.write(json.dumps({'args':args,'selectors':[k for k in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'] if k in os.environ], 'config':os.environ.get('DOCKER_CONFIG')})+'\\n')
assert args.pop(0)=='--host=unix:///var/run/docker.sock'
d=json.loads(Path(os.environ['FIXTURE']).read_text())
if args[0]=='info': print(json.dumps(d['info']))
elif args[:3]==['compose','config','--format']: print(json.dumps(d['model']))
elif args[:3]==['compose','config','--hash']: print('\\n'.join(k+' '+'a'*64 for k in d['model']['services']))
elif args[:3]==['compose','config','--images']: print('\\n'.join(v.get('image') or 'fixture-'+k for k,v in d['model']['services'].items()))
elif args[:2]==['compose','ps']: print('\\n'.join(x['Id'] for x in d['containers']))
elif args[:2]==['container','inspect']: print(json.dumps(d['containers']))
elif args[:2]==['image','inspect']:
 if args[2] not in d['images']: sys.exit(1)
 print(json.dumps([d['images'][args[2]]]))
else: raise AssertionError('No mutations permitted: '+repr(args))
''')
        self.fake.chmod(0o755)

    def run_guard(self, free, phase='up', error=None, worktree=None, block_size=4096, service=None, extra_args=()):
        fixture = self.root / 'fixture.json'
        fixture.write_text(json.dumps(self.fixture))
        env = {'PATH': str(self.root) + os.pathsep + os.environ['PATH'], 'FIXTURE': str(fixture), 'TRACE': str(self.trace),
               'DOCKER_HOST': 'tcp://wrong.invalid', 'DOCKER_CONTEXT': 'wrong', 'DOCKER_TLS': '1',
               'DOCKER_TLS_VERIFY': '1', 'DOCKER_CERT_PATH': '/wrong', 'DOCKER_CONFIG': '/preserved-registry-selector'}
        out, err = io.StringIO(), io.StringIO()
        with patch.object(CAPACITY, 'ROOT', self.root), patch.object(CAPACITY, 'OWNER', self.root), patch.object(CAPACITY, 'MARKER', self.root / 'marker'), \
                patch.object(CAPACITY.shutil, 'disk_usage', return_value=SimpleNamespace(free=free), side_effect=error), \
                patch.object(CAPACITY.os, 'statvfs', return_value=SimpleNamespace(f_frsize=block_size)), \
                patch.dict(os.environ, env), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = CAPACITY.main(['--cloud-local', *(['--worktree', str(worktree)] if worktree else []),
                                  *(['--service', service] if service is not None else []), *extra_args, phase])
        return code, json.loads(out.getvalue()) if out.getvalue() else None, err.getvalue()

    def current(self, healthy=False, hash_value='a' * 64, image='sha256:postgres'):
        return {'Id': 'f' * 64, 'Image': image, 'Config': {'Labels': {'com.docker.compose.service': 'postgres',
                'com.docker.compose.project': 'fixture', 'com.docker.compose.config-hash': hash_value}},
                'State': {'Running': healthy, 'Health': {'Status': 'healthy' if healthy else 'unhealthy'}}}

    def test_zero_space_refuses_without_mutations_and_preserves_registry_selector(self):
        code, report, _ = self.run_guard(0)
        self.assertEqual(code, 1)
        self.assertEqual(report['vfs_clone_bytes'], 1709000000)
        self.assertEqual(report['postgres_start_bytes'], 456000000)
        self.assertEqual(report['vfs_allocation_bytes'], 170901504)
        self.assertEqual(report['required_bytes'], CAPACITY.GIB + 1709000000 + 170901504 + 456000000)
        for event in map(json.loads, self.trace.read_text().splitlines()):
            self.assertEqual(event['selectors'], [])
            self.assertEqual(event['config'], '/preserved-registry-selector')
            self.assertNotIn('up', event['args'])
            self.assertNotIn('build', event['args'])

    def test_explicit_compose_owner_does_not_assume_it_contains_the_new_helper(self):
        alternate = self.root / 'old-checkout'; alternate.mkdir()
        self.assertFalse((alternate / 'scripts/docker-cloud.sh').exists())
        code, report, _ = self.run_guard(20 * CAPACITY.GIB, worktree=alternate)
        self.assertEqual(code, 0)
        self.assertEqual(report['worktree'], str(alternate))

    def test_same_free_space_rejects_vfs_but_accepts_cow_instead_of_a_fixed_threshold(self):
        self.assertEqual(self.run_guard(2 * CAPACITY.GIB)[0], 1)
        self.fixture['info']['Driver'] = 'overlay2'
        code, report, _ = self.run_guard(2 * CAPACITY.GIB)
        self.assertEqual(code, 0)
        self.assertEqual(report['vfs_clone_bytes'], 0)
        self.assertEqual(report['vfs_allocation_bytes'], 0)
        self.assertEqual(report['postgres_start_bytes'], 456000000)

    def test_root_observed_fresh_create_cannot_pass_the_old_one_copy_boundary(self):
        # Root Engine28.4.0/vfs observation: these pinned images consumed 1,786,224,640B,
        # including a later 9.38MB snapshot. Old required space would leave only 597MB.
        for name, size in [('postgres', 455836883), ('backend', 349570196), ('frontend', 48529029)]:
            self.fixture['images'][name]['Size'] = size
        old_boundary = 2383514815
        observed_drop = 1786224640
        self.assertLess(old_boundary - observed_drop, CAPACITY.GIB)
        code, report, _ = self.run_guard(old_boundary)
        self.assertEqual(code, 1, 'the old one-copy boundary must refuse before create')
        self.assertEqual(report['vfs_clone_bytes'], 2 * 853936108)
        self.assertGreater(report['vfs_allocation_bytes'], 0)
        self.assertGreaterEqual(report['required_bytes'] - observed_drop, CAPACITY.GIB)

    def test_unknown_driver_image_size_or_filesystem_fail_closed(self):
        for mutate in [lambda: self.fixture['info'].update(Driver='unknown'),
                       lambda: self.fixture['images']['backend'].update(Size=None),
                       lambda: self.fixture['info'].update(DockerRootDir='/nonexistent-fixture-root')]:
            with self.subTest(mutate=mutate):
                saved = json.loads(json.dumps(self.fixture)); mutate()
                self.assertEqual(self.run_guard(20 * CAPACITY.GIB)[0], 2)
                self.fixture = saved
        self.assertEqual(self.run_guard(None, error=OSError('unavailable'))[0], 2)
        self.assertEqual(self.run_guard(20 * CAPACITY.GIB, block_size=0)[0], 2)

    def test_current_container_avoids_clone_but_unhealthy_postgres_still_needs_start_space(self):
        self.fixture['containers'] = [self.current()]
        _, report, _ = self.run_guard(20 * CAPACITY.GIB)
        self.assertEqual(report['vfs_clone_bytes'], 797000000)
        self.assertEqual(report['postgres_start_bytes'], 456000000)
        self.fixture['containers'] = [self.current(healthy=True)]
        self.assertEqual(self.run_guard(20 * CAPACITY.GIB)[1]['postgres_start_bytes'], 0)

    def test_changed_config_or_image_requires_recreation_clone(self):
        for container in [self.current(hash_value='b' * 64), self.current(image='sha256:old')]:
            self.fixture['containers'] = [container]
            self.assertEqual(self.run_guard(20 * CAPACITY.GIB)[1]['vfs_clone_bytes'], 1709000000)

    def test_all_current_healthy_containers_need_no_new_vfs_copies_or_allocation(self):
        containers = []
        for index, name in enumerate(['postgres', 'backend', 'frontend'], 1):
            current = self.current(healthy=True, image='sha256:' + name)
            current['Id'] = str(index) * 64
            current['Config']['Labels']['com.docker.compose.service'] = name
            containers.append(current)
        self.fixture['containers'] = containers
        code, report, _ = self.run_guard(CAPACITY.GIB)
        self.assertEqual(code, 0)
        self.assertEqual(report['vfs_clone_bytes'], 0)
        self.assertEqual(report['vfs_allocation_bytes'], 0)
        self.assertEqual(report['required_bytes'], CAPACITY.GIB)

    def test_allocation_allowance_scales_with_image_bytes_and_filesystem_blocks_without_pg_start(self):
        self.fixture['containers'] = [self.current(healthy=True)]
        _, before, _ = self.run_guard(20 * CAPACITY.GIB)
        self.assertEqual(before['postgres_start_bytes'], 0)
        self.assertGreater(before['vfs_allocation_bytes'], 0)
        self.fixture['images']['backend']['Size'] += 100000000
        _, after, _ = self.run_guard(20 * CAPACITY.GIB, block_size=65536)
        self.assertGreater(after['vfs_allocation_bytes'], before['vfs_allocation_bytes'])
        self.assertEqual(after['vfs_allocation_bytes'] % 65536, 0)
        self.assertEqual(after['filesystem_block_bytes'], 65536)

    def test_build_accounts_for_real_base_payload_layers_and_future_container(self):
        self.fixture['model']['services']['backend'] = {'build': {'context': str(self.root / 'backend')}}
        self.fixture['model']['services']['frontend'] = {'build': {'context': str(self.root / 'frontend')}}
        _, report, _ = self.run_guard(20 * CAPACITY.GIB, 'build')
        self.assertGreater(report['build_bytes'], 1200000000)
        projected = sum(service.get('projected_virtual_bytes', 0) for service in report['services'])
        self.assertEqual(report['vfs_clone_bytes'], 2 * (456000000 + projected))
        self.assertGreater(report['vfs_allocation_bytes'], 0)
        before = report['required_bytes']
        (self.root / 'backend/payload').write_bytes(b'X' * 1000000)
        self.assertGreater(self.run_guard(20 * CAPACITY.GIB, 'build')[1]['required_bytes'], before)
        (self.root / 'backend/Dockerfile').write_text('FROM missing\nCOPY payload /app\n')
        self.assertEqual(self.run_guard(20 * CAPACITY.GIB, 'build')[0], 2)

    def test_selected_service_preserves_two_copies_reserve_and_block_rounded_allowance(self):
        code, report, _ = self.run_guard(20 * CAPACITY.GIB, service='backend')
        self.assertEqual(code, 0)
        self.assertEqual([x['service'] for x in report['services']], ['backend'])
        self.assertEqual(report['vfs_clone_bytes'], 700000000)
        self.assertEqual(report['vfs_allocation_bytes'], 70000640)
        self.assertEqual(report['postgres_start_bytes'], 0)
        self.assertEqual(report['required_bytes'], CAPACITY.GIB + 700000000 + 70000640)
        self.assertEqual(self.run_guard(report['required_bytes'] - 1, service='backend')[0], 1)

    def test_selected_postgres_keeps_start_reserve_and_only_exact_current_identity_reuses(self):
        _, report, _ = self.run_guard(20 * CAPACITY.GIB, service='postgres')
        self.assertEqual(report['vfs_clone_bytes'], 912000000)
        self.assertEqual(report['postgres_start_bytes'], 456000000)
        self.fixture['containers'] = [self.current(healthy=True)]
        _, report, _ = self.run_guard(CAPACITY.GIB, service='postgres')
        self.assertEqual(report['required_bytes'], CAPACITY.GIB)
        self.assertTrue(report['services'][0]['running'])
        self.assertTrue(report['services'][0]['healthy'])
        self.fixture['containers'] = [self.current(healthy=True, hash_value='b' * 64)]
        _, report, _ = self.run_guard(20 * CAPACITY.GIB, service='postgres')
        self.assertEqual(report['vfs_clone_bytes'], 912000000)
        self.assertEqual(report['postgres_start_bytes'], 456000000)
        self.assertFalse(report['services'][0]['running'])

    def test_selected_build_uses_only_own_cold_layers_and_future_two_copies(self):
        self.fixture['model']['services']['backend'] = {'build': {'context': str(self.root / 'backend')}}
        self.fixture['model']['services']['frontend'] = {'build': {'context': '/not-a-build-input'}}
        _, report, _ = self.run_guard(20 * CAPACITY.GIB, 'build', service='backend')
        self.assertEqual([x['service'] for x in report['services']], ['backend'])
        self.assertGreater(report['build_bytes'], 600000000)
        self.assertEqual(report['vfs_clone_bytes'], 2 * report['services'][0]['projected_virtual_bytes'])
        self.assertEqual(report['postgres_start_bytes'], 0)
        self.assertEqual(self.run_guard(20 * CAPACITY.GIB, 'build')[0], 2)

    def test_unknown_or_duplicate_service_refuses_before_build_or_create(self):
        for service in ['', 'unknown', 'backend postgres']:
            with self.subTest(service=service):
                self.assertEqual(self.run_guard(20 * CAPACITY.GIB, service=service)[0], 2)
        with self.assertRaises(SystemExit) as result:
            self.run_guard(20 * CAPACITY.GIB, extra_args=['--service', 'backend', '--service', 'postgres'])
        self.assertEqual(result.exception.code, 2)
        self.assertNotIn('"up"', self.trace.read_text())
        self.assertNotIn('"build"', self.trace.read_text())

    def test_selected_missing_metadata_and_unmeasurable_space_fail_closed(self):
        self.fixture['images']['backend']['Size'] = None
        self.assertEqual(self.run_guard(20 * CAPACITY.GIB, service='backend')[0], 2)
        self.assertEqual(self.run_guard(None, error=OSError('unavailable'), service='backend')[0], 2)

class DockerEntrypointTest(unittest.TestCase):
    def entry(self, cloud=False, fail_phase='', host_build=False, capacity_exit=0):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'scripts').mkdir(); (root / 'backend').mkdir()
            for name in ['docker-verify.sh', 'docker-cloud.sh', 'build-local.sh']:
                shutil.copy(ROOT / 'scripts' / name, root / 'scripts' / name)
                (root / 'scripts' / name).chmod(0o755)
            (root / 'scripts/toolchain-env.sh').write_text('')
            if not host_build:
                (root / 'scripts/build-local.sh').write_text('#!/bin/sh\necho native-build >>"$TRACE"\n')
            commands = {
                'python3': '#!/bin/sh\necho "capacity $*" >>"$TRACE"\nif [ "$1" = scripts/check-workspace-capacity.py ]; then exit "$CAP_EXIT"; fi\nif [ "$3" = "$FAIL_PHASE" ]; then exit 7; fi\n',
                'docker': '#!/bin/sh\necho "docker $* context=${DOCKER_CONTEXT-unset}" >>"$TRACE"\n',
                'npm': '#!/bin/sh\necho npm >>"$TRACE"\n',
                'curl': '#!/bin/sh\nexit 0\n',
            }
            for name, body in commands.items():
                (root / name).write_text(body); (root / name).chmod(0o755)
            (root / 'backend/gradlew').write_text('#!/bin/sh\necho gradle >>"$TRACE"\n'); (root / 'backend/gradlew').chmod(0o755)
            env = os.environ | {'PATH': str(root) + os.pathsep + os.environ['PATH'], 'TRACE': str(root / 'trace'),
                                'CAP_EXIT': str(capacity_exit), 'FAIL_PHASE': fail_phase,
                                'DOCKER_CONTEXT': 'desktop-selected', 'DOCKER_HOST': 'tcp://selected.invalid'}
            args = ['bash', str(root / 'scripts' / ('build-local.sh' if host_build else 'docker-verify.sh'))]
            if cloud: args.append('--cloud-local')
            result = subprocess.run(args, cwd='/tmp', env=env, capture_output=True, text=True)
            return result.returncode, (root / 'trace').read_text() if (root / 'trace').exists() else ''

    def test_native_preflight_stops_before_gradle_or_npm(self):
        for code in [1, 2]:
            status, trace = self.entry(host_build=True, capacity_exit=code)
            self.assertEqual(status, code)
            self.assertNotIn('gradle', trace); self.assertNotIn('npm', trace)

    def test_cloud_build_or_up_refusal_precedes_docker_mutation(self):
        for phase in ['build', 'up']:
            status, trace = self.entry(cloud=True, fail_phase=phase)
            self.assertEqual(status, 7)
            self.assertNotIn('compose up', trace)
            self.assertEqual('compose build' in trace, phase == 'up')

    def test_desktop_selection_is_preserved_but_cloud_pins_only_local_endpoint(self):
        status, trace = self.entry()
        self.assertEqual(status, 0)
        self.assertIn('docker compose up --build -d context=desktop-selected', trace)
        self.assertNotIn('check-docker-capacity', trace)
        status, trace = self.entry(cloud=True)
        self.assertEqual(status, 0)
        self.assertIn('--host=unix:///var/run/docker.sock compose build context=unset', trace)
        self.assertIn('compose up --no-build -d context=unset', trace)
        self.assertLess(trace.index('--cloud-local build'), trace.index('compose build'))
        self.assertLess(trace.index('--cloud-local up'), trace.index('compose up'))


if __name__ == '__main__':
    unittest.main()
