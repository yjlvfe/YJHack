# Archived / stale reports

Files here are **out of date and were contradictory** with the actual project state.
They are kept only for the record. Do not treat them as current.

## report.html.stale
Copied from the pre-repair backup (`YJHack-1.21.5-BACKUP-20260723-150824/report/report.html`,
written 15:03 — before the source-build conversion at 15:31). It claims ModGui / Tracker /
AimAssist still run from pre-compiled `Dev/bin/main` blobs.

**That is false for the current tree.** Verified 2026-07-23:
- `Dev/bin` does not exist.
- `build.gradle` performs no class injection.
- All six modules compile from `Dev/src/main/java`; the built JAR has no duplicate classes.

The single current, authoritative report is **`report/comprehensive-repair-report-v2.html`**.
