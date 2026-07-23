# PROJECT KNOWLEDGE BASE — YJHack-1.21.5 (Dev/)

**Generated:** 2026-07-23
**Project:** YJHack-1.21.5 (Minecraft 1.21.5 — Yarn mappings)
**Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4

## OVERVIEW
Fabric client mod — 6 modules bundled into one JAR.

> **REPAIRED 2026-07-23:** The project is now **100% source-based**. All six modules
> (`modgui`, `tracker`, `aimassist`, `autoleft`, `autoright`, `ninjabridge`) compile from
> `src/main/java`. The old `bin/main/` pre-compiled-blob approach and the
> `extractGoodModClasses`/`mergeGoodModClasses` build hacks were **removed** — they were the
> root cause of the "can't edit settings" and mismatched-class crashes. `modgui`, `aimassist`,
> and `tracker` source was recovered from the working v1.0.0 reference JAR
> (`report/YJHack-1.21.5.jar`) via tiny-remapper (intermediary→Yarn) + Vineflower, then hardened.
> Build is now a plain `fabric-loom` source build. See `report/comprehensive-repair-report.html`.

## STRUCTURE
```
Dev/
├── src/main/java/.../                 # Source code
│   ├── autoleft/                      # AutoLeftClient.java (source)
│   ├── autoright/                     # AutoRightClient.java (source)
│   └── ninjabridge/                   # NinjaBridgeClient.java + mixin (source)
├── src/main/resources/                # fabric.mod.json, mixins, assets
├── bin/main/                          # Pre-compiled .class (aimassist, tracker, modgui)
│   ├── com/masteryj/aimassist/        # Original AimAssist from YJHack.jar
│   ├── com/masteryj/tracker/          # Original Tracker (no VertexRendering crash)
│   └── com/masteryj/modgui/           # Original GUI from YJHack.jar, ASM-patched:
│       ├── ModGuiClient.class         # Unchanged
│       ├── ModGuiClient$AutoLeftConfig.class   # meanCps→minCps (float), stdDev→maxCps (float)
│       ├── ModGuiClient$AutoRightConfig.class  # same rename
│       ├── ModGuiClient$AutoLeftScreen.class   # meanCpsField→minCpsField, labels changed
│       ├── ModGuiClient$AutoRightScreen.class  # same rename
│       └── ... (rest unchanged)
├── build.gradle                       # Loom build + bin/ injection + mixin inject
├── gradle.properties                  # MC 1.21.5, loom 1.10.5, loader 0.16.10
├── settings.gradle
├── gradlew / gradle/                  # Gradle wrapper
└── AGENTS.md                          # ← this file
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Auto-left click logic | `src/.../autoleft/AutoLeftClient.java` | Hold physical mouse → auto-click. CPS: random(minCps, maxCps) |
| Auto-right click logic | `src/.../autoright/AutoRightClient.java` | Hold physical mouse → auto-click. Block burst mode. CPS: random(minCps, maxCps) |
| GUI | `bin/.../modgui/` | Original compiled classes from YJHack.jar, ASM-patched field names only |
| Mixin bridge (gui) | `src/.../ninjabridge/` | GUI hooks, mixin config |
| Pre-compiled aim assist | `bin/.../aimassist/` | No source — from original working JAR |
| Pre-compiled tracker | `bin/.../tracker/` | No source — from original working JAR, fixed VertexRendering crash |
| Entrypoints | `src/main/resources/fabric.mod.json` | 6 client entrypoints in load order |
| Build pipeline | `build.gradle` | `jar { with bin/ via copy } → remapJar → mergeGoodModClasses → copyJar` |

## CONVENTIONS
- **Monolithic files**: Single `XxxClient.java` per module with inner `Config` class
- **Config format**: Gson JSON → `<mod-id>.json` in Minecraft's config dir (auto-reloads on file change)
- **Keybinding**: Direct GLFW calls (`GLFW.glfwGetKey`) + configurable key codes, NOT Fabric `KeyBinding` API
- **Mapping**: Yarn for development; intermediary in final JAR (`remapJar` handles conversion)
- **Block caching**: `IdentityHashMap<Block, Boolean>` for fast building-block checks
- **Client-only**: `"environment": "client"` in fabric.mod.json
- **CPS**: Simple random integer between minCps and maxCps (no Gaussian, no fluctuation factor)

## KEY DETAILS — AutoLeft/AutoRight
- **Must physically hold mouse button**: `isMouseDown(client, 0/1)` using GLFW
- **AutoLeft**: `triggerLeftPulse()` → `holdLeftMouse()` + `KeyBinding.onKeyPressed()` then `releaseLeftHold()` at end of tick
- **AutoRight**: `clickMouseKey()` → `setKeyPressed(true)` + `onKeyPressed()` + `setKeyPressed(false)` (instant)
- **CPS**: `minCps + random.nextInt(maxCps - minCps + 1)`, delay = `ceil(1000/cps)` ms
- **No meanCps/stdDev** — completely removed from modules AND GUI. CPS uses `randomInt(minCps, maxCps)` only

## KEY DETAILS — GUI (ModGuiClient)
- **NOT source-compiled** — uses original compiled classes from YJHack.jar in `bin/main/`
- Fields `meanCps`→`minCps`, `stdDev`→`maxCps` ASM-patched in bytecode (both remain `float` type to avoid breaking bytecode)
- GUI labels: "Mean CPS"→"Min CPS", "Std Deviation"→"Max CPS"
- Default values patched: AutoLeft (11→8, 1.5→16), AutoRight (18→14, 3→28)
- All other GUI classes (AimAssist, Tracker, NinjaBridge, MainScreen, BaseScreen, etc.) are 100% original, unchanged

## BUILD PIPELINE (post-repair — plain Fabric source build)
1. `compileJava` — compiles all 6 modules from `src/main/java`
2. `processResources` — expands `${version}`/`${loader_version}` in `fabric.mod.json`
3. `jar` → `remapJar` — remaps Yarn→Intermediary (standard loom)
4. `copyJar` — copies result to `../YJHack-1.21.5.jar`

(The former `extractGoodModClasses`/`mergeGoodModClasses`/`bin` injection steps were deleted.)

## ANTI-PATTERNS (THIS PROJECT)
- **No `@SuppressWarnings`** — codebase is clean of forced suppressions
- **No System.out** — no print debugging left in source; use the per-module SLF4J `LOGGER`
- **No empty `catch {}`** — config I/O logs via `LOGGER.warn`
- **`bin/main/` no longer exists** — everything is real source in `src/main/java`; edit source directly
- **Keep GUI config `configVersion` >= module version** so module `normalize()` never wipes user settings
- **CPS is `randomInt(minCps, maxCps)`** — no meanCps/stdDev anywhere

## COMMANDS
```bash
cd /home/masteryj/Desktop/Mod Maker/YJHack-1.21.5/Dev
./gradlew build                       # Build → copies YJHack-1.21.5.jar one dir up
./gradlew clean build                 # Clean rebuild
./gradlew runClient                   # Launch MC (requires assets)
./build.sh <PROJECT_NAME>            # Shell wrapper for single-module Eclipse builds
./build_all.sh                        # Build all 6 modules sequentially via shell
```

## NOTES
- **No git repo** — no `.gitignore`, no version control
- `ninjabridge.mixins.json` has empty `client: []` — mixins entry injected by `mergeGoodModClasses` task
- `clean` deletes `YJHack-1.21.5.jar` from the output dir
- Original YJHack.jar at `/home/masteryj/Desktop/Mod Maker/YJHack-1.21.5/YJHack.jar` — master reference
- modgui comes from `bin/main/` (patched originals), NOT from source compilation
- Gson writes `minCps: 8.0` (float from GUI config), module reads `int minCps` — Gson handles float→int conversion automatically
- `bin/main/assets/modgui/lang/en_us.json` is from original JAR (says "HelpNoob Core") — NOT used; source resources take precedence
- Eclipse `.factorypath` lists mixin processors: sponge-mixin 0.15.4, ASM 9.7.1, mixinextras 0.4.1
- `.debug-journal.md` tracks repair history — AimAssist range (49 vs 64) and Tracker rendering fixes