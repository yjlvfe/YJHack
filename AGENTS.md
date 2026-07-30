# PROJECT KNOWLEDGE BASE — YJHack-1.21.5

**Project:** YJHack Minecraft 1.21.5 Fabric client mod  
**Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4

## OVERVIEW

All-in-one client-side Fabric mod built entirely from `Dev/src/main/java`. There are no
precompiled class blobs or ASM injection. `clean test build` compiles the source, runs the
headless regression suite, remaps the Fabric jar, and only then copies
`YJHack-1.21.5.jar` to the project root.

## STRUCTURE

```text
YJHack-1.21.5/
├── .github/workflows/ci.yml
├── AGENTS.md
├── .gitignore
└── Dev/
    ├── build.gradle
    ├── gradlew / gradlew.bat
    ├── src/main/java/com/masteryj/
    ├── src/main/resources/fabric.mod.json
    └── src/test/java/com/masteryj/
```

## RUNTIME CONTRACT

- Seven client entrypoints are registered: the synthetic-action dispatcher, GUI, Tracker,
  AimAssist, AutoLeft, AutoRight, and NinjaBridge.
- AutoLeft and AutoRight submit held-action requests at `END_CLIENT_TICK`; the dispatcher
  revalidates and flushes them at the next `START_CLIENT_TICK`.
- Synthetic actions have one global fair ceiling: at most two total per client tick and forty
  total in any rolling second. Real vanilla input is never counted or throttled.
- The physical rising-edge click always belongs to vanilla. Automation never adds a duplicate
  synthetic click in the same tick.
- Pending work is cancelled on release, menu/focus loss, disable, death, world replacement,
  slot changes, or held-item changes. Denied work is dropped and never replayed.
- AutoRight treats Fire Charge and instant throwables as one-use-per-press, blocks as Block Mode,
  and bows/crossbows/tridents/shields/food and other hold items as vanilla pass-through.
- AimAssist requires an active left-button hold, focused captured gameplay, current FOV, and a
  fresh line-of-sight raycast. It does not steer while mining.
- NinjaBridge resets active/sneak/slot state on world changes and preserves an explicit
  `autoSwitch=false` during config migration.
- Tracker scans once per tick, reuses the snapshot for rendering, and clamps its HUD inside the
  current scaled window.

## BUILD AND CI

```bash
cd Dev
./gradlew clean test build --warning-mode all
```

`gradlew` uses the standard wrapper jar when present. On a historical clean clone where the jar
is absent, it uses an installed Gradle or bootstraps pinned Gradle 8.14.4 after SHA-256
verification. GitHub Actions runs the same clean/test/build gate on Java 21.

The release copy task depends on `check` and `remapJar`; a failed test cannot publish a new root
jar. Generated jars, runtime directories, reports, and Graphify outputs remain ignored.

## IMPORTANT PATHS

| Task | Location |
|---|---|
| Click cadence | `Dev/src/main/java/com/masteryj/core/ClickScheduler.java` |
| Global fair budget | `Dev/src/main/java/com/masteryj/core/ActionBudget.java` |
| Tick-start dispatcher | `Dev/src/main/java/com/masteryj/core/SyntheticActionDispatcherClient.java` |
| Left click | `Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java` |
| Right click policy | `Dev/src/main/java/com/masteryj/autoright/RightClickPolicy.java` |
| Tests | `Dev/src/test/java/com/masteryj/` |
| CI | `.github/workflows/ci.yml` |
