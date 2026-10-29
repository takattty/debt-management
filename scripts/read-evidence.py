#!/usr/bin/env python3
"""Find bounded evidence in registered worktrees without relying on the caller's cwd."""

import argparse
from collections import deque
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", help="Repository-relative work/... file")
    parser.add_argument("--worktree", type=Path)
    parser.add_argument("--lines", type=int, default=20)
    args = parser.parse_args()
    relative = Path(args.artifact)
    if relative.is_absolute() or not relative.parts or relative.parts[0] != "work" or ".." in relative.parts:
        parser.error("Evidence must be a relative work/... path")
    if not 1 <= args.lines <= 200:
        parser.error("lines must be between 1 and 200")
    root = Path(__file__).resolve().parents[1]
    listing = subprocess.check_output(
        ["git", "-C", str(root), "worktree", "list", "--porcelain", "-z"], text=True,
    )
    owners = [Path(field[9:]) for field in listing.split("\0") if field.startswith("worktree ")]
    if args.worktree:
        owners = [owner for owner in owners if owner.resolve() == args.worktree.resolve()]
    matches = []
    for owner in owners:
        path = (owner / relative).resolve()
        if path.is_relative_to((owner / "work").resolve()) and path.is_file():
            matches.append(path)
    if len(matches) != 1:
        parser.error(f"Expected one evidence owner, found {len(matches)}; select --worktree explicitly")
    path = matches[0]
    print(f"Evidence: {path}")
    with path.open(encoding="utf-8", errors="replace") as source:
        sys.stdout.writelines(deque(source, maxlen=args.lines))


if __name__ == "__main__":
    main()
