# GUI — Before / After (repair v2)

> **Note on "After" screenshots:** I cannot launch the game in this environment, so I cannot
> attach rendered After screenshots. The "After" column describes exactly what the new code
> draws; the user's Lunar run is what visually confirms it (see acceptance criterion at the end).
> The "Before" column is taken from the attached screenshots and the prior source.

| Aspect | BEFORE (attached screenshots / old `ModGuiClient`) | AFTER (`ModGuiClient` v2) |
|--------|-----------------------------------------------------|----------------------------|
| World behind panel | Near-black; players invisible. Cause: vanilla `applyBlur()`+`renderDarkening()` ran every frame via `super.render()`. | **No vanilla blur.** `renderBackground()` overridden; only a light ~0x22 full-screen tint. World + players clearly visible. `shouldPause()=false` so they keep moving. |
| Controls | Vanilla grey buttons ("Enabled: ON"), raw `TextFieldWidget`s for numbers. | Custom **toggle switches**, custom **sliders** each with a synced **numeric entry box**, custom **keybind** control, **status chips**. |
| Help | Many unexplained "!" boxes. | **Hover tooltips** on each row; short descriptions. No "!" boxes. |
| Layout | Content stretched; large empty area below; Save/Reset far from content. | **Centred responsive window**, fixed sidebar, header with live status, **footer action bar** (Back / Reset / Save) pinned to the panel. Sized for 720×1280 and common sizes. |
| Save feedback | None. | **"Settings saved"** toast after Save. |
| Dashboard | Every card stamped "READY"; text "Locked modules stay unavailable until their .jar is installed". | Cards show name, description, **live ON/OFF status dot + chip**, current **key**, and key values (e.g. "CPS 14-28 • Block"). No "READY", no "Locked modules" text. |
| Saving cost | `saveConfig` (file write + `Class.forName` reflection) on **every keystroke** and on every toggle. | Live in-memory apply; file saved on **350 ms debounce / slider release / Save / close**. Sliders never write while dragging. |
| GUI↔module wiring | Blind **reflection** copying fields by name between two different config objects (silently dropped mismatches; earlier caused settings to not apply / revert). | **Typed bridge**: the GUI edits the module's own `Config` type and calls the module's `applyRuntimeConfig()` + `saveConfigStatic()`. No reflection. |
| Branding text | "HelpNoob"/"Modules" leftovers, "meanCps"/"stdDev". | "YJHack Client"; CPS min/max only. |

## Theme (unified constants — `ModGuiClient.Theme`)
- Screen tint `0x22060A0F` (only full-screen layer, ~13%).
- Panels: main `0xB0…` (~69% glass), sidebar `0xC0…`, header/footer, cards, controls.
- Accent teal `0xFF35E0C8`; text primary/secondary/muted; success/warning/error; toggle track/knob.
- Metrics: padding, row height (24), control height (20).

## Visual acceptance criterion (must be checked in-game on Lunar)
> Open the GUI standing in front of a nearby player: you must clearly see the player and their
> movement behind the panel. This is the pass/fail test for the blur removal and cannot be
> declared passed from static review alone.
