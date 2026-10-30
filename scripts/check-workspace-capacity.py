#!/usr/bin/env python3
"""Require 1 GiB available before dependency installation, builds, or E2E."""

import argparse
from pathlib import Path
import shutil
import sys


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_BYTES = 1024 ** 3
CLEANUP = (
    "Stop heavy work. Check df and ownership before starting another worktree/worker. "
    "Only clean regenerable dependencies (frontend/node_modules or work/npm-cache) "
    "in owner-confirmed completed worktrees, after git check-ignore and symlink checks. "
    "Preserve active worktrees, source, jars, databases, and evidence/logs; do not delete broadly."
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", type=Path, default=ROOT,
                        help="existing workspace path (default: helper's checkout)")
    args = parser.parse_args(argv)
    try:
        available = shutil.disk_usage(args.path).free
    except OSError:
        print(f"Workspace capacity unavailable: available=unknown; required={REQUIRED_BYTES} bytes; "
              f"path={args.path}. Measurement failed; heavy work must stop.", file=sys.stderr)
        print(CLEANUP, file=sys.stderr)
        return 2
    message = (f"Workspace capacity: available={available} bytes ({available / REQUIRED_BYTES:.2f} GiB); "
               f"required={REQUIRED_BYTES} bytes (1.00 GiB); path={args.path}")
    if available < REQUIRED_BYTES:
        print(message, file=sys.stderr)
        print(CLEANUP, file=sys.stderr)
        return 1
    print(message)
    return 0


if __name__ == "__main__":
    sys.exit(main())
