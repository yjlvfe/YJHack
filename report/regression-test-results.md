# Regression test results — final cleanup

Baseline: tag `stable-before-final-cleanup` (jar `706daa61…`).
Cleaned:  branch `cleanup/final-project-cleanup` (jar `5398ab19…`).

## A. Automated checks — ALL PASS ✅

| Check | Result |
|---|---|
| `./gradlew clean build` | BUILD SUCCESSFUL |
| `./gradlew test` | 19 tests, 0 failures, 0 errors, 0 skipped |
| `test` task state | runs real sources (no more `NO-SOURCE`) |
| Jar compiled classes | 32 (== baseline 32) |
| Duplicate classes in jar | none |
| Client entrypoints | 6 (identical set to baseline) |
| Jar resources | identical (MANIFEST, en_us.json, fabric.mod.json) |
| RightClickPolicy in jar | present (RightClickPolicy + $Kind) |
| All six modules in jar | present |
| `Dev/bin` | absent (did not return) |
| `.class` files under `src` | none |
| Injected `net/minecraft` classes | none |
| Gradle deprecation warnings | resolved (only the documented HudRenderCallback note remains) |

### JUnit breakdown (19)
- `RightClickPolicyTest` — 8: Fire Charge + throwables + every bucket are single-press;
  blocks and ordinary items are not; null-safe `classify`/`shouldAutoRepeat`; 3 Kind values.
- `RisingEdgeModelTest` — 5: 20 presses = 20 uses; hold = 1; release+press = 2; 5 holds = 5;
  no press = 0.
- `ConfigNormalizationTest` — 6: Min/Max CPS clamp (AutoLeft, AutoRight), version bump preserves
  values, AimAssist NaN/float clamp, Tracker range clamp, NinjaBridge migration.

### Per-batch discipline
Every deletion/refactor batch was its own commit, each followed by a build (and, once the
suite existed, tests) before moving on. Commits: `cb2c1e3` (reports) → `f157bd7` (dead code) →
`0ce86dc` (tests) → `0955a8e` (gradle) → `a1bb9c8` (GUI text) → `be0a0d7` (GUI split).

## B. In-game Lunar acceptance — NOT RUN (user to perform) ⏳

These require the game and cannot be executed from this environment. They are **NOT** marked
passed. The cleaned jar is structurally equivalent to the baseline you already verified on
Lunar (same entrypoints/resources/module+policy+screen+config classes; only theme/widget
classes were relocated), so no behavioural change is expected — but this must be confirmed
in-game before calling the cleanup done.

Copy `./YJHack-1.21.5.jar` (SHA-256 `5398ab19…`) into the Lunar mods folder and check:

- [ ] Game launches; no "Not Responding".
- [ ] Enter a world.
- [ ] Open/close the GUI 20× — no freeze, no stuck state.
- [ ] Players remain visible behind the (translucent, un-blurred) panel.
- [ ] Dashboard shows 3-line cards; hovering a card shows the detail tooltip (key + extras).
- [ ] Text feels less crowded than before.
- [ ] Each settings screen opens; Save and Reset work; "Settings saved" toast shows.
- [ ] Restart the game — values persist.
- [ ] Fire Charge: one press = one use; holding = one use; release+press = new use.
- [ ] Blocks still place at CPS in Block Mode.
- [ ] AutoLeft, AutoRight, NinjaBridge, AimAssist behave as before.
- [ ] Tracker HUD + red box render; drag-to-move HUD works.
- [ ] Change server/world — no stuck keys, no leftover synthetic press.

If any check fails, roll back with `git checkout stable-before-final-cleanup` (or reinstall the
frozen `report/YJHack-1.21.5.jar`, `706daa61…`) and report the failing step.
