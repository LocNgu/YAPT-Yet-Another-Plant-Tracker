---
description: ADR authoring, numbering, citation and amendment rules
paths:
  - "docs/decisions/**/*"
  - "tools/check-adr-numbering.py"
  - "tools/test_*.py"
---

# Architecture Decision Records — full rules

- **Where and when:** decisions live in `docs/decisions/{product,technical}/`. **Consult the relevant ADR before working in a covered area**, and never refactor a pattern a technical ADR describes without a superseding decision.
- **New ADRs:** a PR that records a significant new product/technical decision adds an ADR from `docs/decisions/template.md` (Status `accepted`, next number in its folder).
- **Contradictions:** if a request contradicts an ADR, name the ADR and its rationale and get human confirmation first.
- **Finalized ADRs are not rewritten.** The only permitted edits are:
  - the Status line: a supersession (`superseded by [ADR-XXXX](file.md)`), or an amendment (`accepted — <clause description> amended by [ADR-NNNN](file.md)`; technical ADR-0021 is the in-repo example);
  - non-substantive corrections (ADR number, heading format, citation targets);
  - the one-time removal of a legacy "Amended by ADR-XXXX" Consequences note when the equivalent Status-line clause is added (technical ADR-0029). The note and the clause must never both exist, or editing one leaves the other stale.

  Context/Decision/Consequences prose otherwise stays untouched.
- **Namespaces:** `product/` and `technical/` number **independently**, so a number can exist in both.
  - Headings read `# Product ADR-NNNN: Title` or `# Technical ADR-NNNN: Title`, matching the directory.
  - Prose cites with the qualifier (`product ADR-NNNN` / `technical ADR-NNNN`) whenever the number exists in both.
  - A bare `ADR-NNNN` is safe only where the number is unique to one namespace, or **inside `docs/decisions/`**, where it means the citing file's own directory (e.g. a Status-line amendment note in a `technical/` ADR). Cross-namespace citations between ADRs still need the qualifier.
- **Collisions:** when two files land on the same `NNNN` in one namespace, the **later-dated file is renumbered** to the next free number; the earlier one keeps its number (#740).
- **Checking:** `tools/check-adr-numbering.py` (CI-enforced) checks numbering and citations; `git add` new files before running it.
