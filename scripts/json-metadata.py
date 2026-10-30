#!/usr/bin/env python3
"""Describe a JSON envelope without emitting string values or unbounded output."""

import argparse
import json
from pathlib import Path
import sys

MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_BYTES = 4096
MAX_NAME_BYTES = 64


class Parser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's normal diagnostic can echo an arbitrarily large argument.
        raise ValueError()


def describe(value, depth, max_depth, max_nodes, visited):
    visited[0] += 1
    if isinstance(value, str):
        return {"type": "string", "utf8Bytes": len(value.encode("utf-8"))}
    if value is None:
        return {"type": "null"}
    if isinstance(value, bool):
        return {"type": "boolean"}
    if isinstance(value, (int, float)):
        return {"type": "number"}
    is_object = isinstance(value, dict)
    if not is_object and not isinstance(value, list):
        raise ValueError()
    result = {"type": "object" if is_object else "array",
              "fieldCount" if is_object else "itemCount": len(value)}
    if depth >= max_depth:
        result["truncated"] = "depth"
        return result
    children = []
    pairs = value.items() if is_object else enumerate(value)
    for name, child in pairs:
        if visited[0] >= max_nodes:
            break
        if is_object:
            size = len(name.encode("utf-8"))
            entry = {"name": name} if size <= MAX_NAME_BYTES else {"nameUtf8Bytes": size}
        else:
            entry = {"index": name}
        entry["summary"] = describe(child, depth + 1, max_depth, max_nodes, visited)
        children.append(entry)
    result["fields" if is_object else "items"] = children
    omitted = len(value) - len(children)
    if omitted:
        result["omittedFields" if is_object else "omittedItems"] = omitted
    return result


def render_metadata(value, input_bytes, max_depth=4, max_nodes=64):
    if not 0 <= max_depth <= 8 or not 1 <= max_nodes <= 128:
        raise ValueError()
    budget = max_nodes
    while budget:
        visited = [0]
        summary = describe(value, 0, max_depth, budget, visited)
        document = {"inputBytes": input_bytes,
                    "limits": {"maxDepth": max_depth, "requestedMaxNodes": max_nodes,
                               "appliedMaxNodes": budget, "maxOutputBytes": MAX_OUTPUT_BYTES},
                    "visitedNodes": visited[0], "reducedForOutput": budget != max_nodes,
                    "summary": summary}
        output = (json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        if len(output) <= MAX_OUTPUT_BYTES:
            return output
        # Rebuild with a smaller traversal budget; never truncate serialized JSON.
        budget //= 2
    raise ValueError()


def reject_constant(value):
    raise ValueError()


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("path", nargs="?", default="-", help="JSON artifact path, or - for stdin")
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--max-nodes", type=int, default=64)
    try:
        args = parser.parse_args(argv)
        if not 0 <= args.max_depth <= 8 or not 1 <= args.max_nodes <= 128:
            raise ValueError()
        if args.path == "-":
            raw = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        else:
            with Path(args.path).open("rb") as source:
                raw = source.read(MAX_INPUT_BYTES + 1)
        if len(raw) > MAX_INPUT_BYTES:
            raise ValueError()
        # Numeric values are never emitted; avoid converting enormous literals.
        value = json.loads(raw.decode("utf-8-sig"), parse_constant=reject_constant,
                           parse_int=lambda token: 0, parse_float=lambda token: 0.0)
        output = render_metadata(value, len(raw), args.max_depth, args.max_nodes)
        if len(output) > MAX_OUTPUT_BYTES:
            raise ValueError()
        sys.stdout.buffer.write(output)
        return 0
    except (OSError, UnicodeError, ValueError, RecursionError):
        print("Metadata rejected: invalid JSON, input size, Unicode, or limits.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
