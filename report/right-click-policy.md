# Right-Click Policy — AutoRight & Fire Charge

**New file:** `Dev/src/main/java/com/masteryj/autoright/RightClickPolicy.java`
**Rewired:** `Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java`

---

## The bug (old build)

`AutoRightClient.tickRightAutoClick` had one `else` branch (old lines 151-192) that ran the
CPS auto-click loop for **any item that was not a held block**. So holding right-click with a
Fire Charge / fireball:

- Block Mode ON + non-block held → fell into the `else` branch → **synthetic clicks at 25-30 CPS**
  (the configured CPS) → 25-30 fireballs per second.
- Block Mode OFF → everything auto-clicked at CPS as well.

That is the "AutoRight repeats fireball by CPS" report.

---

## The rule now

Classification lives in **one** place, `RightClickPolicy`:

| Kind | Items | Behaviour |
|------|-------|-----------|
| `SINGLE_PRESS` | Fire Charge, Ender Pearl, Snowball, Egg, splash/lingering potion, Wind Charge, XP bottle, Ender Eye, Fishing Rod, Trident, Bow, Crossbow, buckets, and any `UseAction` ∈ {BOW, CROSSBOW, SPEAR, TOOT_HORN} | **One use per physical press.** Never CPS — even with Block Mode off. |
| `BLOCK` | any `BlockItem` | CPS burst **only** while Block Mode is on. Block Mode off → pass-through. |
| `PASS_THROUGH` | everything else (food, tools, shields, spyglass…) | Vanilla input untouched — no synthetic clicks, no suppression. |

Classification is by **registry id** (`Registries.ITEM.getId(item).getPath()`) which is stable
across Yarn builds, plus a `UseAction` check for charge/draw items. It never depends on internal
class names. Fire Charge is the mandatory minimum and is the first entry in the id set.

---

## Press flow (physical mouse → classification → repeat/single-press → vanilla)

```
GLFW.glfwGetMouseButton(RMB)          // physical poll, every client tick
        │
        ▼
RightClickPolicy.classify(heldStack)  // SINGLE_PRESS / BLOCK / PASS_THROUGH
        │
   ┌────┴───────────────┬───────────────────────────┐
   ▼                    ▼                           ▼
SINGLE_PRESS          BLOCK (+Block Mode)          PASS_THROUGH / BLOCK(no mode)
rising edge → 0 synthetic   rising→first place      do nothing;
   (vanilla fires 1 use)    then CPS burst          leave vanilla input as-is
held → setKeyPressed(RMB,false)  (clickMouseKey     (no synthetic click)
   (suppress vanilla repeat)      at CPS interval)
release → re-arm
```

Why single-press works without a mixin: AutoRight polls the physical button directly, so it is
independent of the key-binding state. On the rising-edge tick it does nothing, letting the one
vanilla use through; on every held tick it forces `useKey` released, so vanilla's
`itemUseCooldown` loop cannot re-fire. A new use needs RELEASE → PRESS.

## State reset (no stuck keys / no deferred clicks)
`resetRightAutoClickState()` forces `KeyBinding.setKeyPressed(RIGHT_MOUSE, false)` and clears the
edge state. It runs whenever `!enabled` **or** not in active gameplay — i.e. on module disable,
**GUI open** (`currentScreen != null`), world exit, unfocus, death, or spectator. So opening the
GUI while holding, disabling mid-hold, or leaving the world never leaves the key pressed and never
replays a click on return.

---

## Acceptance tests

**Automated (run here):** headless rising-edge model
(`report/SinglePressModelTest.java`) — models the exact contract handleSinglePress implements:

```
[PASS] 20 separate presses = 20 uses      expected=20 actual=20
[PASS] hold 200 ticks = 1 use             expected=1  actual=1
[PASS] press/release/press = 2 uses       expected=2  actual=2
[PASS] 5 long holds = 5 uses              expected=5  actual=5
[PASS] no press = 0 uses                  expected=0  actual=0
ALL PASS
```

**In-game (pending user on Lunar — NOT yet verified):**
- 20 discrete Fire Charge presses = 20 fireballs.
- Holding Fire Charge produces exactly 1.
- Blocks still burst at CPS with Block Mode on.
- Opening the GUI mid-hold stops automation and releases the key.
- Changing hotbar slot mid-hold does not auto-use the new item.
- Leaving the world mid-hold leaves no stuck key.

These six require the game and are listed in `lunar-test-results.md` as **not yet run**.
