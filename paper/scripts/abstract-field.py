#!/usr/bin/env python3
"""Print a paper's abstract as the plain text arXiv's abstract field wants.

arXiv keeps the abstract twice: once in the submitted source, once in the metadata field, and the two are
expected to say the same thing. Transcribing the second by hand is how they drift, so this derives it from
main.tex instead. main.tex stays the only place the abstract is written.

Run from the repository root:

    python3 paper/scripts/abstract-field.py
    PAPER_DIR=arxiv-2604.16986 python3 paper/scripts/abstract-field.py

Exit code 1 means the abstract still holds markup this script does not know how to render as plain text,
which is a prompt to extend it rather than to paste the result.
"""

import os
import re
import sys
from pathlib import Path

# Commands whose argument is the text itself: the markup carries font or emphasis, which the field drops.
INLINE_TEXT_COMMANDS = ("texttt", "code", "emph", "textbf", "textit")

# Spacing and punctuation control sequences, mapped to what a reader of plain text should see.
LITERAL_REPLACEMENTS = {
    r"\,": "",  # thin space inside a quantity, e.g. 25\,\% reads as 25%
    r"\%": "%",
    r"\&": "&",
    r"\_": "_",
    "~": " ",  # non-breaking space
    "--": "-",
}


def abstract_source(main_tex: Path) -> str:
    match = re.search(r"\\begin\{abstract\}(.*?)\\end\{abstract\}", main_tex.read_text(), re.S)
    if match is None:
        sys.exit(f"No abstract environment found in {main_tex}")
    return match.group(1).strip()


def own_macros(main_tex: Path) -> dict[str, str]:
    """The paper's own argument-less \\newcommand definitions, so the field reads what the PDF reads."""
    definitions = re.findall(r"\\newcommand\{\\([A-Za-z]+)\}\{([^{}]*)\}", main_tex.read_text())
    return {name: body for name, body in definitions}


def to_plain_text(latex: str, macros: dict[str, str]) -> str:
    text = latex
    for name, body in macros.items():
        text = re.sub(rf"\\{name}(?![A-Za-z])", body, text)
    for command in INLINE_TEXT_COMMANDS:
        # Repeat until stable: an inline command may wrap another.
        pattern = re.compile(rf"\\{command}\{{([^{{}}]*)\}}")
        while pattern.search(text):
            text = pattern.sub(r"\1", text)
    for source, replacement in LITERAL_REPLACEMENTS.items():
        text = text.replace(source, replacement)
    return re.sub(r"\s+", " ", text).strip()


def main() -> None:
    paper_dir = Path(__file__).resolve().parent.parent / os.environ.get("PAPER_DIR", "three-carriers")
    main_tex = paper_dir / "main.tex"
    text = to_plain_text(abstract_source(main_tex), own_macros(main_tex))

    leftover = sorted(set(re.findall(r"\\[A-Za-z]+|[{}\\]", text)))
    if leftover:
        sys.exit(f"Abstract still holds markup this script does not render: {', '.join(leftover)}")

    print(text)


if __name__ == "__main__":
    main()
