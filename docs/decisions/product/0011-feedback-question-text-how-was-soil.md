# Product ADR-0011: Feedback question text revised to "How was the soil?"

**Status**: superseded by [ADR-0027](0027-check-reminders-still-moist-action.md)
**Date**: 2026-06-03  
**Supersedes**: ADR-0009

## Context

ADR-0009 updated the feedback chip labels to soil-state language ("Still wet", "Just right", "Too dry") and set the question text to "What did you find?". During a subsequent string-extraction sweep the question text in the implementation read "How was the soil?", which is more specific and directly prompts the soil-state observation the labels describe.

## Decision

The feedback question text is **"How was the soil?"**. This is more concrete than "What did you find?" — it names the thing the user should check (the soil), which directly maps to the three chip labels. "What did you find?" is valid English but is ambiguous about what to observe.

The chip labels ("Still wet", "Just right", "Too dry"), enum values (`TOO_SOON`, `JUST_RIGHT`, `TOO_LATE`), and all business logic are unchanged.

## Consequences

- The `care_log_prompt_how_was_soil` string resource value remains "How was the soil?".
- No DB migration or backup-format change required.
- ADR-0009 chip labels and enum semantics are fully preserved.
