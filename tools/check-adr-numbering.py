#!/usr/bin/env python3
"""Check ADR numbering and citation hygiene across the repository.

`docs/decisions/product/` and `docs/decisions/technical/` are independent
sequences, so a number can legitimately exist in both. That makes an
unqualified `ADR-NNNN` ambiguous for every number the two share, and it makes a
duplicate number *within* one namespace a genuine collision. Three collisions
and 91 ambiguous citations had accumulated unnoticed before #740; this is what
stops the next one.

Rules, all blocking:

  R1  No two files in the same namespace share an NNNN prefix.
  R2  A file's `# ` heading number matches its filename number.
  R3  Headings read `# Product ADR-NNNN: ` / `# Technical ADR-NNNN: `,
      matching the directory the file sits in.
  R4  Every cited ADR-NNNN resolves to a file; a qualified citation resolves
      within the namespace it names.
  R5  Outside docs/decisions/, a bare ADR-NNNN whose number exists in both
      namespaces must carry a `product` or `technical` qualifier.

R5 accepts every way a qualifier is actually written in this repo — separated
by a space or a hyphen, and split across a line wrap. Correct prose is never
required to be reflowed to satisfy this check.

Run standalone before pushing:  python3 tools/check-adr-numbering.py
Exits 0 when clean, 1 with one `file:line: [R#] message` per violation.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# git ls-files emits forward slashes on every platform, so compare against
# a POSIX prefix rather than one built with os.sep.
DECISIONS = "docs/decisions"
NAMESPACES = ("product", "technical")
# Every tracked text file is scanned rather than an extension allowlist: an
# allowlist silently stops enforcing the convention the moment a citation lands
# in a format nobody thought of (this check first shipped blind to the .html
# architecture docs and to robolectric.properties).
BINARY_SNIFF_BYTES = 8192

# Optional `product`/`technical` qualifier, then ADR-NNNN. The separator may be
# a space or a hyphen: both read naturally ("product ADR-0007" as a citation,
# "product-ADR-0007 invariant guard" as a compound adjective).
CITATION = re.compile(
    # (?<![-\w]) not \b: with a hyphen allowed as the separator, \b alone would
    # read the tail of a hyphen-joined word as the qualifier, so "By-product
    # ADR-0007" would parse as a product citation and escape R5.
    r"(?:(?<![-\w])(product|technical)[-\s]+)?ADR-(\d{4})",
    re.IGNORECASE,
)
# A citation may also wrap, leaving the qualifier at the end of the previous
# line: a "See technical" ending one line, with "ADR-XXXX" opening the next.
DANGLING_QUALIFIER = re.compile(r"\b(product|technical)\s*$", re.IGNORECASE)
# Comment, list and quote markers that open a wrapped continuation line —
# includes "/" so a Kotlin // comment continuation counts, not just KDoc "*".
CONTINUATION_PREFIX = re.compile(r"^[\s*/>|#-]*")

FILENAME = re.compile(r"^(\d{4})-.+\.md$")
HEADING = re.compile(r"^#\s+(?:(Product|Technical)\s+)?ADR-(\d{4})\s*:")
# Keep a Changelog: everything from the first released version heading down is
# frozen history, not rewritten to satisfy a lint rule.
RELEASED_HEADING = re.compile(r"^##\s+\[\d")


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return [p for p in out.split("\0") if p]


def read_text(path: str) -> list[str] | None:
    """Lines of a tracked text file, or None when it looks binary/unreadable."""
    try:
        with open(os.path.join(REPO_ROOT, path), "rb") as handle:
            raw = handle.read()
    except OSError:
        return None
    if b"\0" in raw[:BINARY_SNIFF_BYTES]:
        return None
    return raw.decode("utf-8", errors="replace").splitlines(keepends=True)


def wrapped_qualifier(line: str, match: re.Match, previous: str) -> str | None:
    """The namespace of a citation whose qualifier sits on the previous line.

    Returns "product"/"technical" so the caller can validate the number against
    that namespace exactly as it would an inline qualifier — a wrapped citation
    naming the wrong tree must still fail R4, not merely escape R5. None when
    this is a genuinely bare citation.

    Only counts when the number opens the line (allowing comment/list markers),
    so a wrapped citation is recognised while a bare one later on the same line
    is still reported.

    Known limit: this trusts any previous line ending in "product"/"technical",
    without confirming that word was introducing a citation. A line ending in
    either word for unrelated reasons, followed by one opening with a bare
    ADR-NNNN, would be accepted. Every wrap in the tree today is a genuine
    citation; tighten this if that stops being true.
    """
    prefix_end = CONTINUATION_PREFIX.match(line).end()
    if match.start() != prefix_end:
        return None
    carried = DANGLING_QUALIFIER.search(previous.rstrip("\n"))
    return carried.group(1).lower() if carried else None


def check_files(violations: list[str]) -> dict[str, set[str]]:
    """R1-R3. Returns {namespace: {numbers}} for the citation rules to use."""
    numbers: dict[str, set[str]] = {ns: set() for ns in NAMESPACES}
    for ns in NAMESPACES:
        directory = os.path.join(REPO_ROOT, "docs", "decisions", ns)
        by_number: dict[str, list[str]] = {}
        for name in sorted(os.listdir(directory)):
            match = FILENAME.match(name)
            if not match:
                continue
            number = match.group(1)
            by_number.setdefault(number, []).append(name)
            numbers[ns].add(number)

            path = f"{DECISIONS}/{ns}/{name}"
            with open(os.path.join(REPO_ROOT, path), encoding="utf-8") as handle:
                heading = handle.readline().rstrip("\n")
            found = HEADING.match(heading)
            if not found:
                violations.append(
                    f"{path}:1: [R3] heading must read "
                    f"'# {ns.capitalize()} ADR-{number}: <title>', got: {heading!r}"
                )
                continue
            qualifier, heading_number = found.group(1), found.group(2)
            if heading_number != number:
                violations.append(
                    f"{path}:1: [R2] heading number ADR-{heading_number} does "
                    f"not match filename number {number}"
                )
            if (qualifier or "").lower() != ns:
                violations.append(
                    f"{path}:1: [R3] heading qualifier must be "
                    f"'{ns.capitalize()}' to match docs/decisions/{ns}/, "
                    f"got {qualifier!r}"
                )

        for number, names in sorted(by_number.items()):
            if len(names) > 1:
                violations.append(
                    f"{DECISIONS}/{ns}/: [R1] {len(names)} ADRs share number "
                    f"{number}: {', '.join(names)} — renumber the later-dated "
                    f"file to the next free number (see .claude/CLAUDE.md)"
                )
    return numbers


def check_citations(violations: list[str], numbers: dict[str, set[str]]) -> None:
    """R4-R5 over every tracked text file."""
    shared = numbers["product"] & numbers["technical"]
    known = numbers["product"] | numbers["technical"]

    for path in tracked_files():
        lines = read_text(path)
        if lines is None:
            continue

        in_decisions = path.startswith(DECISIONS + "/")
        is_changelog = os.path.basename(path) == "CHANGELOG.md"
        frozen = False
        previous = ""

        for lineno, line in enumerate(lines, 1):
            if is_changelog and RELEASED_HEADING.match(line):
                frozen = True
            if frozen:
                previous = line
                continue

            for match in CITATION.finditer(line):
                qualifier, number = match.group(1), match.group(2)
                namespace = (qualifier or "").lower()
                if not namespace:
                    # A qualifier left at the end of the previous line by a
                    # wrap still names a namespace, and is validated as one:
                    # a wrapped citation naming the wrong tree fails R4 rather
                    # than merely escaping R5.
                    namespace = wrapped_qualifier(line, match, previous) or ""

                if namespace:
                    if number not in numbers[namespace]:
                        violations.append(
                            f"{path}:{lineno}: [R4] cites "
                            f"'{namespace} ADR-{number}' but no such file "
                            f"exists in docs/decisions/{namespace}/"
                        )
                    continue

                if number not in known:
                    violations.append(
                        f"{path}:{lineno}: [R4] cites ADR-{number}, which "
                        f"matches no file in docs/decisions/"
                    )
                elif number in shared and not in_decisions:
                    violations.append(
                        f"{path}:{lineno}: [R5] bare 'ADR-{number}' is "
                        f"ambiguous — product/ and technical/ both define "
                        f"{number}; write 'product ADR-{number}' or "
                        f"'technical ADR-{number}'"
                    )

            previous = line


def main() -> int:
    violations: list[str] = []
    numbers = check_files(violations)
    check_citations(violations, numbers)

    if violations:
        print("ADR numbering check failed:\n", file=sys.stderr)
        for violation in sorted(set(violations)):
            print(f"  {violation}", file=sys.stderr)
        print(
            f"\n{len(set(violations))} violation(s). "
            "See the ADR conventions in .claude/CLAUDE.md.",
            file=sys.stderr,
        )
        return 1

    total = sum(len(v) for v in numbers.values())
    print(f"ADR numbering check passed ({total} ADRs, no collisions, "
          "no ambiguous or dangling citations).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
