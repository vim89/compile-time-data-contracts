#!/usr/bin/env python3
"""Hash the Spark sources the paper's version claim rests on, at several tags.

The paper states two things about Spark's comparison surface across releases: that the three
carriers of optionality are declared in the same slots, and that the predicate bodies are the
same text. Both were established by reading, which a reader cannot check without repeating the
reading. This script turns each claim into a hash they can recompute.

What it does, and does not, establish. Two equal hashes mean the normalised token stream of a
declaration is identical at two tags, so a behavioural difference would have to come from
somewhere other than that declaration -- a called helper, a changed default, the runtime. Two
different hashes mean the text moved and say nothing about whether the behaviour did. The hash
is therefore evidence for an argument from source identity and is not a substitute for running
the predicates, which is why the paper also re-runs its whole matrix against a Spark 4 build.

Normalisation drops comments, blank lines and all whitespace runs, because a reformat or a
scaladoc edit is not a change to the predicate. It keeps identifiers, literals and punctuation,
so a renamed parameter or a flipped comparison changes the hash.

Usage:
    spark_predicate_hashes.py [--repo PATH] [--tags TAG [TAG ...]]

PATH is a clone of apache/spark with the tags fetched. Output is a table on stdout; the paper's
evidence file is this output redirected.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys

# The predicate-bearing file. Every configuration the paper measures that belongs to Spark is
# either defined here or, for `StructType.sameType`, inherited from the `DataType` defined here.
DATATYPE = "sql/api/src/main/scala/org/apache/spark/sql/types/DataType.scala"

# Where `==` on a schema is decided.
STRUCTTYPE = "sql/api/src/main/scala/org/apache/spark/sql/types/StructType.scala"

# The declarations that carry optionality. Hashing the whole case-class head rather than the one
# Boolean parameter is deliberate: a carrier could also be changed by reordering the parameters
# or by altering a default, and both are inside this span.
CARRIERS = {
    "StructField": "sql/api/src/main/scala/org/apache/spark/sql/types/StructField.scala",
    "ArrayType": "sql/api/src/main/scala/org/apache/spark/sql/types/ArrayType.scala",
    "MapType": "sql/api/src/main/scala/org/apache/spark/sql/types/MapType.scala",
}

# Named in the order the paper's matrix reports them. `equalsIgnoreCompatibleCollation` is
# Spark 4 only and is expected to be absent at 3.5.6; the report says so rather than failing.
PREDICATES = [
    "sameType",
    "asNullable",
    "equalsIgnoreNullability",
    "equalsIgnoreCaseAndNullability",
    "equalsIgnoreCompatibleNullability",
    "equalsIgnoreNameAndCompatibleNullability",
    "equalsStructurally",
    "equalsStructurallyByName",
    "equalsIgnoreCompatibleCollation",
]

# A line ending in any of these is mid-expression, so the span continues even at depth zero.
CONTINUATIONS = ("=", "=>", "&&", "||", ".", ",", "+", "(", "{", "[")

OPENERS = re.compile(r"[({\[]")
CLOSERS = re.compile(r"[)}\]]")


def read_at_tag(repo: str, tag: str, path: str) -> str | None:
    """The file as it was at `tag`, or None if it did not exist there."""
    done = subprocess.run(
        ["git", "-C", repo, "show", f"{tag}:{path}"],
        capture_output=True,
        text=True,
    )
    return done.stdout if done.returncode == 0 else None


def spans_of(source: str, starts: re.Pattern[str]) -> list[list[str]]:
    """Every span beginning at a line matching `starts` and ending where its brackets close.

    Bracket counting rather than indentation, because Spark's sources are formatted by more than
    one convention across the tags being compared and indentation is exactly what normalisation
    is supposed to ignore.
    """
    lines = source.splitlines()
    found: list[list[str]] = []
    for index, line in enumerate(lines):
        if not starts.search(line):
            continue
        span: list[str] = []
        depth = 0
        for current in lines[index:]:
            span.append(current)
            depth += len(OPENERS.findall(current)) - len(CLOSERS.findall(current))
            body_started = any("=" in s or "{" in s for s in span)
            stripped = current.strip()
            if depth <= 0 and body_started and not stripped.endswith(CONTINUATIONS):
                break
        found.append(span)
    return found


def param_list_of(source: str, name: str) -> list[str] | None:
    """The parameter list of `case class name(...)`, parentheses only.

    Separate from `spans_of` because a case class opens a brace at the end of its head, so
    bracket counting would run to the end of the class body and the hash would then move every
    time an unrelated method was added -- which is exactly what happened across these tags. The
    claim being checked is about the slots, so the span is the slots.
    """
    head = re.search(rf"case class {name}\(", source)
    if head is None:
        return None
    start = head.end() - 1
    depth = 0
    for index in range(start, len(source)):
        char = source[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return [source[start : index + 1]]
    return None


def normalise(span: list[str]) -> str:
    """The span's tokens with comments and whitespace removed."""
    text = "\n".join(span)
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", " ", text)
    return re.sub(r"\s+", "", text)


def digest(span: list[str]) -> str:
    return hashlib.sha256(normalise(span).encode("utf-8")).hexdigest()[:16]


def hashes_at(repo: str, tag: str) -> dict[str, str]:
    """One hash per declaration the paper's claim covers, keyed by name."""
    out: dict[str, str] = {}

    for name, path in CARRIERS.items():
        source = read_at_tag(repo, tag, path)
        if source is None:
            out[f"carrier {name}"] = "no such file"
            continue
        span = param_list_of(source, name)
        out[f"carrier {name}"] = digest(span) if span else "not found"

    datatype = read_at_tag(repo, tag, DATATYPE)
    if datatype is None:
        for name in PREDICATES:
            out[name] = "no such file"
        return out

    for name in PREDICATES:
        # Several predicates are overloaded. Each overload is hashed and the hashes are joined,
        # so an added or removed overload changes the result rather than being silently ignored.
        spans = spans_of(datatype, re.compile(rf"\bdef {name}\b"))
        out[name] = "+".join(digest(s) for s in spans) if spans else "absent"

    # `==` on a schema is one of the measured configurations and it is not in DataType.scala,
    # so hashing only that file would leave the most-used predicate of the family unchecked.
    structtype = read_at_tag(repo, tag, STRUCTTYPE)
    if structtype is None:
        out["StructType.equals"] = "no such file"
    else:
        spans = spans_of(structtype, re.compile(r"\bdef equals\b"))
        out["StructType.equals"] = digest(spans[0]) if spans else "not found"
    return out


def report(repo: str, tags: list[str]) -> int:
    table = {tag: hashes_at(repo, tag) for tag in tags}
    names = list(table[tags[0]])
    width = max(len(n) for n in names)

    print("repo: a clone of https://github.com/apache/spark")
    # The commit each tag resolved to, so that the table is pinned to objects rather than to
    # names a clone could have moved. A reader whose clone prints different commits here is
    # comparing different sources and should expect different hashes.
    for tag in tags:
        commit = subprocess.run(
            ["git", "-C", repo, "rev-parse", f"{tag}^{{commit}}"],
            capture_output=True,
            text=True,
        ).stdout.strip()
        print(f"  {tag} = {commit or 'unresolved'}")
    print("normalisation: comments and all whitespace removed, sha256 truncated to 16 hex chars")
    print()
    header = "declaration".ljust(width) + "  " + "  ".join(t.ljust(36) for t in tags)
    print(header)
    print("-" * len(header))

    differing = []
    for name in names:
        values = [table[tag].get(name, "absent") for tag in tags]
        print(name.ljust(width) + "  " + "  ".join(v.ljust(36) for v in values))
        if len(set(values)) > 1:
            differing.append(name)

    print()
    if differing:
        print("differs across tags: " + ", ".join(differing))
    else:
        print("identical across all tags")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="../spark", help="a clone of apache/spark")
    parser.add_argument(
        "--tags",
        nargs="+",
        default=["v3.5.6", "v4.0.4", "v4.1.3", "v4.2.0"],
        help="the tags to compare, oldest first",
    )
    args = parser.parse_args()
    return report(args.repo, args.tags)


if __name__ == "__main__":
    sys.exit(main())
