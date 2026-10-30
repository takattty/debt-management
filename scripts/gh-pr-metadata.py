#!/usr/bin/env python3
"""Read a PR's head/base from the REST pulls contract, failing closed on drift."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def repository(value):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise argparse.ArgumentTypeError("repository must be owner/repository")
    return value


def pr_number(value):
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise argparse.ArgumentTypeError("PR number must be a positive integer")
    return int(value)


def metadata(payload, selected_repository, number):
    expected_url = f"https://github.com/{selected_repository}/pull/{number}"
    if (not isinstance(payload, dict) or type(payload.get("number")) is not int
            or payload["number"] != number
            or not isinstance(payload.get("html_url"), str)
            or payload["html_url"].casefold() != expected_url.casefold()
            or payload.get("state") not in ("open", "closed")
            or type(payload.get("merged")) is not bool):
        raise ValueError("REST pull identity/state contract is missing or invalid")
    result = {
        "number": number, "url": payload["html_url"],
        "state": payload["state"], "merged": payload["merged"],
    }
    for side in ("head", "base"):
        ref = payload.get(side)
        if (not isinstance(ref, dict) or not isinstance(ref.get("sha"), str)
                or not re.fullmatch(r"[0-9a-f]{40}", ref["sha"])
                or not isinstance(ref.get("ref"), str) or not ref["ref"].strip()):
            raise ValueError(f"REST pull {side}.sha/ref contract is missing or invalid")
        result[f"{side}RefOid"] = ref["sha"]
        result[f"{side}RefName"] = ref["ref"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=repository)
    parser.add_argument("number", type=pr_number)
    args = parser.parse_args()
    response = subprocess.run([
        str(ROOT / "scripts/gh-repo.sh"), args.repository,
        "api", "--hostname", "github.com", "--method", "GET",
        f"repos/{args.repository}/pulls/{args.number}",
    ], stdout=subprocess.PIPE, text=True, check=False)
    if response.returncode:
        return response.returncode
    try:
        result = metadata(json.loads(response.stdout), args.repository, args.number)
    except (ValueError, TypeError) as error:
        # Do not leak the response body, which can contain private metadata.
        message = str(error) if not isinstance(error, json.JSONDecodeError) else "invalid JSON"
        print(f"PR metadata rejected: {message}", file=sys.stderr)
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
