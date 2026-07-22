# Debug Journal — local-only performance and assist repair

Started: 2026-06-23T06:31:02+03:00
Goal: Repair allowed local-only performance/AimAssist/Tracker issues without anti-cheat evasion, glow, or server-visible behavior.

## Environment snapshot (Phase 0)
- Runtime: Java 21 + Fabric Loom Minecraft client mod.
- Entry: `./gradlew build`; runtime QA entry later: `./gradlew runClient`.
- Git HEAD: workspace root status showed `.codegraph`, `.omo/`, `Fabric/` untracked from root perspective.
- Baseline build: `./gradlew build` in `Fabric/HelpNoob-Core` returned `BUILD SUCCESSFUL in 3s`.
- References read: debugging `references/methodology/00-setup.md`, `02-investigate.md`, `06-fix.md`, `08-qa.md`.

## Hypotheses
1. [OPEN] Poor play/network feel is caused by local tick/render load, not network packets — distinguishing evidence: runtime risk search shows no `sendPacket`, but Tracker scans players in both tick and render; fix is: reduce local work.
2. [OPEN] AimAssist feels broken because target acquisition over-filters by 7 block sample distance and does repeated raycasts/cache without cleanup — distinguishing evidence: `AimAssistClient.java:146-153`, `161-178`, `228-252`; fix is: align target window.
3. [OPEN] Tracker server-detection concern is due to wall-hitbox/glow-like world rendering, even though it is client-only — distinguishing evidence: `TrackerClient.java:96-135`; fix is: HUD-only tracker.
4. [OPEN] Config/defaults make modules active by surprise, amplifying perceived lag and risk — distinguishing evidence: `TrackerClient.Config.enabled=true`, AutoLeft/AutoRight/NinjaBridge enabled defaults; fix is: safe defaults.

## Failed hypothesis round counter
- Round 1: pending background exploration.

## Artifacts to revert
- [ ] `.debug-journal.md` — debug journal. Remove before final if not requested.
- [ ] `/tmp/ulw-20260623-063102.qUqfYH.md` — ultrawork notepad. Remove at cleanup if present.
- [ ] `build.gradle` — add JUnit test harness. Revert only if abandoning test-backed fixes.
- [ ] `src/test/java/com/masteryj/aimassist/AimAssistClientTest.java` — red tests for AimAssist config/range contracts.
- [ ] `src/test/java/com/masteryj/tracker/TrackerClientTest.java` — red tests for Tracker config/render contracts.

## Scenario contract
- Happy path: Local-only tracker has no glow/world box rendering and build grep finds no `setGlowing`, no packets, no world hitbox draw path. Real surface: `rg` + `./gradlew build`.
- Edge path: New/default configs do not activate heavy gameplay modules unless explicitly enabled. Real surface: generated config inspection after build/run-equivalent where feasible.
- Adjacent regression: AimAssist config clamps speed/smoothness/fov and build still passes. Real surface: unit test or compile-time harness + `./gradlew build`.

## Findings
- `./gradlew test` after harness returned `BUILD SUCCESSFUL`; test task was `NO-SOURCE`.
- `TrackerClient.java:61-63` registers tick, HUD, and world render callbacks.
- `TrackerClient.java:96-135` draws world hitboxes for tracked players; user explicitly forbids glow/server-visible-looking tracker.
- `TrackerClient.java:79` and `TrackerClient.java:114` scan all world players in tick and render.
- `AimAssistClient.java:146-153` rejects target samples beyond 49.0D while later `tickAimAssist` allows 64.0D; inconsistent range can make acquisition feel broken near boundary.
- `AimAssistClient.java:351-358` does not clamp speed/smoothness/fov on JSON load.
- Source search found no `sendPacket` and no `glow`/`setGlowing` in production source.

## Final fix
- Pending.
