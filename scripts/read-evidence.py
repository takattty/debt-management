#!/usr/bin/env python3
"""Find bounded evidence in registered worktrees without relying on the caller's cwd."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys


MAX_OUTPUT_BYTES = 16 * 1024
MAX_CHUNK_BYTES = 4096


def text_tail(path, count):
    # CRLF may occupy twice the emitted UTF-8 bytes. Never read an unbounded line.
    with path.open("rb") as source:
        size = source.seek(0, os.SEEK_END)
        start = max(0, size - (2 * MAX_OUTPUT_BYTES + 2))
        source.seek(start)
        raw = source.read(2 * MAX_OUTPUT_BYTES + 2)
    lines = list(io.StringIO(raw.decode("utf-8", errors="replace"), newline=None))
    if start:
        lines = lines[1:]  # The first line may start in the middle of a UTF-8 sequence.
        if len(lines) < count:
            raise ValueError("Evidence output exceeds 16384 bytes; use --json-chunk")
    return "".join(lines[-count:])


def read_chunk(path, offset, size):
    digest = hashlib.sha256()
    data = bytearray()
    total = 0
    with path.open("rb") as source:
        before = os.fstat(source.fileno())
        if offset > before.st_size:
            raise ValueError("offset exceeds artifact size")
        while total < before.st_size:
            block = source.read(min(64 * 1024, before.st_size - total))
            if not block:
                break
            digest.update(block)
            start, end = max(offset - total, 0), min(offset + size - total, len(block))
            if start < end:
                data.extend(block[start:end])
            total += len(block)
        after = os.fstat(source.fileno())
    fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
    if total != before.st_size or any(getattr(before, field) != getattr(after, field) for field in fields):
        raise ValueError("Artifact changed while reading; retry only after its writer stops")
    return {"offset": offset, "size": len(data), "total": total, "sha256": digest.hexdigest(),
            "encoding": "base64", "dataBase64": base64.b64encode(data).decode("ascii")}


def emit(text):
    encoded = text.encode("utf-8")
    if len(encoded) > MAX_OUTPUT_BYTES:
        raise ValueError("Evidence output exceeds 16384 bytes; use --json-chunk")
    sys.stdout.buffer.write(encoded)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", help="Repository-relative work/... file")
    parser.add_argument("--worktree", type=Path)
    parser.add_argument("--lines", type=int, help="Tail lines, 1..200 (default: 20)")
    parser.add_argument("--json-chunk", action="store_true", help="Emit only bounded JSON with base64 bytes")
    parser.add_argument("--offset", type=int, help="Zero-based byte offset for --json-chunk (default: 0)")
    parser.add_argument("--size", type=int, help="Requested chunk bytes, 1..4096 (default: 4096)")
    args = parser.parse_args()
    relative = Path(args.artifact)
    if relative.is_absolute() or not relative.parts or relative.parts[0] != "work" or ".." in relative.parts:
        parser.error("Evidence must be a relative work/... path")
    if args.lines is not None and not 1 <= args.lines <= 200:
        parser.error("lines must be between 1 and 200")
    if args.json_chunk:
        if args.lines is not None:
            parser.error("--lines cannot be combined with --json-chunk")
        if args.offset is not None and args.offset < 0:
            parser.error("offset must be nonnegative")
        if args.size is not None and not 1 <= args.size <= MAX_CHUNK_BYTES:
            parser.error("size must be between 1 and 4096 bytes")
    elif args.offset is not None or args.size is not None:
        parser.error("--offset and --size require --json-chunk")
    root = Path(__file__).resolve().parents[1]
    listing = subprocess.check_output(
        ["git", "-C", str(root), "worktree", "list", "--porcelain", "-z"], text=True,
    )
    owners = [Path(field[9:]) for field in listing.split("\0") if field.startswith("worktree ")]
    if args.worktree:
        owners = [owner for owner in owners if owner.resolve() == args.worktree.resolve()]
    matches = []
    for owner in owners:
        work = (owner / "work").resolve()
        path = (owner / relative).resolve()
        if work == owner.resolve() / "work" and path.is_relative_to(work) and path.is_file():
            matches.append(path)
    if len(matches) != 1:
        parser.error(f"Expected one evidence owner, found {len(matches)}; select --worktree explicitly")
    path = matches[0]
    try:
        if args.json_chunk:
            result = read_chunk(path, args.offset or 0, args.size or MAX_CHUNK_BYTES)
            result["artifact"] = args.artifact
            result["path"] = str(path)
            emit(json.dumps(result, ensure_ascii=True, separators=(",", ":")) + "\n")
        else:
            emit(f"Evidence: {path}\n" + text_tail(path, args.lines or 20))
    except (OSError, ValueError) as failure:
        parser.error(str(failure))


if __name__ == "__main__":
    main()
