# Technical ADR-0045: German localization and the missing-translation gate

**Status**: accepted

**Date**: 2026-11-23

## Context

All UI text already lived in `values/strings.xml`, so localizing was a resource change plus an audit
(#1005). The maintainer chose German as the only initial language, a machine-drafted translation reviewed
in the PR, and a CI check for missing translations. What's New and the Play listing stay English (#1041).

Options for the check:
- Lint `MissingTranslation` only. It catches missing keys, but not a German string that drops or mistypes
  a `%1$d`. That crashes `getString(id, args)` or loses a value at runtime, and only on a German device.
- A custom script. It duplicates lint for missing keys and adds tooling outside Gradle.
- Lint for keys plus a JVM unit test for placeholders, plural quantities and array sizes.

Some user-visible dates also used fixed English-ordered patterns (`"MMM d, yyyy"`, `"EEE, MMM d"`), and
the plant-card countdown was hardcoded English in `DateUtils.formatCountdown`.

## Decision

- Ship `values-de/strings.xml`. English stays the default for every other locale.
- `MissingTranslation` and `ExtraTranslation` are pinned to **error** in `app/build.gradle.kts`, so `lintDebug`
  (CI's `test` job) fails when a string lacks its German entry. `translatable="false"` exempts genuine
  non-text tokens.
- `GermanTranslationParityTest` checks placeholders (per string, and per plural `other`; German `one` may
  drop the count but never add a placeholder), that German plurals are exactly `one` + `other`, and
  `string-array` sizes.
- Multi-field dates come from skeletons via `DateUtils.localizedPattern()`
  (`DateFormat.getBestDateTimePattern`), matching the existing away/sitter text. `DateUtils` returns
  structured values (`RelativeDate`, `Countdown`), and composables render them from resources.

## Consequences

- Every PR that adds a string must add the German text too. Contributors without German can copy the
  English text into `values-de` for the maintainer to correct, and lint still passes.
- `DateUtilsTest` now needs Robolectric, because the Android pattern generator isn't available on the plain JVM.
- English output is unchanged: the skeletons resolve to the same patterns for `en-US`.
- A further language means a `values-xx` file. The parity test is written for German and would need to
  generalize.
