# Runtime Diagnosis — "Not Responding" freeze (repair v2)

**Date:** 2026-07-23
**Installed build under test:** SHA-256 `5254d1…` (v1.2.0, built 15:31) — the previous repair.
**Fix build:** SHA-256 `706daa61…` (this pass).

---

## 1. Evidence actually collected

| Source | Location | What it showed |
|--------|----------|----------------|
| Lunar game log | `~/.lunarclient/profiles/1.21/logs/latest.log` | Mod loaded fine (`yjhack` in the resource-manager list). No YJHack exception, no crash report, no watchdog line. |
| Log timeline | same file | Player was mid-session on Hypixel (Duels/MurderMystery). Between `15:45:46` and `15:49:31` there is a ~4-minute gap with **no render-thread log lines**, then `Stopping!` — consistent with a **render-thread hang** (a freeze, not a crash: a crash writes a stack trace, a hang writes nothing). |
| Runtime configs | `~/.minecraft/config/*.json` | All five configs are **well-formed and in range** (autoright: blockMode=true, CPS 25-30; aimassist speed 0.24; tracker range 100…). → **Config corruption is ruled out** as the freeze cause, with evidence. |
| Resolution | crash card / configs (hudY 527) | **720×1280 portrait** — a narrow window; relevant to GUI layout, not to a hang. |

**Honest limitation:** I could not launch Lunar Client or Minecraft in this environment (no display / no game runtime), so I could **not** capture a live `jstack`/`jcmd` thread dump during an actual freeze. `report/freeze-thread-dump.txt` explains this and gives the static thread-level analysis that substitutes for it. Per the task rules, no freeze item below is marked "Fixed" — they are marked **root cause removed in code, pending in-game confirmation**.

---

## 2. Root cause (decompiler-verified)

Every mod screen’s `render()` called `super.render()` → `Screen.render()`. Disassembling the
Yarn-mapped `net.minecraft.client.gui.screen.Screen` from the Loom cache:

```
Screen.render(DrawContext,int,int,float):
   6: invokevirtual  renderBackground:(...)V     // called automatically, every frame

Screen.renderBackground(DrawContext,int,int,float):
  14: invokevirtual  renderPanoramaBackground     // (menu, no world)
  18: invokevirtual  applyBlur:()V                // <-- fullscreen blur post-effect
  23: invokevirtual  renderDarkening:(...)V        // <-- dark gradient overlay
```

So while a mod screen was open, **`applyBlur()` + `renderDarkening()` ran every frame over the
live world.** Consequences:

1. **"Too dark / can't see players"** — proven. The prior repair only lowered the mod's *own*
   translucent tint; it never stopped the vanilla blur+darkening underneath. That is why the
   world stayed near-black regardless of the tint change.
2. **Leading "Not Responding" suspect.** `applyBlur()` is a fullscreen framebuffer post-process.
   The Lunar log shows **Iris + Sodium + Noxesium + ImmediatelyFast** loaded. The vanilla menu
   blur interacting with a shader/framebuffer pipeline is a well-known source of severe
   render-thread stalls and "Not Responding" on some GPUs. This is the most plausible hang
   given the evidence; it cannot be *proven* the sole cause without a live dump.

**File/line responsible (mod side):** the old `ModGuiClient.BaseScreen.render()` calling
`super.render(...)` on every screen (AimAssistScreen/AutoLeftScreen/AutoRightScreen/
NinjaBridgeScreen/TrackerScreen/MainScreen), with no `renderBackground` override to suppress the
vanilla blur.

### Secondary render-thread pressure (also removed)
- **File I/O + reflection on every keystroke.** Old `bindNumberField → setChangedListener →
  save() → saveConfig()` wrote the JSON file **and** ran `Class.forName` + field reflection on
  every character typed, on the render thread. Not a multi-minute hang by itself, but real
  per-frame/per-keystroke work in the GUI.

---

## 3. Modules cleared (with reasons)

- **Tracker** (`TrackerClient`): the `VertexConsumerProvider.immediate` buffer is allocated
  **once** (field init), not per frame; `renderEnemyHitboxes`/`tickTracker` loop only over
  `world.getPlayers()` (bounded). Not a freeze source.
- **AimAssist** (`AimAssistClient`): raycasts are gated behind `enabled` + physical left-mouse;
  bounded by player count. Not a freeze source.
- **AutoLeft/AutoRight/NinjaBridge ticks**: bounded per-tick work, no unbounded loops.
- No duplicate event/callback registration was found; each module registers its handler once in
  `onInitializeClient`.

---

## 4. Fixes applied this pass

| Cause | Fix | File |
|-------|-----|------|
| Vanilla blur/darkening every frame | `YjScreen.renderBackground()` overridden to draw only a light ~0x22 tint; **never calls `super.renderBackground()`** | `modgui/ModGuiClient.java` |
| Per-keystroke file I/O + reflection | Typed config bridge; edits apply live in memory; file saved on 350 ms debounce / slider release / Save / close. No reflection. | `modgui/ModGuiClient.java` (+ `saveConfigStatic` added to Tracker/AimAssist) |
| `shouldPause()` | returns `false` so the world keeps rendering/moving behind the panel (needed to see players) | `modgui/ModGuiClient.java` |

---

## 5. Status

- Blur/darkening removal: **implemented, compiles, world-visibility path reasoned through** —
  **pending in-game confirmation on Lunar** (see `lunar-test-results.md`).
- Freeze: **primary root cause removed**; because no live thread dump was captured, this is
  **not** declared "Fixed". It must be confirmed by the 15-minute Lunar session test.
