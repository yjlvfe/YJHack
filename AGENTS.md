# PROJECT KNOWLEDGE BASE — YJHack-1.21.5

**Project:** YJHack Minecraft 1.21.5 Fabric client mod
**Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4 (Yarn mappings)

## OVERVIEW
All-in-one client-side Fabric mod — 6 modules bundled into one JAR. **100% source** in
`Dev/src/main/java` (no `Dev/bin/`, no pre-compiled blobs, no ASM/class injection). The
build compiles every module from source and copies the remapped jar to the project root
as `YJHack-1.21.5.jar`.

## STRUCTURE
```
YJHack-1.21.5/
├── AGENTS.md                 # ← this file
├── .gitignore
├── YJHack-1.21.5.jar         # build output (generated; git-ignored)
└── Dev/                      # Gradle project root (see Dev/AGENTS.md)
```
The frozen v1.0.0 reference build (SHA-256 `706daa61…`) is **not** in the repo; it is kept
outside the project (e.g. `../YJHack-1.21.5good.jar`) so a clean tree is preserved.

## WHERE TO LOOK
| Task | Location |
|------|----------|
| **Everything** | `Dev/` — Gradle project with all source + build system |
| **Source modules** | `Dev/src/main/java/com/masteryj/` (6 modules + `autoright/RightClickPolicy`) |
| **GUI** | `modgui/ModGuiClient` + `modgui/theme/YjTheme` + `modgui/component/*` |
| **Tests** | `Dev/src/test/java` — JUnit via fabric-loader-junit (`./gradlew test`) |
| **Graph** | `Dev/graphify-out/` (graph.json, graph.html, GRAPH_REPORT.md) — the only Graphify output |
| **Build output** | `YJHack-1.21.5.jar` (project root, copied by `copyJar`) |
| **Details** | `Dev/AGENTS.md` — build pipeline, conventions, anti-patterns, per-module notes |

## KEY FACTS
- **6 entrypoints** (load order): ModGui → Tracker → AimAssist → AutoLeft → AutoRight → NinjaBridge.
  Each `ClientModInitializer` registers its callbacks exactly once; each is listed once in
  `fabric.mod.json`.
- **No networking code.** The mod opens no sockets and sends no packets directly. All packet
  effects are produced indirectly through vanilla input (keybindings, rotation, sneak, slot).
  It never touches KeepAlive, movement confirmation, or disconnect packets, and makes no
  HTTP/Proxy/Telemetry/Update-check connections.
- **CPS:** `randomInt(minCps, maxCps)` — no Gaussian/jitter/fluctuation. `normalize()` clamps
  hand-edited configs to **[1, 40]** (`MAX_SAFE_CPS`), the same ceiling the GUI slider enforces,
  so a corrupt file cannot cause click/packet spam.
- **AutoRight** classifies the held item via `autoright/RightClickPolicy` (the single source of
  truth): SINGLE_PRESS (one use per physical press, e.g. Fire Charge/pearls) / BLOCK (CPS burst,
  Block Mode only) / PASS_THROUGH.
- **Tracker** computes the tracked-player set once per tick and reuses it for the per-frame red
  box (no per-frame rescan); HUD uses the deprecated-but-intentional `HudRenderCallback`.
- **NinjaBridge** toggles sneak only on a real state change and pauses (releasing sneak) while a
  screen is open or the window is unfocused, like the other modules.
- **GUI:** no vanilla blur/darkening (`YjScreen.renderBackground` overridden, never calls
  `super`); `shouldPause()` is false so the world keeps ticking behind the panel.
- **Config:** per-module Gson JSON in the config dir; live edits apply through a typed
  `applyRuntimeConfig`/`saveConfigStatic` bridge (no reflection). A 5 s mtime-gated poll picks up
  external hand-edits; it no-ops when the file is unchanged.
- **Git:** tracks only `AGENTS.md`, `Dev/`, `.gitignore`. Build outputs, `graphify-out/`, and
  runtime dirs are git-ignored.

## COMMANDS
```bash
cd Dev && ./gradlew build          # → ../YJHack-1.21.5.jar
cd Dev && ./gradlew clean test build
cd Dev && ./gradlew runClient      # launch MC (needs a display + assets)
```
