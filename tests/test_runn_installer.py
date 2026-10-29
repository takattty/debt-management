import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('runn_installer', ROOT / 'scripts/install-runn.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def archive_bytes():
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as archive:
        for name, data in [('runn', b'fixture-binary'), ('../escaped', b'must-not-extract')]:
            member = tarfile.TarInfo(name)
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
    return stream.getvalue()


class RunnInstallerTest(unittest.TestCase):
    def test_verified_archive_repairs_changed_binary_without_extracting_other_members(self):
        payload = archive_bytes()
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'cache'
            cache.mkdir()
            (cache / 'runn_v1.11.1_linux_amd64.tar.gz').write_bytes(payload)
            (cache / 'runn').write_bytes(b'changed')
            with patch.dict(os.environ, {'RUNN_CACHE_DIR': str(cache)}), \
                 patch.object(installer.platform, 'system', return_value='Linux'), \
                 patch.object(installer.platform, 'machine', return_value='x86_64'), \
                 patch.dict(installer.ASSETS, {('Linux', 'x86_64'): ('linux_amd64.tar.gz', hashlib.sha256(payload).hexdigest())}), \
                 patch.object(installer.subprocess, 'run') as download, contextlib.redirect_stdout(io.StringIO()):
                installer.main()
            download.assert_not_called()
            self.assertEqual((cache / 'runn').read_bytes(), b'fixture-binary')
            self.assertTrue(os.access(cache / 'runn', os.X_OK))
            self.assertFalse((Path(directory) / 'escaped').exists())

    def test_untrusted_download_never_becomes_an_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'cache'

            def download(arguments, check):
                Path(arguments[-1]).write_bytes(b'wrong archive')
                return subprocess.CompletedProcess(arguments, 0)

            with patch.dict(os.environ, {'RUNN_CACHE_DIR': str(cache)}), \
                 patch.object(installer.platform, 'system', return_value='Linux'), \
                 patch.object(installer.platform, 'machine', return_value='x86_64'), \
                 patch.object(installer.subprocess, 'run', side_effect=download):
                with self.assertRaisesRegex(SystemExit, 'SHA256 mismatch'):
                    installer.main()
            self.assertFalse((cache / 'runn').exists())
