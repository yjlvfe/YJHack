# PROJECT KNOWLEDGE BASE — YJHack-1.21.5

**Generated:** 2026-07-23
**Project:** YJHack Minecraft 1.21.5 Fabric client mod
**Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4

## OVERVIEW
All-in-one client-side Fabric mod — 6 modules bundled into one JAR. Source in `Dev/`; pre-compiled classes from original `YJHack.jar` in `Dev/bin/main/`.

## STRUCTURE
```
YJHack-1.21.5/
├── YJHack.jar                     # Master reference — original working JAR
├── YJHack-Project-Details.md      # Full Arabic documentation (340 lines)
├── AGENTS.md                      # ← this file
└── Dev/                           # ← Gradle project root (see Dev/AGENTS.md)
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| **Everything** | `Dev/` | Gradle project with source, pre-compiled classes, build system |
| **Source modules** | `Dev/src/main/java/com/masteryj/` | 4 Java files: autoleft, autoright, ninjabridge, tracker |
| **Pre-compiled modules** | `Dev/bin/main/com/masteryj/` | aimassist, tracker, modgui (ASM-patched from YJHack.jar) |
| **Build output** | `YJHack-1.21.5.jar` | Final distributable (copied here by build) |
| **Original reference** | `YJHack.jar` | Working JAR pre-compiled classes extracted from |
| **Detailed docs** | `YJHack-Project-Details.md` | Complete module-by-module analysis in Arabic |

## KEY FACTS
- **REPAIRED 2026-07-23:** now **100% source-based** — `Dev/bin/` blobs removed, all 6 modules
  build from `src/main/java`. See `report/comprehensive-repair-report.html`.
- **Git**: tracked; `.gitignore` present (ignores build/, run/, graphify-out/, generated jars/html)
- **6 entrypoints** (load order): ModGui → Tracker → AimAssist → AutoLeft → AutoRight → NinjaBridge
- **CPS:** `randomInt(minCps, maxCps)` — no Gaussian, no jitter, no fluctuation
- **GUI:** real source (`modgui/ModGuiClient.java`), recovered from the working v1.0.0 JAR
- **See `Dev/AGENTS.md`** for full build pipeline, conventions, anti-patterns, commands

## COMMANDS
```bash
cd Dev && ./gradlew build          # Build → ../YJHack-1.21.5.jar
cd Dev && ./gradlew clean build    # Clean rebuild
cd Dev && ./gradlew runClient      # Launch MC (requires assets)
```