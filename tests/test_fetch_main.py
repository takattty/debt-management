from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FetchMainTest(unittest.TestCase):
    def test_explicit_destination_repairs_a_stale_tracking_ref(self):
        with tempfile.TemporaryDirectory() as directory:
            origin, seed, client = [Path(directory) / name for name in ('origin', 'seed', 'client')]

            def git(path, *args):
                return subprocess.check_output(['git', '-C', str(path), *args], text=True, stderr=subprocess.DEVNULL).strip()

            for path in (origin, seed, client):
                path.mkdir()
            git(origin, 'init', '--bare', '--initial-branch=main')
            git(seed, 'init', '--initial-branch=main')
            git(seed, 'config', 'user.name', 'Fixture')
            git(seed, 'config', 'user.email', 'fixture@example.invalid')
            git(seed, 'remote', 'add', 'origin', str(origin))
            (seed / 'sample').write_text('first')
            git(seed, 'add', 'sample')
            git(seed, 'commit', '--no-gpg-sign', '-m', 'first')
            git(seed, 'push', 'origin', 'main')
            old = git(seed, 'rev-parse', 'HEAD')
            git(client, 'init')
            git(client, 'remote', 'add', 'origin', str(origin))
            git(client, 'config', '--unset-all', 'remote.origin.fetch')
            git(client, 'fetch', 'origin', 'main')
            git(client, 'update-ref', 'refs/remotes/origin/main', old)
            (seed / 'sample').write_text('second')
            git(seed, 'commit', '--no-gpg-sign', '-am', 'second')
            git(seed, 'push', 'origin', 'main')
            latest = git(seed, 'rev-parse', 'HEAD')
            git(client, 'fetch', 'origin', 'main')
            self.assertEqual(git(client, 'rev-parse', 'origin/main'), old)
            result = subprocess.run(['bash', str(ROOT / 'scripts/fetch-main.sh')], cwd=client,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(git(client, 'rev-parse', 'origin/main'), latest)
            self.assertEqual(result.stdout.strip(), latest)
