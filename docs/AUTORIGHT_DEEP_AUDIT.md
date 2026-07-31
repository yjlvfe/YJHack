# AutoRight Deep Audit — 2026-07-31

## Scope

This audit compares the current fixed-CPS AutoRight path with the earliest tracked implementation at commit `b0c08d1` and the later vanilla-queued implementation at `1ae5887`. AutoLeft, AimAssist, Tracker, and unrelated GUI behavior are intentionally out of scope.

## Why the earliest version felt faster

The original implementation did not feel responsive merely because it exposed Min/Max CPS.

1. Its shipped range was 14-28 CPS, whose arithmetic centre is 21 CPS. The v1.3.0 fixed default was 10 CPS, roughly half that attempt cadence.
2. It generated `KeyBinding.onKeyPressed(...)` events, so Minecraft consumed the attempts through its normal input path.
3. It ran from the client tick loop. The later v1.3.0 path called `doItemUse()` directly from `WorldRenderEvents.END`, coupling placement attempts to render timing instead of the input tick.
4. The original burst loop could replay overdue clicks. That produced occasional same-tick bursts and a fast subjective feel, but it was unsafe and could increase rejected predictions, packet pressure, and post-stall bursts.

## Confirmed regressions in v1.3.0

### Direct render-phase use

The fixed path replaced vanilla queued presses with a direct `MinecraftClient#doItemUse()` invoker from the render callback. This moved the action outside the normal input phase and made the result dependent on frame timing.

### Over-strict pre-validation

The v1.3.0 client attempted to calculate the final placement position itself and required that position to be replaceable before calling vanilla. That duplicated only part of Minecraft's placement rules and could reject legal special placements or flicker false while moving across an edge. Vanilla must remain the final placement authority.

### Timer reset on candidate flicker

The wall-clock limiter was cleared whenever the client-side candidate check failed. When the crosshair became valid again, the full interval restarted, creating an eaten-click feeling during fast movement.

### Conservative default migration

The exact v1.3.0 default of 10 CPS was much slower than the original effective cadence. Custom fixed CPS values must remain untouched, but the exact old default can safely migrate to 20.

### Empty-stack transition

A held block stack can become empty shortly before NinjaBridge selects the next block stack. Permanently invalidating the press during that short empty-hand interval breaks continuous bridging.

## Implemented architecture

- Keep one fixed `cps` value; Min/Max is not restored.
- Run AutoRight from `START_CLIENT_TICK`.
- Preserve the first physical click as vanilla.
- Suppress vanilla held repeat only for block and known single-press contexts.
- Queue follow-ups with `KeyBinding.onKeyPressed(...)` through the configured Minecraft use binding.
- Use a fixed tick-phase accumulator:
  - 1-20 CPS: at most one pulse per tick.
  - 21-40 CPS: at most two pulses per tick.
  - No elapsed-time catch-up, backlog, replay, or post-stall compensation.
- Require only a current `BlockHitResult` before queuing; vanilla performs the complete placement decision.
- Drop invalid-candidate pulses, but emit one immediate edge-response pulse when a block face is reacquired.
- Allow a four-tick empty-stack grace window for NinjaBridge restocking; switching to a real non-block item still invalidates the hold.
- Change the recommended fixed default to 20 CPS.
- Migrate only an exact version-8 default value of 10 CPS to 20; preserve custom values.

## Evidence boundary

Automated tests validate cadence, no-backlog behavior, candidate reacquisition, fixed-value migration, and block-to-block continuity. They do not prove target-server placement acceptance, Via/proxy behavior, anti-cheat decisions, or the complete absence of ghost blocks. A controlled in-game bridge test is still required.
