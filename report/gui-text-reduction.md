# GUI text reduction

Goal: cut on-screen text density ~30–40% **without changing the design** — same theme,
same translucent panels, same colours, same window size, no blur, `shouldPause()` still
`false`, players still visible behind the panel. Only *how much text* is shown changed;
the removed detail moved to hover tooltips, not deleted.

## Dashboard — the crowded screen

### Header
| | Before | After |
|---|---|---|
| status (top-right) | `5/5 modules on` | `5 active` |

### Heading subtitle
| | Before | After |
|---|---|---|
| under "Module Dashboard" | `Click a card to configure. Everything below is live.` | `Select a module to configure` |

### Cards — 4 lines → 3 lines
**Before** (each card, 4 text rows, cramped at y+7/+19/+31/+41):
```
Auto Left                    ●
Left-click automation
CPS 8-16  •  Weapon
Key: None
```
**After** (3 rows, more breathing room at y+9/+24/+37; details on hover):
```
Auto Left                    ●
Left-click automation
CPS 8-16
```
**Hover tooltip (new):** `Auto Left · Toggle key: None · Weapon mode: off · Click to configure`

Per-module summary reductions (secondary value → tooltip):
| Card | Before (face) | After (face) | Moved to tooltip |
|---|---|---|---|
| Auto Left | `CPS 8-16  •  Weapon` | `CPS 8-16` | Weapon mode on/off, key |
| Auto Right | `CPS 14-28  •  Block` | `CPS 14-28` | Block mode on/off, "Fire Charge = one use per press", key |
| Ninja Bridge | `Auto-switch on` | `Auto-switch on` | key |
| AimAssist | `Speed 0.24  •  FOV 70` | `Speed 0.24` | FOV, key |
| Tracker | `Range 96` | `Range 96` | team filter, key |

### Quantified
- Card face text rows: **4 → 3 (−25%)** across all 5 cards (20 → 15 draws).
- Heading subtitle: 52 → 28 chars (**−46%**).
- Header status: 14 → 8 chars (**−43%**).
- Secondary values (`• Weapon`, `• Block`, `• FOV 70`) removed from the face entirely.
- Card inner padding increased (x+9→x+10; rows spaced 12px→~13–15px) for a less cramped feel.
- **Aggregate on-screen text on the dashboard is reduced ~30–40%**, with every removed detail
  reachable via the card's hover tooltip.

## Settings screens
Already low-density in the v2 design and left essentially as-is:
- One short heading + one short subtitle per screen (module name appears once, not repeated).
- Each control has an inline label; the longer explanation is already a **hover tooltip**
  (`addHelp(...)`), not on-screen body text.
- No "Saved / Live / Current" labels next to fields (there were none to begin with).
- Save / Reset / Back remain clearly present in the footer action bar.

## Explicitly unchanged (design preserved)
- Theme colours, `SCREEN_TINT` (~13% alpha), translucent `PANEL`/`SIDEBAR`, no blur, no
  global darkening, world + players visible.
- Window sizing (`winW/winH`), sidebar (names only), custom widgets (toggles, sliders +
  numeric entry, keybind capture), toast "Settings saved", debounced save timing.
- `shouldPause()` stays `false`.

## Verification
- `./gradlew build` → BUILD SUCCESSFUL; 19 tests pass; jar still 32 classes / 6 entrypoints.
- No colour, alpha, blur, window-size, or save-timing code was touched — only text content,
  line count, and tooltip registration on the Dashboard.
- Live visual confirmation (card layout, hover tooltip, players-behind-panel) is part of the
  Lunar acceptance checklist in `regression-test-results.md` (user-run).
