# YJHack 1.3.0 manual test checklist

Automated tests do not prove real server damage, knockback, anti-cheat acceptance, or block placement. Run this checklist on the actual target environment before claiming in-game validation.

## Test environment record

- Client: Minecraft Java 1.21.5
- Fabric Loader/Fabric API versions:
- Server address/build:
- Server base combat version:
- ViaVersion/ViaBackwards/ViaRewind versions:
- Via configuration snapshot:
- Combat/anti-cheat plugin names and versions:
- Server TPS during test:
- Client FPS during test:
- Ping average / jitter / packet loss:
- Equipment, armor, effects, absorption, and enchantments:

## AutoLeft

For each CPS value 1, 5, 10, 12, 20, 30, and 40:

- [ ] Hold attack against a stationary target for a large repeated sample.
- [ ] Record generated attempts separately from server-confirmed damage events.
- [ ] Repeat at low, medium, and high ping.
- [ ] Repeat with stable FPS and intentionally reduced FPS.
- [ ] Verify no burst after a 500 ms render stall.
- [ ] Verify no burst after a 2 second stall/menu/focus loss.
- [ ] Verify mining and empty-space holds remain normal.
- [ ] Verify one physical press does not create two immediate attacks.
- [ ] Compare damage and knockback over many hits, not one hit.

## AutoRight and NinjaBridge

For CPS 1, 5, 10, 20, 30, and 40:

- [ ] Bridge while holding a normal full block stack.
- [ ] Exhaust the stack and transition to another valid block stack.
- [ ] Confirm hold continues without repeated unnecessary slot changes.
- [ ] Transition from blocks to Fire Charge; confirm the block context ends.
- [ ] Test pearl, food, bow, shield, bucket, and Fire Charge behavior.
- [ ] Aim at air/no valid face; confirm no repeated artificial use attempts.
- [ ] Aim at occupied/non-replaceable positions.
- [ ] Test near the world border.
- [ ] Record every local prediction followed by server correction.
- [ ] Repeat under low, medium, and high ping and while moving/sneaking.

## AimAssist

- [ ] At 3.4 blocks, confirm a valid visible target can be retained.
- [ ] At exactly 3.5 blocks, confirm retention remains possible.
- [ ] Beyond 3.5 blocks, confirm immediate release.
- [ ] Confirm attacks still require Minecraft's normal crosshair/reach result.
- [ ] Place a solid wall between player and target; confirm release.
- [ ] Confirm small FOV/camera deviations do not switch a sticky target.
- [ ] Confirm target death/disappearance/world change releases lock.
- [ ] Begin breaking a bed, then bring another player within 3.5 blocks; confirm the bed keeps aim ownership.
- [ ] Release attack, change target, break/remove bed, open GUI, lose focus, disconnect, and change world; confirm Bed Lock releases.
- [ ] Break an ordinary block; confirm the special Bed Lock is not created.

## Runtime state cleanup

- [ ] Disable every module while its physical key is held.
- [ ] Open GUI while keys are held.
- [ ] Alt-tab / lose focus while keys are held.
- [ ] Disconnect while keys are held.
- [ ] Change world/dimension and respawn.
- [ ] Confirm no extra attack/use, no burst, no stuck use, and no stuck sneak.

## GUI, autosave, and Reset

For AutoLeft, AutoRight, NinjaBridge, AimAssist, and Tracker individually:

- [ ] Change every control and confirm runtime updates immediately.
- [ ] Confirm no Save, Apply, or Confirm button exists.
- [ ] Drag a slider and confirm disk writes are debounced, not per frame.
- [ ] Release slider and confirm save.
- [ ] Navigate to another page and back; confirm save.
- [ ] Close/reopen GUI and restart the game; confirm persistence.
- [ ] Press Reset and confirm only the open module changes.
- [ ] Confirm all visible controls refresh immediately.
- [ ] Confirm the page stays open.
- [ ] Confirm toast text is `Recommended settings restored`.
- [ ] Confirm Reset sends no attack, use, sneak, slot change, or packet.
- [ ] Restart the game and confirm the Reset values persisted.

## Result boundary

Do not mark real combat or placement behavior as verified unless the target server was available and the test environment record above is complete. Attach repeated measurements and server-side logs where possible.
