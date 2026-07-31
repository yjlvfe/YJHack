# YJHack 1.3.0 — Multi-Version Stability Release

YJHack 1.3.0 is built for Minecraft Java 1.21.5 clients that may connect to legacy-style 1.8.9 combat servers through ViaVersion or comparable protocol translation.

## Research and compatibility

- Reviewed ViaVersion, ViaBackwards, ViaRewind, ViaFabricPlus, Minecraft 1.21.5 Yarn mappings, YJHack `main`, and the reference tag `v1.0.6-autoleft-hold-fix-20260730`.
- Separates generated CPS, vanilla attempts, translated packets, server-processed attacks, and damage-producing hits.
- Does not claim that 40 CPS creates 40 accepted hits or fixed damage.
- Uses vanilla attack/use paths; no direct packet spoofing, reach increase, through-wall targeting, fake lag, critical spoofing, or anti-cheat bypass.

## Recommended settings

- AutoLeft: 12 CPS.
- AutoRight: 10 CPS.
- NinjaBridge: Auto Switch enabled, 120 ms slot delay.
- AimAssist: 3.5 max range, 70 FOV, 0.22 speed, 0.62 smoothness, Sticky Lock, mandatory Line of Sight, and Bed Lock enabled.
- Tracker: 48 range, ignore own team, HUD at X 8 / Y 8.

All modules default to disabled. These are conservative client defaults, not guarantees about a server's damage, knockback, anti-cheat, or placement acceptance.

## Reset and automatic saving

- Every module page has its own Reset button.
- Reset creates a fresh recommended config, affects only the open module, applies immediately, saves to disk, refreshes all controls in place, and shows `Recommended settings restored`.
- Reset sends no input or packet and does not close the page.
- Save, Apply, and Confirm buttons were removed.
- Valid edits apply live and save with a short debounce, slider release, navigation, Back, and close.

## AutoLeft

- One `cps` value from 1–40.
- Independent from AutoRight.
- One follow-up owner.
- Monotonic timing with no queue, backlog, catch-up, replay, or post-stall burst.
- First physical click remains vanilla.
- Follow-ups require a current vanilla entity hit result.

## AutoRight

- One independent `cps` value from 1–40.
- Conservative placement candidate validation before vanilla use.
- Block-to-block stack transitions preserve the physical hold.
- Fire Charge, pearls, buckets, and known instant items use once per physical press.
- Food, bows, shields, and hold/charge items remain vanilla.
- Vanilla remains responsible for sequence IDs, prediction, collision, packets, and server correction.

## AimAssist and Bed Lock

- Absolute target-retention cap of 3.5 blocks.
- Does not modify attack reach.
- Deterministic smoothing with no random jitter.
- Sticky target retention without excessive target switching.
- Mandatory line of sight.
- Bed Lock prevents nearby players from stealing aim while the same bed is actively being broken.
- Ordinary blocks do not create the special lock.

## NinjaBridge and Tracker

- Recommended 120 ms slot-switch delay.
- Avoids unnecessary repeated slot selection.
- Synthetic sneak never cancels a real physical sneak hold.
- Runtime state is cleared on world/focus/GUI/death/disable paths.
- Tracker keeps the professional HUD and position editor and uses only player entities already available in normal client state.

## Interface

- Restored the professional translucent YJHack theme from the reference release.
- Sidebar, header, dashboard cards, hover states, status chips, tooltips, summaries, Tracker HUD position editor, `YjTheme`, `ThemeSlider`, `ToggleSwitch`, and `KeybindButton` are present.
- No blur or vanilla fullscreen darkening.

## Testing

- Clean Java 21 / Gradle build.
- CPS 1–40, independent left/right timing, 500 ms and 2 second stalls, no catch-up, one pulse per callback.
- Config migration from legacy `minCps`/`maxCps`.
- 3.5 range boundary, line of sight, Sticky Lock, and Bed Lock ownership.
- AutoRight placement gating and block-stack transitions.
- NinjaBridge slot-delay guards.
- Fresh per-module recommended profiles and GUI Reset contract.

## Known limitations

- Automated tests do not prove real target-server damage, knockback, anti-cheat acceptance, or all ghost-block behavior.
- Server plugins, no-damage windows, TPS, Via configuration, ping, jitter, upload loss, and proxy buffering remain authoritative.
- Use `docs/MANUAL_TEST_CHECKLIST.md` for controlled target-server validation.
