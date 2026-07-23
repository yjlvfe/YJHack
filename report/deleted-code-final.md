# Deleted code — final cleanup

All removals are behaviour-preserving and were verified by build + jar inspection
(32 classes, 6 entrypoints unchanged) and the 19-test JUnit suite. Nothing on the
"KEEP" list (entrypoints, callbacks, RightClickPolicy, single-press handling, Tracker
HUD/box, aim cleanup, typed config bridge, blur suppression, `shouldPause=false`,
config migration) was touched.

## 1. Unused named constants (9) — `commit f157bd7`
The decompiler had inlined the literal and left a dead `private static final`:

| File | Constant | Proof of deadness |
|---|---|---|
| aimassist/AimAssistClient | `MOUSE_KEY_OFFSET` | 0 refs; body uses `1000` |
| aimassist/AimAssistClient | `CONFIG_RELOAD_INTERVAL_MS` | 0 refs; body uses `5000L` |
| aimassist/AimAssistClient | `BLOCK_BREAK_FOCUS_MS` | 0 refs; body uses `500L` |
| autoleft/AutoLeftClient | `MOUSE_KEY_OFFSET` | 0 refs; body uses `1000` |
| autoright/AutoRightClient | `MOUSE_KEY_OFFSET` | 0 refs; body uses `1000` |
| tracker/TrackerClient | `MOUSE_KEY_OFFSET` | 0 refs; body uses `1000` |
| tracker/TrackerClient | `WALL_HITBOX_EXPAND` | 0 refs; body uses `0.03` |
| tracker/TrackerClient | `HITBOX_BUFFER_SIZE` | 0 refs; body uses `16384` |
| tracker/TrackerClient | `CONFIG_RELOAD_INTERVAL_MS` | 0 refs; body uses `5000L` |

(`CONFIG_RELOAD_INTERVAL_MS` was **kept** in AutoLeft/AutoRight/NinjaBridge where it
is actually referenced; `MOUSE_KEY_OFFSET` was **kept** in ModGuiClient/YjTheme where used.)

## 2. Unused Theme colours + dead helper (ModGuiClient) — `commit f157bd7`
- `Theme.ACCENT_DIM`, `Theme.ERROR`, `Theme.FOOTER` — 0 uses anywhere.
- `normalizeKey(int)` — private, never called (each module normalizes keys itself).

## 3. Redundant explicit no-arg constructors (4) — `commit f157bd7`
- `TrackerClient()`, `AimAssistClient()`, `TrackerClient.Config()`, `AimAssistClient.Config()`.
- Each class declares no other constructor, so the compiler emits an identical implicit
  default; Fabric entrypoint reflection and Gson deserialization are unaffected.

## 4. Stale comment describing a removed system — `commit f157bd7`
- NinjaBridgeClient: 3 lines narrating a *removed* "reflection-based untoggleMethod"
  trimmed to a single accurate line. `setSneakState` logic unchanged.

## 5. Gradle dead/stale bits — `commit 0955a8e`
- Removed unused `maven-publish` plugin (no publishing config).
- Removed stale banner comment (bin/main narrative + nonexistent report reference).
- Replaced a hardcoded absolute path with a config-time relative delete.

## 6. Not "deleted" but relocated (behaviour-preserving) — `commits a1bb9c8, be0a0d7`
- Dashboard "Key:" line + secondary summary values → moved to hover tooltip (text reduction).
- `ModGuiClient.Theme` + 3 widget classes → moved verbatim to `theme/` and `component/`
  packages (see final-jar-verification.txt for the 1:1 class relocation).

## Net effect
~24 lines of dead declarations removed; ~2 dead colours + 1 dead method + 4 redundant
constructors gone; 1 stale comment fixed; Gradle trimmed. Structure of the built jar is
unchanged (32 classes, 6 entrypoints, identical resources).
