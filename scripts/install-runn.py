#!/usr/bin/env python3
"""Install the pinned official runn asset in a writable workspace cache."""
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import tempfile
import zipfile

VERSION = '1.11.1'
ASSETS = {
    ('Linux', 'x86_64'): ('linux_amd64.tar.gz', '3bdb4a48368ba45831c69719f0c72228c7c8405338ff27f7369095ccf07ea248'),
    ('Linux', 'aarch64'): ('linux_arm64.tar.gz', '6dd48b40a0499213c06133fe1b3e3022de7a52514d0d6fe9f3e9796549546fd2'),
    ('Darwin', 'x86_64'): ('darwin_amd64.zip', '69519ca01fcc55cbe72820842c465b306216e77f297b5a1b10a6a78092d3d6c5'),
    ('Darwin', 'arm64'): ('darwin_arm64.zip', '09047d473e2eae5b45d8a1ce81b78ae568357f755a451b50eceb27117ca1e10f'),
}
ROOT = Path(__file__).resolve().parents[1]


def main():
    selected = ASSETS.get((platform.system(), platform.machine()))
    if not selected:
        raise SystemExit('Supported runn platforms: Linux/Darwin amd64/arm64.')
    suffix, checksum = selected
    cache = Path(os.environ.get('RUNN_CACHE_DIR', ROOT / f'work/tools/runn-{VERSION}')).resolve()
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f'runn_v{VERSION}_{suffix}'
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        with tempfile.TemporaryDirectory(dir=cache) as directory:
            download = Path(directory) / 'asset'
            url = f'https://github.com/k1LoW/runn/releases/download/v{VERSION}/{archive.name}'
            subprocess.run(['curl', '--silent', '--show-error', '--fail', '--location',
                            '--connect-timeout', '20', '--max-time', '180', url, '--output', str(download)], check=True)
            if hashlib.sha256(download.read_bytes()).hexdigest() != checksum:
                raise SystemExit('runn archive SHA256 mismatch; refusing to install.')
            os.replace(download, archive)
    if suffix.endswith('.zip'):
        with zipfile.ZipFile(archive) as package:
            binary = package.read('runn')
    else:
        with tarfile.open(archive, 'r:gz') as package:
            member = package.getmember('runn')
            if not member.isfile():
                raise SystemExit('runn archive entry is not a regular file.')
            binary = package.extractfile(member).read()
    executable = cache / 'runn'
    if not executable.exists() or hashlib.sha256(executable.read_bytes()).digest() != hashlib.sha256(binary).digest():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as target:
            target.write(binary)
            temporary = Path(target.name)
        temporary.chmod(0o755)
        os.replace(temporary, executable)
    executable.chmod(0o755)
    print(executable)


if __name__ == '__main__':
    main()
