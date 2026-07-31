# YJHack 1.3.1 — AutoRight Responsiveness Repair

This release focuses only on the held block-use path. AutoLeft combat behavior and unrelated modules remain unchanged.

## AutoRight deep repair

- Keeps one fixed `cps` value from 1–40. Min/Max CPS is not restored.
- Changes the recommended fixed AutoRight value from 10 to 20 CPS, matching the responsive effective cadence of the original 14–28 range without random variation.
- Migrates only the exact previous default of 10 CPS to 20; custom fixed CPS values remain unchanged.
- Moves AutoRight from render-phase direct `doItemUse()` calls to Minecraft's normal tick-aligned queued use binding.
- Preserves the first physical right click as fully vanilla.
- Uses a fixed tick-phase cadence with a maximum of two attempts per tick.
- Never replays elapsed time: no backlog, catch-up, post-stall burst, or packet queue.
- Removes duplicated client-side replaceability checks that could reject or flicker legal vanilla placements.
- Requires a current block hit before queuing while leaving the complete placement decision, sequence IDs, prediction, collision, and packets to vanilla.
- Adds immediate response when a block face is reacquired after brief crosshair loss.
- Adds a short empty-stack grace window so NinjaBridge can select the next block stack without permanently breaking a held placement.
- Keeps Fire Charge, pearls, buckets, and known instant items to one use per physical press.
- Keeps food, bows, shields, and hold/charge items fully vanilla.

## Verification

- Java 21 / Gradle 8.14.4 clean test and build.
- 65 automated tests with zero failures, errors, or skipped tests.
- Tests cover fixed 10/20/40 CPS cadence, two-attempt ceiling, no-backlog behavior, candidate reacquisition, configuration migration, fresh recommended profiles, and block-to-block continuity.

## Evidence boundary

Automated tests cannot prove target-server placement acceptance, Via/proxy behavior, anti-cheat decisions, or the complete absence of ghost blocks. Controlled in-game testing on the target server is still required.
