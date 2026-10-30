#!/usr/bin/env python3
"""Discover tracked source paths before reading a unique match."""

import argparse
import difflib
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".cjs", ".mjs",
            ".py", ".sh", ".sql", ".css", ".html"}
SECRET_NAME = re.compile(r"(^|[._-])(secrets?|credentials?|tokens?|passwords?)([._-]|$)", re.I)


def catalog(root):
    top = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                         capture_output=True, text=True, check=True)
    if Path(top.stdout.strip()).resolve() != root:
        raise ValueError("worktree must be a repository root")
    tracked = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                             capture_output=True, text=True, check=True)
    paths = []
    for name in sorted(set(tracked.stdout.rstrip("\0").split("\0"))):
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or ".." in path.parts
                or path.suffix not in SUFFIXES or SECRET_NAME.search(path.name)
                or any(part.startswith(".") or part in ("work", "build", "node_modules", "secrets", "credentials")
                       for part in path.parts)):
            continue
        source = root / name
        if (source.is_file() and source.resolve().is_relative_to(root)
                and not any((root / Path(*path.parts[:index])).is_symlink()
                            for index in range(1, len(path.parts) + 1))):
            paths.append(name)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", help="case-sensitive path substring; use --regex for a regular expression")
    parser.add_argument("--regex", action="store_true")
    parser.add_argument("--list", action="store_true", help="show catalog matches without reading content")
    parser.add_argument("--worktree", type=Path, default=ROOT, help="explicit repository root (default: helper's checkout)")
    parser.add_argument("--start", type=int, default=1)
    parser.add_argument("--end", type=int, default=200)
    args = parser.parse_args()
    if (not args.query.strip() or args.query.startswith("/") or ("\\" in args.query and not args.regex)
            or ".." in PurePosixPath(args.query).parts or any(ord(char) < 32 for char in args.query)):
        parser.error("query must be a relative source pattern without traversal")
    if args.start < 1 or args.end < args.start:
        parser.error("line range requires 1 <= start <= end")
    try:
        expression = re.compile(args.query) if args.regex else None
        root = args.worktree.resolve()
        paths = catalog(root)
        if expression:
            matches = [path for path in paths if expression.search(path)]
        elif args.query in paths:
            matches = [args.query]
        else:
            matches = [path for path in paths if args.query in path]
        if not matches:
            print("No tracked source match. Candidate paths only; refine the query:", file=sys.stderr)
            candidates = sorted(paths, key=lambda path: difflib.SequenceMatcher(
                None, args.query.casefold(), Path(path).name.casefold()).ratio(), reverse=True)[:10]
            print("\n".join(candidates))
            return 1
        if args.list or len(matches) > 1:
            print("\n".join(matches))
            if not args.list:
                print("Multiple matches; select an observed path and invoke again.", file=sys.stderr)
                return 2
            return 0
        # The read path comes exclusively from the observed, filtered Git catalog.
        selected = matches[0]
        lines = (root / selected).read_text(encoding="utf-8").splitlines()
        print(f"{selected}:{args.start}-{min(args.end, len(lines))}")
        for number in range(args.start, min(args.end, len(lines)) + 1):
            print(f"{number}: {lines[number - 1]}")
        return 0
    except (OSError, UnicodeError, ValueError, re.error, subprocess.CalledProcessError):
        print("Source read rejected: invalid pattern, catalog, or source file.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
