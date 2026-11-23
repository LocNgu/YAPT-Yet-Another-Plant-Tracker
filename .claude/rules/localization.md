---
description: German translation (values-de), the missing-translation gate, and locale-aware date formatting
paths:
  - "app/src/main/res/values/strings.xml"
  - "app/src/main/res/values-*/strings.xml"
  - "app/src/main/kotlin/com/yapt/planttracker/util/DateUtils.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/util/CountdownText.kt"
  - "app/src/test/kotlin/com/yapt/planttracker/resources/**"
---

# Localization rules (#1005, technical ADR-0045)

## Languages
- English (`values/strings.xml`) is the default; **German** (`values-de/strings.xml`) is the only translation.
  Any other device language falls back to English.
- Out of scope: `WhatsNewContent` and the Play listing stay English (#1041, on hold).
- The German file started as a machine draft reviewed by the maintainer. Address the user with informal "du",
  keep button labels short, and reuse the existing terms (Pflege, gießen, düngen, umtopfen, Ruhephase,
  Pflanzenfriedhof, Abwesenheitsmodus, Pflanzensitter, …). grep `values-de` before inventing a new one.

## Every new string ships in both files
- `lintDebug` fails on `MissingTranslation` and `ExtraTranslation` (pinned to error in `app/build.gradle.kts`).
  Add the German entry in the same PR, or mark a genuinely untranslatable string `translatable="false"`
  (brand/URL tokens such as `sitter_brand_url`). Copying a string unchanged into `values-de` is also fine.
- `GermanTranslationParityTest` (plain JVM, reads both XML files) also fails on: a string whose placeholders
  differ from English, a German plural without exactly `one` + `other`, a German `other` whose placeholders
  differ from English `other`, a German `one` with a placeholder English `other` lacks, or a `string-array`
  with a different item count.
- German `one` renders only for exactly 1, so it may drop the count ("Jeden Tag" for "Every %d day").
  It must never add a placeholder.

## Locale-aware formatting
- **Never hardcode an English-ordered date pattern** (`"MMM d, yyyy"`, `"EEE, MMM d"`) for user-visible text.
  Use `DateUtils.formatDate()` / `formatWeekdayDate()` / `formatMonthYear()`, or build a pattern from a skeleton
  with `DateUtils.localizedPattern("yMMMd")` (`android.text.format.DateFormat.getBestDateTimePattern`).
  The away/sitter texts already did this. Because of it, `DateUtilsTest` runs under Robolectric.
- Single-field patterns (`"MMM"`, `"MMMM"`), `"HH:mm"`, `getDisplayName(TextStyle, locale)` and
  `String.format(Locale.getDefault(), …)` are already locale-correct. `"yyyy-MM-dd"` backup file names are
  intentionally fixed.
- Countdowns ("Overdue by 2 days" / "Due today" / "In 3 days") come from `countdownText()` over
  `DateUtils.countdown()`, like `relativeDateText()` over `relativeDate()`. `DateUtils` never returns
  display text.
