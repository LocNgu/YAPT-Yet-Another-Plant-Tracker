# Handover: PR #976 / issue #350 (createComposeRule v2 migration)

## State
- Branch `claude/issue-350` (PR #976 → `develop`), latest commit `baf4b58`.
- Change: 38 androidTest files now import `androidx.compose.ui.test.junit4.v2.createComposeRule`
  (was `androidx.compose.ui.test.junit4.createComposeRule`). Exists in compose-ui-test 1.12.1 (BOM 2026.09.00).
- CI: 269/270 instrumented tests pass, plus unit tests, lint, detekt, CodeQL. **One failure remains.**
- Not merged; issue #350 is labelled `in-progress`. Nothing skipped or disabled.

## The failing test
`ui/screens/watering/WateringOverviewScreenTest.aGroupOfSixShowsFiveRowsAndShowAllRevealsEveryRow`
(androidTest, instrumented shard 1).

Steps: 6 plants in one group → 5 rows + "Show all 6" button (LazyColumn in
`WateringOverviewContent.kt`; `expanded` is `rememberSaveable` state, button sets
`expanded.value = expanded.value + groupKey`). Test scrolls to "Show all 6", clicks it, then expects row 6
(`watering_overview_row_6`) to exist.

Failure under v2: row 6 never appears. The list semantics showed 7 composed children
(header + 5 rows + button) with the scroll at max, so the group looks **unexpanded after the click**.
- Run 1 (before my change to the test): `performScrollTo()` on row 6 → "could not find any node".
- Run 2 (`baf4b58`, `onNode(hasScrollAction()).performScrollToNode(hasTestTag(...))`):
  "No node found ... in scrollable container". Same cause, so scrolling is not the problem.

v2 swaps the Unconfined test dispatcher for a StandardTestDispatcher. The old rule passed this test.

## Reproduce locally
Needs an emulator/device (API 26+; CI uses API 35 AVD).
```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yapt.planttracker.ui.screens.watering.WateringOverviewScreenTest
# only the failing method:
#  ...arguments.class=com.yapt.planttracker.ui.screens.watering.WateringOverviewScreenTest#aGroupOfSixShowsFiveRowsAndShowAllRevealsEveryRow
```
Report: `app/build/reports/androidTests/connected/debug/index.html`.
To confirm the v2 cause, flip the import in that one file back to
`androidx.compose.ui.test.junit4.createComposeRule`; it should pass.

## Things to try (untested; I had no emulator)
1. Add `composeTestRule.waitForIdle()` (and/or `composeTestRule.mainClock.advanceTimeByFrame()`) right after
   `performClick()` on "Show all 6", then assert `onNodeWithText("Show all 6").assertDoesNotExist()` first to see
   whether the click registers at all.
2. Check whether `performScrollTo()` followed immediately by `performClick()` clicks at stale coordinates under
   the standard dispatcher (scroll not settled). Try `waitForIdle()` between them.
3. Dump the tree after the click: `composeTestRule.onRoot().printToLog("DBG")`.
4. If the click truly doesn't reach `onClick`, check the `TextButton` is not partly obscured when scrolled to
   the bottom edge (contentPadding bottom = 16.dp).

Follow CLAUDE.md's two-strikes rule: find the mechanism before pushing more variants, and keep assertions on
user-visible semantics.

## Finishing
- Pre-push gate: `./gradlew detekt lintDebug compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin`.
- Push to `claude/issue-350`; CI re-runs both instrumented shards.
- The cloud session could not run `testDebugUnitTest`/`lintDebug` (robolectric jar download blocked); CI
  passed them.
- Delete this file before merging.
