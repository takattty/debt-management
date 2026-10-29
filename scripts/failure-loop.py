#!/usr/bin/env python3
"""Persist repeated failures and require a reviewed prevention before continuing."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_STORE = ROOT / "docs/learning/failures.json"
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9._:-]{0,119}\Z")


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def plain(value, name):
    value = value.strip()
    if not value or len(value) > 2000 or any(ord(c) < 32 for c in value):
        raise ValueError(f"{name} must contain 1..2000 characters without control characters")
    return value


def identifier(value, name):
    if not IDENTIFIER.fullmatch(value):
        raise ValueError(f"{name} must be a short lowercase cause/event identifier")
    return value


def repo_file(value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] == ".git":
        raise ValueError("guard must be a repository-relative existing file")
    resolved = (ROOT / path).resolve()
    if not resolved.is_relative_to(ROOT) or not resolved.is_file():
        raise ValueError("guard must resolve to an existing file inside this repository")
    return path.as_posix()


def evidence_reference(value):
    """Store references, never raw error output, query tokens, or absolute paths."""
    value = plain(value, "evidence")
    url = urlsplit(value)
    if url.scheme:
        if url.scheme != "https" or url.netloc != "github.com" or url.query or url.username:
            raise ValueError("evidence URL must be an HTTPS github.com reference without query/auth")
        return value
    path = Path(value.split("#", 1)[0])
    if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] == ".git":
        raise ValueError("evidence must be a repository-relative path or GitHub URL")
    if not (ROOT / path).resolve().is_relative_to(ROOT):
        raise ValueError("evidence path must remain inside the repository")
    return value


def normalize(data):
    if not isinstance(data, dict) or type(data.get("schema_version")) is not int or data["schema_version"] != 1:
        raise ValueError("failure store must use schema_version 1")
    records = data.get("failures")
    if not isinstance(records, list):
        raise ValueError("failure store failures must be a list")
    normalized = []
    keys = set()
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("invalid failure record")
        key = identifier(record.get("key", ""), "key")
        if key in keys:
            raise ValueError("duplicate failure key")
        keys.add(key)
        events = record.get("events")
        resolutions = record.get("resolutions", [])
        if not isinstance(events, list) or not events or not isinstance(resolutions, list):
            raise ValueError("failure record requires events and resolutions lists")
        event_ids = set()
        normalized_events = []
        for event in events:
            event_id = identifier(event.get("event", ""), "event")
            if event_id in event_ids:
                raise ValueError("duplicate event in failure record")
            event_ids.add(event_id)
            normalized_events.append({
                "event": event_id,
                "summary": plain(event.get("summary", ""), "summary"),
                "evidence": evidence_reference(event.get("evidence", "")),
                "recorded_at": plain(event.get("recorded_at", ""), "recorded_at"),
            })
        normalized_resolutions = []
        for resolution in resolutions:
            count = resolution.get("event_count")
            if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= len(events):
                raise ValueError("invalid resolution event_count")
            normalized_resolutions.append({
                "cause": plain(resolution.get("cause", ""), "cause"),
                "prevention": plain(resolution.get("prevention", ""), "prevention"),
                "verification": plain(resolution.get("verification", ""), "verification"),
                "guard": repo_file(resolution.get("guard", "")),
                "event_count": count,
                "resolved_at": plain(resolution.get("resolved_at", ""), "resolved_at"),
            })
        # Derive state from evidence; editing a status flag cannot bypass the gate.
        resolved = bool(normalized_resolutions) and normalized_resolutions[-1]["event_count"] == len(events)
        normalized.append({
            "key": key,
            "status": "resolved" if resolved else "open",
            "events": normalized_events,
            "resolutions": normalized_resolutions,
        })
    return {"schema_version": 1, "failures": sorted(normalized, key=lambda item: item["key"])}


def read_store(path):
    if not path.exists():
        raise ValueError(f"failure store is missing: {path.name}")
    return normalize(json.loads(path.read_text(encoding="utf-8")))


def write_store(path, data):
    data = normalize(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def store_lock(path):
    # Keep lock outside the repository so concurrent CLI writers cannot lose events.
    digest = hashlib.sha256(str(path).encode()).hexdigest()
    with (Path(tempfile.gettempdir()) / f"debt-failure-loop-{digest}.lock").open("a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", type=Path, default=DEFAULT_STORE, help="override only for disposable tests")
    commands = parser.add_subparsers(dest="command", required=True)
    record = commands.add_parser("record", help="record a distinct observed failure")
    record.add_argument("--key", required=True)
    record.add_argument("--event", required=True)
    record.add_argument("--summary", required=True)
    record.add_argument("--evidence", required=True)
    resolve = commands.add_parser("resolve", help="save cause, prevention, verified result and future guard")
    resolve.add_argument("--key", required=True)
    for field in ("cause", "prevention", "verification", "guard"):
        resolve.add_argument(f"--{field}", required=True)
    commands.add_parser("check", help="fail on repeated unresolved causes or invalid records")
    args = parser.parse_args()
    path = args.store.resolve()
    try:
        with store_lock(path):
            if args.command == "record" and not path.exists():
                data = {"schema_version": 1, "failures": []}
            else:
                data = read_store(path)
            if args.command == "record":
                key = identifier(args.key, "key")
                event_id = identifier(args.event, "event")
                summary = plain(args.summary, "summary")
                evidence = evidence_reference(args.evidence)
                failure = next((item for item in data["failures"] if item["key"] == key), None)
                if failure is None:
                    failure = {"key": key, "events": [], "resolutions": []}
                    data["failures"].append(failure)
                if any(item["event"] == event_id for item in failure["events"]):
                    print(f"duplicate event ignored: {key}/{event_id}")
                    return 0
                failure["events"].append({"event": event_id, "summary": summary, "evidence": evidence, "recorded_at": now()})
                write_store(path, data)
                print(f"recorded: {key} ({len(failure['events'])} distinct events)")
            elif args.command == "resolve":
                key = identifier(args.key, "key")
                failure = next((item for item in data["failures"] if item["key"] == key), None)
                if failure is None:
                    raise ValueError("cannot resolve an unknown cause")
                resolution = {field: plain(getattr(args, field), field) for field in ("cause", "prevention", "verification")}
                resolution.update(guard=repo_file(args.guard), event_count=len(failure["events"]), resolved_at=now())
                failure["resolutions"].append(resolution)
                write_store(path, data)
                print(f"resolved: {key} at {len(failure['events'])} events; future guard: {resolution['guard']}")
            else:
                blocking = [item for item in data["failures"] if len(item["events"]) >= 2 and item["status"] != "resolved"]
                for item in blocking:
                    print(f"BLOCKED: {item['key']} ({len(item['events'])} events): record cause/prevention/verification/guard", file=sys.stderr)
                if blocking:
                    return 1
                print("failure-loop check passed")
        return 0
    except (ValueError, TypeError, AttributeError, OSError, json.JSONDecodeError) as error:
        print(f"failure-loop error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
