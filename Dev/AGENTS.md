# PROJECT KNOWLEDGE BASE — YJHack-1.21.5 (Dev/)

**Generated:** 2026-07-23 (repair v2)
**Project:** YJHack-1.21.5 (Minecraft 1.21.5 — Yarn mappings)
**Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4

## OVERVIEW
Fabric client mod — 6 modules bundled into one JAR.

> **100% SOURCE BUILD (verified 2026-07-23).** `Dev/bin/` does **not** exist. `build.gradle`
> is a plain `fabric-loom` source build — no `bin/main` injection, no
> `extractGoodModClasses`/`mergeGoodModClasses`. All six modules compile from
> `src/main/java`. Verified by `find Dev/bin` (empty), `grep` on build.gradle (only
> comments), and `jar tf` on the output (each class once, no duplicates). Any older
> doc claiming ModGui/Tracker/AimAssist run from pre-compiled `bin/` blobs is STALE.

## STRUCTURE
```
Dev/
├── src/main/java/com/masteryj/
│   ├── aimassist/AimAssistClient.java      # close-range aim (tick), typed saveConfigStatic
│   ├── autoleft/AutoLeftClient.java        # left auto-click (hold physical LMB)
│   ├── autoright/AutoRightClient.java      # right auto-click + block burst + single-press items
│   ├── autoright/RightClickPolicy.java     # NEW: item classification (single-press vs block vs pass-through)
│   ├── modgui/ModGuiClient.java            # REDESIGNED GUI: entrypoint + screen graph (no vanilla blur, typed config)
│   ├── modgui/theme/YjTheme.java           # GUI kit: colours, metrics, panel/pill, fmt/keyName/encodeMouse
│   ├── modgui/component/                    # custom widgets: ToggleSwitch, ThemeSlider, KeybindButton
│   ├── ninjabridge/NinjaBridgeClient.java  # auto-sneak bridge helper (GLFW polling)
│   └── tracker/TrackerClient.java          # hidden-enemy HUD + red box (world render)
├── src/test/java/com/masteryj/             # JUnit (fabric-loader-junit): RightClickPolicy, rising-edge, config
├── src/main/resources/                     # fabric.mod.json, assets/modgui/lang
├── build.gradle                            # plain fabric-loom build + copyJar → ../YJHack-1.21.5.jar
├── gradle.properties                       # MC 1.21.5, loom 1.10.5, loader 0.16.10
└── AGENTS.md                               # ← this file
```
No `bin/`. No mixins JSON (NinjaBridge is pure `ClientModInitializer` + GLFW polling).

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Auto-left logic | `autoleft/AutoLeftClient.java` | Hold physical LMB → auto-click. CPS: `randomInt(minCps,maxCps)` |
| Auto-right logic | `autoright/AutoRightClient.java` | Rising-edge single-press for discrete items; block burst only in Block Mode |
| Right-click classification | `autoright/RightClickPolicy.java` | `classify(stack,user)` → SINGLE_PRESS / BLOCK / PASS_THROUGH |
| GUI | `modgui/ModGuiClient.java` (+ `modgui/theme/`, `modgui/component/`) | `YjScreen` base + screens in ModGuiClient; kit in `theme/YjTheme`; widgets in `component/`; typed config bridge |
| Aim assist | `aimassist/AimAssistClient.java` | ≤3.5-block apply, FOV gate, raycast visibility, S-curve smoothing |
| Tracker HUD + box | `tracker/TrackerClient.java` | HudRenderCallback (deprecated, kept intentionally) + WorldRenderEvents.BEFORE_DEBUG_RENDER. Tracked set computed once per tick; render draws the box from that snapshot (no per-frame rescan) |
| Tests | `src/test/java/com/masteryj/` | `./gradlew test` — 20 JUnit via fabric-loader-junit (RightClickPolicy, rising-edge, config normalize incl. CPS cap) |
| Entrypoints | `src/main/resources/fabric.mod.json` | 6 client entrypoints |

## CONVENTIONS
- **One `XxxClient.java` per module** with a public inner `Config` class. The GUI is split into `modgui/ModGuiClient` (entrypoint + screen graph), `modgui/theme/YjTheme` (visual kit) and `modgui/component/*` (custom widgets).
- **Config format**: Gson JSON → `<mod-id>.json` in the config dir; module auto-reloads only when the file's mtime changes (never on a timer alone).
- **Keybinding**: direct GLFW polling (`GLFW.glfwGetKey`/`glfwGetMouseButton`) with configurable codes; codes ≥ 1000 encode mouse buttons (`1000 + button`).
- **CPS**: `minCps + random.nextInt(maxCps-minCps+1)`, delay = `ceil(1000/cps)` ms. No Gaussian, no fluctuation. `normalize()` clamps min/max to `[1, MAX_SAFE_CPS=40]` — the same ceiling the GUI slider enforces — so a hand-edited file cannot request packet-spam click rates.
- **Typed config bridge**: the GUI edits each module's OWN `Config` type and pushes it via the module's `applyRuntimeConfig(cfg)` (live) + `saveConfigStatic(cfg)` (file). No reflection, no field-name copying between different objects.

## KEY DETAILS — AutoRight / Fire Charge (RightClickPolicy)
- `RightClickPolicy.classify(stack,user)`:
  - **SINGLE_PRESS** — Fire Charge (`fire_charge`), Ender Pearl, Snowball, Egg, splash/lingering potion, Wind Charge, XP bottle, Ender Eye, Fishing Rod, Trident, Bow, Crossbow, buckets, and any `UseAction` of BOW/CROSSBOW/SPEAR/TOOT_HORN. Classified by **registry id** (mapping-stable) + use-action.
  - **BLOCK** — any `BlockItem`. CPS burst **only** while Block Mode is on.
  - **PASS_THROUGH** — everything else: vanilla input untouched.
- **Single-press rule**: on the physical rising edge the mod does nothing (vanilla fires exactly one use); while held it forces the use key released so vanilla's `itemUseCooldown` loop cannot repeat; a new use needs RELEASE→PRESS. No synthetic repeated clicks. Verified by a headless rising-edge model (20 presses = 20 uses, hold = 1 use).
- On disable / GUI open / world exit: `resetRightAutoClickState()` forces the use key up and clears edge state — nothing is left pressed.

## KEY DETAILS — GUI (ModGuiClient, redesigned v2)
- **No vanilla blur/darkening**: `YjScreen.renderBackground()` is overridden and never calls `super.renderBackground()` (which runs `applyBlur()` + `renderDarkening()`). Only a light full-screen tint (~0x22 alpha) is drawn; panels are translucent glass. World and nearby players stay clearly visible. This was the cause of both the "too dark" look and the render-thread stall with Iris/Sodium.
- **Custom widgets** (in `modgui/component/`; kit in `modgui/theme/YjTheme`): `ToggleSwitch`, `ThemeSlider` (built on `SliderWidget`, custom-drawn) with a synced numeric entry box, `KeybindButton`.
- **No per-frame / per-keystroke saving**: edits apply live in memory; the file is written on a 350 ms debounce, on slider release, on Save, and on close. Sliders never write during a drag.
- Responsive centred window; sidebar nav; header with status; footer Save/Reset/Back; "Settings saved" toast; hover tooltips (no "!" boxes). `shouldPause()` returns false so the world keeps moving behind the panel.

## BUILD PIPELINE (plain Fabric source build)
1. `compileJava` — compiles all sources from `src/main/java`
2. `processResources` — expands `${version}` in `fabric.mod.json`
3. `jar` → `remapJar` — Yarn → Intermediary (standard loom)
4. `copyJar` — copies to `../YJHack-1.21.5.jar`

## ANTI-PATTERNS (THIS PROJECT)
- No empty `catch {}` — config I/O logs via the per-module SLF4J `LOGGER`.
- No reflection to sync settings — use the typed `applyRuntimeConfig`/`saveConfigStatic` bridge.
- No `super.renderBackground()` in mod screens — it applies the vanilla blur.
- No config writes every frame / every keystroke — debounce.
- CPS is `randomInt(minCps,maxCps)` — no meanCps/stdDev anywhere.
- `bin/main/` must not return.

## COMMANDS
```bash
cd "/home/masteryj/Desktop/Mod Maker/YJHack-1.21.5/Dev"
./gradlew build          # → ../YJHack-1.21.5.jar
./gradlew clean build    # clean rebuild
./gradlew runClient      # launch MC (needs a display + assets)
```
