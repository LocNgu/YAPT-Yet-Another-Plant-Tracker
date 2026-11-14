# Product ADR-0085: The sitter PDF has visit pages only

**Status**: accepted

**Date**: 2026-11-14

**Amends**: [ADR-0065](0065-away-mode.md) (step 4's last "Please don't water these" page).

## Context

Issue #1030. Product ADR-0065 step 4 ended the sitter PDF with a **Please don't water these** page of resting
plants (dormant or suspended), to warn the sitter off plants that look dry but are resting. The same step says
there is no "no watering needed" list because at this scale it is noise. Plants that simply are not due yet are
not shown either, so a "don't water" list of only the resting ones makes the sitter wonder about everything else
that is missing from the document. The alternative of keeping the page and also listing the not-due plants was
rejected for the same noise reason.

## Decision

The sitter PDF has visit pages only: "water these". Resting plants do not appear on it unless the owner ticks one
in the picker (product ADR-0077), in which case it is a normal visit-page plant. The **Share as text** fallback
drops its resting section too, so it keeps the PDF's structure, and the share caption no longer mentions pages to
leave dry. The owner-facing parts are unchanged: the picker's collapsed Resting section and the summary's
"+ N resting" count.

## Consequences

- `SitterList` carries visit rooms only; `SitterPdfLayout.PageKind` and the resting pagination are gone.
- Product ADR-0077's reference to an added resting plant being "dropped from Please don't water these" is moot;
  adding a resting plant to the visit works as before.
- A sitter is no longer told which plants to leave dry; the PDF's footer "Only water the plants on this page" and
  the "When in doubt, leave it dry" tip carry that message.
