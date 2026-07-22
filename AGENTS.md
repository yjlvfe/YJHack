# PROJECT ROOT — YJHack-1.21.5

**Generated:** 2026-07-22
**Project:** YJHack Minecraft 1.21.5 Fabric client mod

## OVERVIEW
All-in-one client-side Fabric mod for Minecraft 1.21.5 — 6 modules packed into one JAR. Source lives in `Dev/`.

```
YJHack-1.21.5/
├── YJHack.jar              # Master reference — original working JAR
├── YJHack-Project-Details.md  # Full Arabic documentation (340 lines)
├── AGENTS.md               # ← this file
└── Dev/                    # ← All code, config, build lives here
```

## WHERE TO LOOK
| What | Where | Notes |
|------|-------|-------|
| **Everything** | `Dev/` | Gradle project root with source, pre-compiled classes, build system |
| **Source modules** | `Dev/src/main/java/com/masteryj/` | 4 Java files: autoleft, autoright, ninjabridge, tracker |
| **Pre-compiled modules** | `Dev/bin/main/com/masteryj/` | aimassist, modgui (ASM-patched) from original JAR |
| **Build output** | `YJHack-1.21.5.jar` | Final distributable JAR (copied here by build) |
| **Original reference** | `YJHack.jar` | Working JAR that pre-compiled classes were extracted from |
| **Detailed docs** | `YJHack-Project-Details.md` | Complete module-by-module analysis in Arabic |

## KEY FACTS
- **Stack:** Minecraft 1.21.5 + Fabric Loom 1.10.5 + Java 21 + Gradle 8.14.4
- **No git** — project is not version-controlled
- **6 entrypoints** (load order): ModGui → Tracker → AimAssist → AutoLeft → AutoRight → NinjaBridge
- **CPS**: `randomInt(minCps, maxCps)` — no Gaussian, no jitter, no fluctuation
- **GUI**: Original compiled classes from `YJHack.jar`, ASM-patched (meanCps→minCps, stdDev→maxCps)
- See `Dev/AGENTS.md` for full details

## COMMANDS
```bash
cd Dev && ./gradlew build          # Build → ../YJHack-1.21.5.jar
cd Dev && ./gradlew clean build    # Clean rebuild
cd Dev && ./gradlew runClient      # Launch MC
```
