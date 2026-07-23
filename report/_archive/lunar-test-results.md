# Lunar Acceptance Tests — Results

**Build under test:** `YJHack-1.21.5.jar` SHA-256 `706daa61acd3f8fc774e493e5935e06854230a11ba674181b15eda95d7a5178b`
**Installed at:** `~/.lunarclient/profiles/1.21/mods/fabric-1.21.5/YJHack-1.21.5.jar` (built == installed, verified)

---

## IMPORTANT — honest status

I could **not** run Lunar Client / Minecraft in this environment (no display, no game runtime).
The 20 in-game acceptance tests below therefore **have NOT been executed by me**. Per the task
rule ("either test on Lunar, or clearly write the work is incomplete and do not mark Fixed"),
every in-game row is marked **NOT RUN — pending user**. None are marked "Fixed".

What *was* verified here (see other reports):
- `./gradlew clean build` → **BUILD SUCCESSFUL**; built JAR installed into Lunar; hashes match.
- Headless rising-edge model for single-press → **ALL PASS**.
- Root causes located with decompiler/log evidence (blur path; fireball CPS branch).

---

## The 20 tests (to be run by the user on the new build)

| # | Test | Status | Code change that should make it pass |
|---|------|--------|--------------------------------------|
| 1 | Launch + enter world, no "Not Responding" | NOT RUN | vanilla blur removed from GUI (main freeze suspect) |
| 2 | Stay in world ≥ 15 min | NOT RUN | no per-frame I/O; no unbounded loops |
| 3 | Open/close GUI 20× | NOT RUN | no blur; no reflection on open |
| 4 | Navigate all pages | NOT RUN | sidebar nav rebuilt |
| 5 | Edit every setting several times | NOT RUN | typed bridge, live apply |
| 6 | Restart, values persist | NOT RUN | debounced save + save-on-close; typed save writes int/float correctly |
| 7 | AutoRight with blocks (CPS burst) | NOT RUN | `BLOCK` kind keeps burst in Block Mode |
| 8 | AutoRight with Fire Charge | NOT RUN | `SINGLE_PRESS` — one use per press |
| 9 | Hold Fire Charge → 1 use | NOT RUN | held ticks force useKey released |
| 10 | Change slot while holding → no auto-use | NOT RUN | single-press items never CPS; pass-through untouched |
| 11 | AutoLeft | NOT RUN | unchanged logic, CPS clamp |
| 12 | NinjaBridge | NOT RUN | unchanged logic; GUI now exposes Auto-Switch |
| 13 | Tracker HUD text | NOT RUN | unchanged HUD render |
| 14 | Red box around players | NOT RUN | unchanged world render |
| 15 | Aim | NOT RUN | unchanged aim logic |
| 16 | Change server/world | NOT RUN | state reset on world change |
| 17 | Exit to menu and back | NOT RUN | reset paths |
| 18 | Watch latest.log for repeated exceptions | NOT RUN | no empty catches; warnings only |
| 19 | No stuck keys | NOT RUN | `resetRightAutoClickState` forces key up on disable/GUI/exit |
| 20 | Players clearly visible behind GUI | NOT RUN | blur removed; light tint only |

## Recommended order for the user
1, 3, 20 first (they exercise the two biggest fixes: freeze + visibility). Then 8/9/10 (fireball),
then 5/6 (settings persistence), then the rest. Keep `latest.log` open (test 18).

If any test fails, capture `report/freeze-thread-dump.txt` (instructions inside) and the tail of
`latest.log`, and report which test number failed.
