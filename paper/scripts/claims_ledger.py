#!/usr/bin/env python3
"""Turn the artifact claim matrix in ARTIFACT.md into a machine-readable, revision-pinned ledger.

ARTIFACT.md stays the single place a claim is written. This script only re-reads it, so the two cannot
disagree. It also resolves every repository path cited as evidence and fails if one is missing, which is the
part a human reviewer cannot do reliably: a claim whose evidence moved still reads as closed.

Run from the repository root:

    python3 paper/scripts/claims_ledger.py

Writes paper/evidence/claims.json. Exit code 1 means a cited evidence path does not exist.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "ARTIFACT.md"
OUTPUT = ROOT / "paper" / "evidence" / "claims.json"

# A markdown link in the evidence or limit column. Only the target matters here.
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
# A row of the claim matrix: | AC1 | statement | `status` | evidence | limit |
ROW = re.compile(r"^\|\s*(AC\d+)\s*\|(.*)$")


def revision() -> dict:
    """The commit the ledger describes, and whether the tree was clean when it was generated."""
    head = subprocess.run(
        ["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()
    dirty = subprocess.run(
        ["git", "-C", str(ROOT), "status", "--porcelain"], capture_output=True, text=True, check=True
    ).stdout.strip()
    return {"commit": head, "clean_worktree": dirty == ""}


def parse_claims(text: str) -> list:
    claims = []
    for line in text.splitlines():
        match = ROW.match(line)
        if match is None:
            continue
        cells = [c.strip() for c in match.group(2).split("|")]
        # trailing empty cell from the closing pipe
        if cells and cells[-1] == "":
            cells.pop()
        if len(cells) != 4:
            raise SystemExit(f"claim {match.group(1)} has {len(cells)} cells, expected 4: {line[:120]}")
        statement, status, evidence, limit = cells
        claims.append(
            {
                "id": match.group(1),
                "namespace": "artifact",
                "statement": statement,
                "status": status.strip("`"),
                "evidence_paths": LINK.findall(evidence),
                "evidence_prose": evidence,
                "limit": limit,
            }
        )
    return claims


def check_paths(claims: list) -> list:
    missing = []
    for claim in claims:
        for path in claim["evidence_paths"]:
            if not (ROOT / path).exists():
                missing.append((claim["id"], path))
    return missing


def main() -> int:
    claims = parse_claims(SOURCE.read_text())
    if not claims:
        raise SystemExit(f"no AC* rows found in {SOURCE}; the table format changed")

    missing = check_paths(claims)
    if missing:
        for claim_id, path in missing:
            print(f"{claim_id}: evidence path does not exist: {path}", file=sys.stderr)
        return 1

    ledger = {
        "generated_from": SOURCE.relative_to(ROOT).as_posix(),
        "generator": Path(__file__).relative_to(ROOT).as_posix(),
        "revision": revision(),
        "note": (
            "Artifact claims only. Research claims are numbered RC* in paper/RESEARCH-DESIGN.md and are not "
            "part of this file. Status values: closed, partial, open."
        ),
        "status_counts": {s: sum(1 for c in claims if c["status"] == s) for s in ("closed", "partial", "open")},
        "claims": claims,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(ledger, indent=2, ensure_ascii=False) + "\n")
    print(f"{OUTPUT.relative_to(ROOT)}: {len(claims)} claims, {ledger['status_counts']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
