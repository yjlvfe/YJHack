# YJHack-1.21.5 — Cleanup Candidates (reference-checked)

**Baseline:** commit `4368fa5` / tag `stable-before-final-cleanup` · stable jar SHA-256 `706daa61…`
**Method:** every candidate below was checked with *multiple* signals, not a single text search:
Python AST-ish symbol counting, `grep`/`ugrep`, `git ls-files`, jar `unzip -l` inspection,
`fabric.mod.json` entrypoint list, Gson-serialization awareness, and Fabric callback registration.

**Confidence legend:** 🟢 high (safe to remove) · 🟡 medium (safe, low value) · 🔴 low (KEEP)

**Discovery note (important):** The current source tree is already small and disciplined. There is
**no** `Dev/bin`, **no** blob-injection, **no** duplicate module classes, **no** `HelpNoob`/`READY`/
`Locked modules` strings, **no** `.class` files in `src`, **no** stray `.bak/.orig/.tmp/.log/.patch`
files. So this pass is genuinely "remove the last dead bits + reorganize", not a large excavation.

---

## A. Dead code — source (`Dev/src/main/java`)

Legend for each: *path* · *type* · *why dead* · *reference search* · *in git?* · *in jar?* ·
*entrypoint?* · *reflection?* · *JSON/resource?* · *decision* · *confidence*

### A1. Unused named constants (literal was inlined by the decompiler)

| Symbol | File | Refs (decl+use) | Decision |
|---|---|---|---|
| `MOUSE_KEY_OFFSET` | aimassist/AimAssistClient.java:38 | 1 (decl only) | delete 🟢 |
| `CONFIG_RELOAD_INTERVAL_MS` | aimassist/AimAssistClient.java:36 | 1 (decl only; body uses literal `5000L`) | delete 🟢 |
| `BLOCK_BREAK_FOCUS_MS` | aimassist/AimAssistClient.java:37 | 1 (decl only; body uses literal `500L`) | delete 🟢 |
| `MOUSE_KEY_OFFSET` | autoleft/AutoLeftClient.java:39 | 1 (decl only; body uses literal `1000`) | delete 🟢 |
| `MOUSE_KEY_OFFSET` | autoright/AutoRightClient.java:37 | 1 (decl only; body uses literal `1000`) | delete 🟢 |
| `MOUSE_KEY_OFFSET` | tracker/TrackerClient.java:42 | 1 (decl only) | delete 🟢 |
| `WALL_HITBOX_EXPAND` | tracker/TrackerClient.java:40 | 1 (decl only; body uses literal `0.03`) | delete 🟢 |
| `HITBOX_BUFFER_SIZE` | tracker/TrackerClient.java:41 | 1 (decl only; body uses literal `16384`) | delete 🟢 |
| `CONFIG_RELOAD_INTERVAL_MS` | tracker/TrackerClient.java:39 | 1 (decl only; body uses literal `5000L`) | delete 🟢 |

- **why dead:** `private static final` with zero references outside its own declaration line
  (verified by regex word-count across each file). A `private` constant cannot be referenced from
  outside the class, so zero in-file refs ⇒ provably unused.
- **in git?** yes (part of the tracked source) · **in jar?** no (constants inline into bytecode; an
  unused one leaves no trace) · **entrypoint?** no · **reflection/JSON?** no.
- **NOT dead (kept):** `CONFIG_RELOAD_INTERVAL_MS` in AutoLeft/AutoRight/NinjaBridge (2 refs each —
  actually used); `MOUSE_KEY_OFFSET` in ModGuiClient (5 refs — used by `normalizeKey`/`encodeMouse`/
  `keyName`).

### A2. Unused Theme colours + unused helper (modgui/ModGuiClient.java)

| Symbol | Line | Refs | Decision |
|---|---|---|---|
| `Theme.ACCENT_DIM` | 104 | 0 uses | delete 🟢 |
| `Theme.ERROR` | 112 | 0 uses | delete 🟢 |
| `Theme.FOOTER` | 93 | 0 uses | delete 🟢 |
| `normalizeKey(int)` | 144 | decl only, never called | delete 🟢 |

- **why dead:** color constants never referenced in any `fill`/`drawText`/`panel` call; `normalizeKey`
  is a leftover helper — the codebase normalizes keys inside each module, not here. `encodeMouse`,
  `keyName`, `fmt`, `panel`, `pill` are all still used (kept).
- **reflection/JSON?** no · **entrypoint?** ModGuiClient itself is an entrypoint but these symbols are
  private internals, not referenced by the entrypoint contract.

### A3. Redundant explicit no-arg constructors (decompiler artifacts)

| Symbol | Line | Decision |
|---|---|---|
| `public TrackerClient()` | tracker/TrackerClient.java:56 | delete 🟢 |
| `public AimAssistClient()` | aimassist/AimAssistClient.java:59 | delete 🟢 |
| `public Config()` (Tracker) | tracker/TrackerClient.java:367 | delete 🟢 |
| `public Config()` (AimAssist) | aimassist/AimAssistClient.java:436 | delete 🟢 |

- **why safe:** each class declares no *other* constructor, so the compiler emits an identical implicit
  public no-arg constructor. Fabric entrypoint instantiation (no-arg reflection) and Gson
  deserialization (no-arg) both keep working — **byte-for-byte-equivalent** constructor.
- Other modules (AutoLeft/AutoRight/NinjaBridge/ModGui) already omit the redundant constructor.

### A4. Stale comment describing a removed system (ninjabridge/NinjaBridgeClient.java:51–59)

- 3 comment lines describe a *removed* "reflection-based untoggleMethod". The reflection is gone;
  the comment documents a system that no longer exists. **Decision:** trim to a one-line note 🟢
  (keep a short "direct setPressed()" comment; drop the removed-reflection narrative).

### A5. KEEP — looks removable but is NOT (🔴 do not delete)

| Symbol | Why it must stay |
|---|---|
| `RightClickPolicy.shouldAutoRepeat(...)` | Spec-required policy API. Currently the tick loop uses `classify()`+`blockMode`, but this method is the documented contract and will be **covered by new JUnit tests**. Removing it would violate the Fire-Charge policy requirement. |
| All `Config` public fields | Gson-serialized to/from the JSON config files. "Unused in Java" ≠ unused — they are read/written by reflection. |
| All `public static` module fields/methods (`config`, `enabled`, `applyRuntimeConfig`, `saveConfigStatic`, …) | The typed-config bridge / GUI reads and writes these across classes. |
| `HudRenderCallback` / `WorldRenderEvents` / `ClientTickEvents` registrations | Live Fabric callbacks (Tracker HUD, red box, all tick loops). |
| `Config.normalize()/norm()` version-bump blocks | Config migration for old user files — protected by section 九 of the task. |

---

## B. Files & folders

| Path | Type | Status | Reason | Decision | Conf |
|---|---|---|---|---|---|
| `Dev/AUDIT_REPORT.md` | doc (tracked) | stale | Pre-repair audit (2026-07-22) that speaks of "the four source files" — predates the 6-source-module state. A report living in the source tree. | `git mv` → `report/_archive/` | 🟢 |
| `Dev/.debug-journal.md` | doc (tracked, hidden) | stale | Journal referencing removed `HelpNoob-Core`, `Fabric/`, `.omo/`, `.codegraph` — a project structure that no longer exists. | `git mv` → `report/_archive/debug-journal.md` | 🟢 |
| `Dev/.classpath` | Eclipse IDE (untracked/ignored) | regenerable | Local IDE metadata; not needed by build; regenerated by the IDE. | delete | 🟡 |
| `Dev/.project` | Eclipse IDE (untracked/ignored) | regenerable | same | delete | 🟡 |
| `Dev/.factorypath` | Eclipse IDE (untracked/ignored) | regenerable | same | delete | 🟡 |
| `Dev/.settings/` | Eclipse IDE (untracked/ignored) | regenerable | annotation-processor prefs; regenerated by the IDE. | delete | 🟡 |
| `report/_archive-stale/` | old archive (tracked) | archive | Even-older stale report; user wants a single `report/_archive/`. | `git mv` → `report/_archive/stale-v1/` | 🟢 |
| `report/SinglePressModelTest.java` | test-in-wrong-place (tracked) | move | A `.java` test file living under `report/`. | convert → `Dev/src/test/java/.../RightClickPolicyTest`; remove from `report/` | 🟢 |
| `report/comprehensive-repair-report-v2.html` | prior official report (tracked .html) | archive | The authoritative record of the repair that produced the baseline. Important history. | untrack (`git rm --cached`, honor `report/*.html`) + move to `report/_archive/` | 🟢 |
| `report/{runtime-diagnosis.md, freeze-thread-dump.txt, lunar-test-results.md, right-click-policy.md, gui-before-after.md, jar-verification.txt, final-build-output.txt, final-modified-files.txt}` | prior supporting reports (tracked) | archive | Supporting evidence for the repair-v2 work. Keep for review, out of the top level. | `git mv` → `report/_archive/` | 🟢 |
| `report/YJHack-1.21.5.jar` | **frozen baseline reference** (untracked/ignored) | KEEP | The immutable Lunar-verified build. **Never modify.** | keep untouched | 🔴 |
| root `YJHack-1.21.5.jar` | build output (untracked/ignored) | KEEP/regenerate | The current testable jar; produced by `copyJar`. Not a duplicate committed to git. | leave (gitignored) | 🔴 |
| `Dev/build/`, `Dev/.gradle/` | generated (ignored) | clean-managed | Never delete from the build system; already gitignored; `clean` handles them. | leave to Gradle | 🔴 |
| `graphify-out/**` | generated (ignored) | rebuild | Archived + rebuilt in the Graphify step. | rebuild at end | 🟡 |

**No duplicate JARs are committed to git** (`git ls-files '*.jar'` = empty). The two on-disk jars are
(1) the frozen baseline reference and (2) the regenerated build output — distinct, both gitignored,
both intentional. Documented in `final-jar-verification.txt`.

---

## C. Gradle (`Dev/build.gradle`, `Dev/settings.gradle`, `gradle.properties`)

| Item | Location | Reason | Decision | Conf |
|---|---|---|---|---|
| Hardcoded absolute path | build.gradle:65 (`clean.doLast { delete file('/home/masteryj/…/YJHack-1.21.5.jar') }`) | Machine-specific absolute path; breaks on any other checkout. | replace with relative `file('../YJHack-1.21.5.jar')` (same behavior) | 🟢 |
| Stale comment | build.gradle:31–40 (references `bin/main` blob + nonexistent `report/comprehensive-repair-report.html`) | Describes removed system + wrong filename. | trim to a short, accurate note | 🟢 |
| `maven-publish` plugin | build.gradle:3 | Applied but there is **no** `publishing{}` block and nothing publishes; a client mod isn't published. | remove plugin (verify build after) | 🟡 |

**KEEP:** the four `dependencies` (minecraft/yarn/fabric-loader/fabric-api — all required), both
`repositories`, the `copyJar` task (the user's requested "jar next to project root" flow),
`processResources` expand (needed for `${version}`), `withSourcesJar()` (produces a real artifact).

---

## D. GUI text reduction (modgui/ModGuiClient.java) — *content, not deletion*

Not "dead code" but the explicit crowding requirement. Design/colours/transparency unchanged.

| Location | Now | Change |
|---|---|---|
| Dashboard card | 4 text lines (label, desc, `summary`, `Key: …`) | 3 lines (label, short desc, one value); move Key + secondary value to hover tooltip |
| Dashboard header subtitle (790) | "Click a card to configure. Everything below is live." | "Select a module to configure" |
| Dashboard header status (730) | "5/5 modules on" | "5 active" |
| AimAssist card summary (770) | "Speed 0.24 • FOV 70" + Key line | "Speed 0.24" (FOV + Key → tooltip) |
| Settings pages | inline label + tooltip already | keep; ensure no duplicated module name / no Saved-Live-Current spam (already absent) |

---

## E. Decisions summary

- **Delete (source):** 9 dead constants + 3 unused Theme colours + `normalizeKey` + 4 redundant
  constructors; trim 1 stale comment block. All 🟢, all behavior-preserving.
- **Reorganize (files):** archive 2 Dev docs + 10 report artifacts into `report/_archive/`; delete 4
  Eclipse IDE artifacts (regenerable); move the model test into `src/test` as real JUnit.
- **Gradle:** relativize 1 path, trim 1 comment, drop `maven-publish`.
- **GUI:** reduce card/header text ~30–40%, design untouched.
- **Nothing removed at low confidence.** Every 🔴 item is explicitly kept with a reason.
