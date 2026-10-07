# Product ADR-0062: Plant Detail's tabs replace the + FAB; Add Care Log becomes edit-only; Note is retired

**Status**: accepted

**Date**: 2026-10-06

## Context

Issue #532 (a follow-up to #436). Since the Plant Detail tabs (technical ADR-0018, product ADR-0043) and the Home
tab (product ADR-0060), most care actions already have an in-pane entry point: Water, Fertilize, Photo and Repot
each log in place through `QuickLogUseCase`. The `+` FAB and the full Add Care Log form stayed as the only way to
log what had no pane (Prune, Note) and to log with notes, an amount or feedback. Product ADR-0060 explicitly left
both "not changed" until this issue.

Keeping the FAB had costs. It was a second, divergent create path (its own duplicate guard, side effects and, until
technical ADR-0030, its own copy of the adaptive-watering logic). It overlapped the in-pane actions, it needed a
reserved snackbar offset, and it let a user create a type the app no longer wants to produce (Mist, product
ADR-0061; Note, here).

Alternatives considered:

- **Keep the FAB and the create form, just add a Prune tab.** Rejected: it leaves two create paths for WATER,
  FERTILIZE, PHOTO and REPOT, and the create-mode branches that mirror `QuickLogUseCase`.
- **Keep Add Care Log's type chips while editing.** Rejected: an edit could then retype a log, including into a
  retired type (NOTE, MIST), or turn a system-written CUSTOM/CHECK row into something else.
- **Convert existing NOTE rows to another type.** Rejected for the reason product ADR-0061 gave for MIST: it
  rewrites user data and needs a migration and a backup-version story, and nothing about reading them is broken.

## Decision

**Fertilize is no longer gated on a fertilizing interval (#532, part 1).** `FertilizeDueActionRow` always shows on
Home and the Fertilize tab. A liquid-fertilizer plant with no interval therefore shows "Water + Fertilize". Home's
summary card still shows the Fertilizing rows only when an interval is set. This amends the gating clause and the
liquid edge case of product ADR-0060.

**Prune gets its own tab (#532, part 2).** `PlantDetailTab.PRUNE` sits after Repot, behind the chevron: Home ·
Water · Fertilize · Photo | Repot · Prune · Reminders · Issues. `COLLAPSED_TAB_COUNT` stays 4. Prune has no due
state, so the attention badge and the orphan-reset target (Home) do not change. The tab is built like Repot: a
Prune button opens `CareDatePickerBottomSheet`, then `PlantDetailViewModel.quickPrune` logs through
`QuickLogUseCase.quickLog` and shows a snackbar. Below it are a `TabInsightsCard`, the plant's own PRUNE logs (edit
and delete) and an empty state. Prune is not duplicate-guarded and has no notes field. This amends the tab set and
order of product ADR-0043 and product ADR-0060.

**Add Care Log is edit-only, and the FAB is removed (#532, part 3).** `careLogId` is a required route argument; the
`careType` route argument, `preselectCareType`, `prepareNewLog()` and every create-mode branch are deleted, because
those side effects already live in `QuickLogUseCase`. The type chips are replaced by a read-only type header, so an
edit cannot change a log's type or write a retired type. The per-type fields, the same-day WATER/FERTILIZE
duplicate guard on edit, the PHOTO "photo required" Save rule and the cover-photo update stay. The `+` FAB is
deleted and the `SnackbarHost`'s FAB offset with it. This supersedes the "not changed" clause of product ADR-0060.
It also supersedes the type-picker, `careType=MIST` route-argument and edit-time Mist-chip clauses of product
ADR-0061 (a stored MIST log now opens with a read-only "Misted" header and keeps its type).

**Note is retired as a care type for new logs (#532, part 4).** `CareType.NOTE` follows the CHECK pattern (product
ADR-0039) and the MIST pattern (product ADR-0061): the enum constant, `EnumResources`'s label and icon, and the
chart marker colour stay, so existing NOTE rows still load, show in Home's care history, can be edited and
deleted, and `.yapt` backups round-trip unchanged. No code path writes a new NOTE: the bulk bar never offered it,
the quick-log surfaces never create one, Add Care Log only edits, and the demo data no longer seeds one. No Room
schema, migration or backup-format change.

**Accepted trade-offs.**

- Notes, amount, the "plant needed it" flag and a non-default fertilizer type cannot be set while logging. They can
  only be added by editing the log afterwards.
- A log's type cannot be changed. Delete it and log it again instead.
- A user who wants a free-text entry no longer has one as a stand-alone log. The notes field of a PHOTO or other
  log, a custom reminder, or a plant issue carry free text instead.
- The Photo quick action's no-notes trade (product ADR-0038) is no longer a gap with a canonical alternative: a
  new photo log gets notes only by editing it afterwards. This amends that clause of product ADR-0038.

**Related technical decision.** Add Care Log no longer observes WATER logs or hands a watering-interval suggestion
back to Plant Detail; `QuickLogUseCase` is the sole observation call site. That is recorded in technical ADR-0037,
which supersedes technical ADR-0006 and technical ADR-0033.

## Consequences

- Every new log comes from an in-pane action through one shared use case, so the two parallel create paths and the
  hand-mirrored side effects are gone.
- The user-visible type set for new logs is Water, Fertilize, Prune, Repot, Photo (and custom-reminder completions).
  Future code that offers a list of care types to create must leave NOTE and MIST out; code that reads or displays
  care types must keep handling both.
- Product ADR-0061's consequence that "a NOTE can carry the same information in free text" no longer holds for new
  logs; it is amended alongside the other ADR-0061 clauses above.
- Product ADR-0060's, ADR-0043's, ADR-0038's and ADR-0061's Status lines carry the amendments (technical ADR-0029
  form). Released changelog and What's New entries are history and are not rewritten.
- Older ADRs (technical ADR-0005, ADR-0018, ADR-0022, product ADR-0034) that describe the FAB as a live element
  describe the app as it was when they were written; they are not individually amended.
