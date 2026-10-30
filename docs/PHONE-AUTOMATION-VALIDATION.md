# Native phone validation — 2026-10-31

Baseline: upstream v0.2.5, `833166fd0171f922df17d4178427ea538532070d`.
Physical device: Redmi 23013RK75C, Android 15 / API 35, 1440 × 3200.
The separate `io.github.jackwener.dsh.phonecheck` debug package preserved the existing installed
DSH app. Tests used the production capability hub, native accessibility actions/gestures and
bundled ML Kit on the phone's main display. No emulator or browser was used.

## Results

| Check | Result |
| --- | --- |
| JVM unit tests | 148 passed, zero failures/errors/skips |
| JavaScript Node test runner | 119 passed, zero failures/skips |
| Standalone attachment regression | Passed against the staged engine bundle |
| Debug, instrumentation and Release APK builds | Passed |
| Release isolation | Native phone tools present; debug fixture absent from Release DEX |
| Connected functional suite | 24 passed, zero failures/skips; 128.236 seconds |
| Additional Calculator confirmation | Passed; independently observed `7+5` and `= 12` in native UI |
| Lint | 7 existing error occurrences, matching clean upstream v0.2.5; zero new errors |
| Whitespace validation | `git diff --check` passed |

The existing lint errors are `LocalContextGetResourceValueCall` in `AutomationScreen.kt` and
`ProvidersUi.kt`, and `PermissionImpliesUnsupportedChromeOsHardware` in the main manifest.
There are six unique findings and seven occurrences because one provider finding appears twice.
Lint is **not** reported as passing; these unrelated upstream errors were not suppressed.

## Connected coverage

The 24 tests exercise:

- Fresh selectors, actual text input, clicks, result assertions, duplicate-target rejection and
  rejection of a malformed later command before an earlier action can run.
- Per-child capability and unattended whitelist checks, unexpected app switches, new user turns,
  overall deadlines, direct coroutine cancellation and the actual capability bridge cancellation
  handler. Completed actions are not replayed.
- Native updates after 900 ms and 2400 ms, explicit condition waits, unmet wait timeouts, and
  stopping before another write when an action has no observed effect.
- Full 60-row lists in both directions, repeated values, output limits, invalid collection bounds
  and native paragraphs longer than the compact 120-character preview.
- Fresh OCR target taps, retired text ids, a changed canvas before tapping, a canvas changed while
  recognition is running, and continuously moving content that must not receive text coordinates.
- Reading Android Settings with a two-page cap (the recorded run actually returned one page and
  13 items); native Xiaomi Calculator buttons and a verified arithmetic result; single-tool versus
  compound-tool dispatch measurements.

The fixture is a real, non-exported debug Activity with Android widgets and a custom-drawn canvas.
The Settings and Calculator tests operate the installed system apps. The original input method
and accessibility configuration were verified restored after testing.

## Measurements

These are local native measurements, excluding LLM inference and network latency. Three runs
are enough to detect large regressions, not to establish a statistically significant speedup.

| Scenario | Observed result |
| --- | --- |
| Four individual native taps | 2230 / 2125 / 2202 ms; median 2202 ms; four caller tool invocations |
| One compound call containing four taps | 2070 / 2169 / 2175 ms; median 2169 ms; one caller tool invocation |
| Forward collection | 60 rows, 7 pages, 7 scroll attempts, 6468 ms |
| Backward collection | Same 60 rows in reading order, 7 pages, 7 scroll attempts, 6537 ms |
| 900 ms delayed native update | Condition observed and verified in 1540 ms |
| Continuously moving canvas | Stopped recognition in 3438 ms; zero text coordinates issued |

The four-tap native execution times are comparable. The compound call reduces caller tool
invocations from four to one (75%), while retaining Android-side checks and fresh observations.
The collection's last scroll attempt detects the end; both directions returned `scroll_no_effect`
with `truncated: false`, and the entire output matched the known 60-row source including repeats.

## Scope and limits

The tests verify one physical Redmi on the main display. Other Android versions/OEMs, Shizuku
virtual-display operation and live model-driven conversations were not part of this device run.
No model credentials were copied and no end-to-end LLM speedup is claimed. Native settling
cannot predict a future network response; explicit `wait`/`assert` remains necessary. OCR uses
sampled visual comparisons, and ordered text overlap does not provide database row identity.

See [usage and test commands](PHONE-AUTOMATION.md) to reproduce the suite.


## Additional installed daily-build acceptance — 2026-10-31

The original 24-test run above targeted the isolated feature APK. A separate acceptance run now
checks the **actually installed local integration APK**, `0.2.5-local.2` / versionCode 16, SHA-256
`8e40a9f890426f6f5bb5723dc1cd76e8675c6187e3ba5faec191d31e2af4e84c`.
Every test verifies that installed APK hash. The complete run passed **35/35 tests in 341.398 s**:
31 shared phone-feature cases, one local engine-recovery case, and three real model-driven cases.
The integration includes the provider/reasoning changes from PR #30 and a local engine-generation
lifecycle fix; it is not a standalone build of this PR. All 13 runtime files changed by the phone
features were compared byte-for-byte with this branch and matched.

| Feature | Evidence on the installed integration APK |
| --- | --- |
| Sequential native actions | One eight-step Clock navigation/assertion batch, 4617 ms, no explicit wait and no timer/alarm mutation. A real model independently completed four Clock navigation steps in one batch, 2571 ms. |
| Multi-screen collection | All 60 fixture rows in both directions, seven pages each, including repeated values; 7057/7012 ms. The real model collected two actual Settings home pages and 26 entries, 4254 ms. |
| Adaptive waiting | Direct taps without explicit wait: quick update 613 ms; a 900 ms delayed update returned with the result present in 1441 ms; no effect bounded at 1818 ms; continuously changing content bounded at 1843 ms with `screen_unstable`. The real model used one two-step batch with no wait, 1475 ms, one dispatch. |
| OCR frame validation | Real Settings: old ID and post-scroll frame both rejected without a tap; a fresh OCR target navigated. Canvas tests cover recognition-time changes, old-ID non-reuse and animation rejection. The real model used a fresh OCR ID once and verified count 1. |

All three real model cases returned zero tool errors and reached both SDK completion and the app's
idle state. A separate recovery check induced a failed engine start and confirmed its replacement
remained usable. That local engine lifecycle repair is outside this phone-feature PR.

The expanded **31-case shared suite is included in this PR**; its instrumentation APK compiles.
Local-only provider configuration and the four integration-specific cases are not included here.
Test setup waits for a focused, on-screen stable fixture after closing the previous Activity;
window/decor access runs on the main thread. It restores the prior settings after each case and
refuses configured `-local.` daily builds, which require the local backup/exact-APK opt-in suite.
Failed/interrupted fixture runs were retained and excluded from the complete passing run.

The new four-tap measurements were 2306/2305/2335 ms individually (median 2306 ms), versus
2427/2395/2392 ms in one batch (median 2395 ms). These samples **do not show a native execution
speedup**; the verified reduction is four caller tool invocations to one. No end-to-end LLM/network
speedup is claimed. The final integration also passed 155 JVM tests; its lint errors match the
seven occurrences on clean v0.2.5, with no added errors.

### Observed Redmi limitation

The Settings “更多连接” subpage does not expose its active application root to DSH's accessibility
service. An independent test observer sees `accessibilityDataSensitive=true`; DSH's current fallback
reports the launcher package. A batch stops with `app_changed` and does not execute later actions.
**Continuous operation inside that subpage is not accepted as working.** A fresh OCR tap can enter
it, with the destination verified independently; that does not prove DSH can read the destination.
Other ROMs, virtual displays and arbitrary app flows remain outside this acceptance scope.
