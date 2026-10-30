#!/usr/bin/env python3
"""Read-only managed-Cloud Docker build/up budget. Never creates or deletes Docker objects."""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

OWNER = Path(__file__).resolve().parents[1]
ROOT = OWNER
MARKER = Path('/etc/codex/network-policy.json')
GIB = 1024 ** 3
PG_MINIMUM = 256 * 1024 ** 2


class MeasurementError(Exception):
    pass


def docker(*args, json_output=True):
    try:
        result = subprocess.run(['bash', str(OWNER / 'scripts/docker-cloud.sh'), *args], cwd=ROOT,
                                capture_output=True, text=True, timeout=30, check=False)
        if result.returncode:
            raise MeasurementError('Docker metadata command failed; no build/create was attempted')
        return json.loads(result.stdout) if json_output else result.stdout
    except (OSError, subprocess.TimeoutExpired, ValueError) as error:
        # Compose config and inspect may contain credentials. Never echo their output or exception text.
        raise MeasurementError('Docker metadata unavailable or malformed') from error


def image(reference):
    records = docker('image', 'inspect', reference)
    if not isinstance(records, list) or len(records) != 1:
        raise MeasurementError('Image metadata unavailable; pre-load the pinned image before a Cloud build')
    result = records[0]
    if type(result.get('Size')) is not int or result['Size'] <= 0 or not result.get('Id'):
        raise MeasurementError('Uncompressed virtual image size unavailable')
    return result


def tree_bytes(path):
    """Count the whole context conservatively, including files Dockerignore might exclude."""
    if path.is_symlink():
        return path.lstat().st_size + 8192
    if path.is_file():
        return path.stat().st_size + 8192
    if not path.is_dir():
        raise MeasurementError('Build input unavailable; run the native artifact build first')
    total = 8192
    for child in path.iterdir():
        total += tree_bytes(child)
    return total


def build_budget(build, driver):
    if not isinstance(build, dict):
        raise MeasurementError('Unsupported build context')
    context = Path(build['context']).resolve()
    if ROOT.resolve() not in (context, *context.parents):
        raise MeasurementError('Build context must belong to this checkout')
    dockerfile = context / build.get('dockerfile', 'Dockerfile')
    base = None
    copied = 0
    layers = 0
    for raw in dockerfile.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        parts = shlex.split(line)
        operation = parts.pop(0).upper()
        if operation == 'FROM' and base is None and len(parts) == 1:
            base = image(parts[0])['Size']
        elif operation == 'COPY':
            if parts and parts[0].startswith('--chmod='):
                parts.pop(0)
            if len(parts) != 2 or parts[0].startswith(('-', '[')):
                raise MeasurementError('Unsupported COPY; cannot bound its storage')
            source = context / parts[0]
            if context not in (source.resolve(), *source.resolve().parents):
                raise MeasurementError('COPY input leaves its build context')
            copied += tree_bytes(source)
            layers += 1
        elif operation == 'WORKDIR':
            layers += 1
        elif operation not in ('USER', 'EXPOSE', 'ENTRYPOINT', 'CMD', 'LABEL', 'ENV'):
            raise MeasurementError('Unsupported Dockerfile instruction; build size is unknown')
    if base is None:
        raise MeasurementError('Pinned base image unavailable')
    # VFS retains deep copies for filesystem layers plus one transient working container.
    # COW drivers retain copied payload instead. Context transport/tar metadata is additional.
    return {'build_bytes': tree_bytes(context) + ((base + copied) * (layers + 1) if driver == 'vfs' else copied),
            'final_virtual_bytes': base + copied}


def measure(phase):
    if not MARKER.is_file():
        raise MeasurementError('This mode is only for the managed Cloud local daemon')
    info = docker('info', '--format', '{{json .}}')
    driver = info['Driver']
    if driver not in ('vfs', 'overlay2', 'overlayfs'):
        raise MeasurementError('Storage driver is unknown to this budget')
    daemon_root = Path(info['DockerRootDir'])
    if not daemon_root.is_absolute() or not daemon_root.is_dir():
        raise MeasurementError('Local daemon filesystem cannot be measured')
    available = shutil.disk_usage(daemon_root).free
    workspace_available = shutil.disk_usage(ROOT).free
    if any(type(value) is not int or value < 0 for value in (available, workspace_available)):
        raise MeasurementError('Filesystem available bytes are unknown')
    model = docker('compose', 'config', '--format', 'json')
    services = model['services']
    hashes = {}
    for line in docker('compose', 'config', '--hash', '*', json_output=False).splitlines():
        name, digest = line.split()
        if not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise MeasurementError('Compose config hash unavailable')
        hashes[name] = digest
    references = set(docker('compose', 'config', '--images', json_output=False).splitlines())
    ids = docker('compose', 'ps', '--all', '--quiet', json_output=False).split()
    if any(not re.fullmatch(r'[a-f0-9]{12,64}', value) for value in ids):
        raise MeasurementError('Container identity unavailable')
    containers = docker('container', 'inspect', *ids) if ids else []
    clone = pg = build_bytes = 0
    planned = []
    for name, service in services.items():
        reference = service.get('image') or f"{model['name']}-{name}"
        if reference not in references or name not in hashes:
            raise MeasurementError('Compose image/config identity unavailable')
        if phase == 'build' and service.get('build'):
            projected = build_budget(service['build'], driver)
            build_bytes += projected['build_bytes']
            if driver == 'vfs':
                clone += projected['final_virtual_bytes']
            planned.append({'service': name, 'projected_virtual_bytes': projected['final_virtual_bytes']})
            continue
        measured = image(reference)
        own = [entry for entry in containers if entry['Config']['Labels'].get('com.docker.compose.service') == name
               and entry['Config']['Labels'].get('com.docker.compose.project') == model['name']]
        if len(own) > 1:
            raise MeasurementError('Scaled containers are outside this Compose budget')
        current = bool(own and own[0]['Image'] == measured['Id']
                       and own[0]['Config']['Labels'].get('com.docker.compose.config-hash') == hashes[name])
        if driver == 'vfs' and not current:
            clone += measured['Size']
        healthy = bool(current and own[0]['State'].get('Running')
                       and own[0]['State'].get('Health', {}).get('Status') == 'healthy')
        postgres = name == 'postgres' or reference.rsplit('/', 1)[-1].split('@')[0].split(':')[0] == 'postgres'
        if postgres and not healthy:
            pg += max(PG_MINIMUM, measured['Size'])
        planned.append({'service': name, 'virtual_bytes': measured['Size'], 'current_container': current,
                        'healthy': healthy})
    # A build leaves room for subsequent new application containers, not just its transient layers.
    required = GIB + clone + pg + build_bytes
    return {'phase': phase, 'driver': driver, 'worktree': str(ROOT), 'available_bytes': available,
            'workspace_available_bytes': workspace_available, 'reserve_bytes': GIB,
            'vfs_clone_bytes': clone, 'postgres_start_bytes': pg, 'build_bytes': build_bytes,
            'required_bytes': required, 'services': planned,
            'sufficient': available >= required and workspace_available >= GIB}


def main(argv=None):
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cloud-local', required=True, action='store_true')
    parser.add_argument('--worktree', type=Path, default=ROOT, help='explicit Compose owner; helper stays in its own checkout')
    parser.add_argument('phase', choices=('build', 'up'))
    args = parser.parse_args(argv)
    try:
        ROOT = args.worktree.resolve()
        result = measure(args.phase)
        print(json.dumps(result, sort_keys=True))
        if not result['sufficient']:
            print('Docker capacity insufficient. Stop other heavy workers; preserve source, logs, images and volumes.', file=sys.stderr)
            return 1
        return 0
    except (MeasurementError, OSError, KeyError, TypeError, ValueError, AttributeError, IndexError) as error:
        print('Docker capacity unavailable; build/create refused. ' + (str(error) if isinstance(error, MeasurementError) else 'Measurement failed.'), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
