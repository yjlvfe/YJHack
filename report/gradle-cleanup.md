# Gradle cleanup

Scope: `Dev/build.gradle`, `Dev/settings.gradle`, `Dev/gradle.properties`. Goal: remove
unused/stale bits and fix build warnings **without changing build outputs' behavior**.

## Changes

| # | Change | Reason | Behavior impact |
|---|---|---|---|
| 1 | Removed `id 'maven-publish'` plugin | Applied but no `publishing{}` block; a client mod is never published. Nothing referenced any publish task. | none — no publish task existed or was used |
| 2 | Trimmed the big banner comment | It described the removed `bin/main` blob-injection + `extractGoodModClasses`/`mergeGoodModClasses` hacks and pointed at a **nonexistent** file `report/comprehensive-repair-report.html`. | none (comment only); kept a short "do not reintroduce bin/main" note |
| 3 | `clean.doLast`: replaced hardcoded absolute path `delete file('/home/masteryj/Desktop/Mod Maker/YJHack-1.21.5/YJHack-1.21.5.jar')` with a config-time-resolved `File` + `rootConvenienceJar.delete()` | The absolute path was machine-specific and broke on any other checkout; the old form also triggered the `Task.project`-at-execution deprecation. | identical — still removes `../YJHack-1.21.5.jar` on `clean` (verified: file gone after `gradlew clean`) |
| 4 | `processResources`: capture `version`/`loader_version` into locals at configuration time before the `filesMatching` closure | The `expand version: project.version …` closure dereferenced `Task.project` at execution time (Gradle 10 / configuration-cache deprecation). | identical — `fabric.mod.json` still expands to `version 1.2.0`, `fabricloader >=0.16.10` (verified in built jar) |
| 5 | Added `testImplementation "net.fabricmc:fabric-loader-junit:${loader_version}"` + `test { useJUnitPlatform() }` | Enable the new JUnit suite (previously `test` was `NO-SOURCE`). | new: `test` now runs 19 tests |

## Warnings — before vs after

**Before (baseline):**
```
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
  → Invocation of Task.project at execution time (build.gradle, expand closure + clean.doLast)
Note: TrackerClient.java uses or overrides a deprecated API.  (HudRenderCallback)
```

**After:**
```
Note: TrackerClient.java uses or overrides a deprecated API.  (HudRenderCallback) — INTENTIONAL, documented
```
The Gradle `Task.project` deprecation is **resolved**. `./gradlew build --warning-mode all` no
longer prints "Deprecated Gradle features were used".

## HudRenderCallback (deprecated) — deliberately retained

- **Exact site:** `tracker/TrackerClient.java:56` → `HudRenderCallback.EVENT.register(this::renderHiddenEnemyHud)`.
- **Why kept:** deprecated in Fabric API 0.119, but migrating to the newer HUD-layer API
  (`HudLayerRegistrationCallback` / `HudElementRegistry`) changes draw ordering/layering of the
  hidden-enemy alert. The Tracker HUD behaviour is explicitly protected from any change, so the
  migration is out of scope for a cleanup pass.
- **Not hidden:** the javac deprecation *Note* is left visible on every build (no `-Xlint` suppression,
  no `@SuppressWarnings`), and a code comment at the call site documents the decision.

## Kept (verified still required — not removed)

- Dependencies: `minecraft`, `yarn` mappings, `fabric-loader`, `fabric-api` — all required.
- `repositories { maven fabricmc; mavenCentral }` — both resolve real artifacts.
- `copyJar` task + `build.finalizedBy copyJar` — the user's "jar next to project root" test flow.
- `withSourcesJar()` — produces a real `-sources` artifact (not dead).
- `settings.gradle` (`pluginManagement`, `rootProject.name='YJHack'`) and `gradle.properties`
  (versions) — unchanged; no stale entries found.

## Verification

- `./gradlew clean` → removes `../YJHack-1.21.5.jar` (confirmed).
- `./gradlew clean build --warning-mode all` → `BUILD SUCCESSFUL`, only the documented Hud note.
- Built jar: 32 classes, 6 entrypoints, `fabric.mod.json` version `1.2.0` — unchanged structure.
