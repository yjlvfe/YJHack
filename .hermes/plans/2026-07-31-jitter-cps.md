# Jitter CPS Implementation Plan — AutoLeft & AutoRight

> **Goal:** Add per-second jitter to AutoLeft & AutoRight CPS timing so reaching the configured CPS takes a variable amount of wall time (0.8s–1.5s), making the click pattern indistinguishable from a human. No other module is modified.

**Architecture:** Replace the current fixed-interval CPS model (`FixedCpsLimiter`) in both AutoLeft and AutoRight with a **jittered-interval model** where the time to complete one "CPS batch" varies randomly between 0.8–1.5s per second. The target CPS is achieved **on average** but each second's completion time differs. A new `JitteredCpsLimiter` is introduced in `core/` and used by both modules. GUI sliders remain 1–40 CPS with a new `jitter` toggle + `jitterStrength` slider per module.

**Tech Stack:** Java 21, Fabric Loom, Gradle 8.14.4, JUnit via fabric-loader-junit

**Key Constraint:** Touch ONLY: `core/`, `autoleft/`, `autoright/`, `modgui/ModGuiClient.java`, tests, config files. **DO NOT modify** `aimassist/`, `tracker/`, `ninjabridge/`, `config/RecommendedSettings.java`, `mixin/`, `build.gradle`, `gradle.properties`.

---

## Background — How It Works Now

```
FixedCpsLimiter.acquire(nowNanos, configuredCps):
  interval = 1_000_000_000 / cps
  if now >= nextActionAt: emit action, schedule next = now + interval
```

This produces exactly `cps` actions per second, every second. Predictable.

## How Jitter Changes This

Instead of `interval = 1_000_000_000 / cps`, each "second window" gets a **randomized total duration** between 0.8s and 1.5s:

```
jitteredMs = 800 + random.nextInt(701)   // 800..1500
actionsPerWindow = cps
interval = jitteredMs * 1_000_000 / cps   // spread cps clicks across jittered window
```

Since `FixedCpsLimiter` already schedules from `now` (no backlog), the jitter just changes the **sum** of all intervals in a rolling window. The per-click interval is `windowDuration / cps`.

A `jitterStrength` value (0.0–1.0) blends between:
- 0.0 → fixed (window = 1000ms, no jitter)  
- 1.0 → full range (window = 800–1500ms)

User can disable jitter entirely via toggle.

---

## Task Summary

| # | Task | Files |
|---|------|-------|
| 1 | Write failing tests for JitteredCpsLimiter | `core/JitteredCpsLimiterTest.java` |
| 2 | Implement JitteredCpsLimiter | `core/JitteredCpsLimiter.java` |
| 3 | Make FixedCpsLimiter support jitter (merge approach) | `core/FixedCpsLimiter.java` |
| 4 | Update AutoLeft config: add jitter fields | `autoleft/AutoLeftClient.java` |
| 5 | Wire AutoLeft to use jittered limiter | `autoleft/AutoLeftClient.java` |
| 6 | Update AutoRight config: add jitter fields | `autoright/AutoRightClient.java` |
| 7 | Wire AutoRight to use jittered limiter | `autoright/AutoRightClient.java` |
| 8 | Add AutoLeft jitter controls to GUI | `modgui/ModGuiClient.java` |
| 9 | Add AutoRight jitter controls to GUI | `modgui/ModGuiClient.java` |
| 10 | Update AutoLeft config migration tests | `autoleft/AutoLeftHoldPolicyTest.java` |
| 11 | Update AutoRight config migration tests | `autoright/AutoRightLongHoldPolicyTest.java` |
| 12 | Full test run to verify | `./gradlew clean test build` |
| 13 | Bump version, build JAR, move to Downloads | `gradle.properties`, `copyJar` |

---

## Task 1: Write failing tests for JitteredCpsLimiter

**Objective:** Define the contract for jittered CPS timing before implementation exists.

**Files:**
- Create: `Dev/src/test/java/com/masteryj/core/JitteredCpsLimiterTest.java`

**Step 1: Write the test file**

```java
package com.masteryj.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JitteredCpsLimiterTest {

    @Test
    void jitterZero_shouldBehaveLikeFixedCps() {
        JitteredCpsLimiter limiter = new JitteredCpsLimiter();
        long base = 0L;
        int cps = 20;
        int emitted = 0;

        // With jitter 0.0, window should be exactly 1_000ms
        for (long t = 0; t < 2_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, cps, 0.0)) emitted++;
        }
        // Over 2 seconds of ticks at 1ms resolution, expect ~40 actions
        assertTrue(emitted >= 36 && emitted <= 44, "Expected ~40, got " + emitted);
    }

    @Test
    void jitterFull_shouldVaryCompletionTime() {
        JitteredCpsLimiter limiter = new JitteredCpsLimiter();
        int cps = 10;
        long base = 0L;
        long firstCompletion = -1;
        int emitted = 0;

        // Run at 1ms resolution, measure when 10 clicks complete
        for (long t = 0; t < 3_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, cps, 1.0)) {
                emitted++;
                if (emitted == cps && firstCompletion == -1) {
                    firstCompletion = t;
                }
            }
        }
        // Completion should be between 0.8s and 1.5s (but test is probabilistic)
        assertTrue(firstCompletion >= 700_000_000L,
            "First batch should take at least 0.7s, got " + (firstCompletion / 1_000_000) + "ms");
        assertTrue(firstCompletion <= 1_600_000_000L,
            "First batch should take at most 1.6s, got " + (firstCompletion / 1_000_000) + "ms");
    }

    @Test
    void jitterStrengthHalf_shouldUseMidpointWindow() {
        JitteredCpsLimiter limiter = new JitteredCpsLimiter();
        int cps = 10;
        int emitted = 0;
        long firstCompletion = -1;

        for (long t = 0; t < 3_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, cps, 0.5)) {
                emitted++;
                if (emitted == cps && firstCompletion == -1) firstCompletion = t;
            }
        }
        // With 0.5 blend: window = 1000 + random(0..500) * 0.5, range ~1000-1250
        assertTrue(firstCompletion >= 900_000_000L,
            "Expected ≥0.9s, got " + (firstCompletion / 1_000_000) + "ms");
        assertTrue(firstCompletion <= 1_350_000_000L,
            "Expected ≤1.35s, got " + (firstCompletion / 1_000_000) + "ms");
    }

    @Test
    void disabledJitter_shouldUseFixedWindow() {
        JitteredCpsLimiter limiter = new JitteredCpsLimiter();
        // When jitterEnabled=false, should behave exactly like FixedCpsLimiter
        int cps = 20;
        int emitted = 0;
        for (long t = 0; t < 1_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, cps, false, 0.0)) emitted++;
        }
        assertEquals(20, emitted, "Disabled jitter should produce exactly cps per second");
    }

    @Test
    void clampStrengthOutOfRange() {
        // JitteredCpsLimiter.clampStrength should clamp to [0.0, 1.0]
        assertEquals(0.0, JitteredCpsLimiter.clampStrength(-0.5), 0.0001);
        assertEquals(1.0, JitteredCpsLimiter.clampStrength(1.5), 0.0001);
        assertEquals(0.7, JitteredCpsLimiter.clampStrength(0.7), 0.0001);
    }

    @Test
    void clearTimingState_resetsWindow() {
        JitteredCpsLimiter limiter = new JitteredCpsLimiter();
        limiter.acquire(0L, 10, 1.0);
        limiter.clearTimingState();
        // After clear, should generate a fresh window on next acquire
        int emitted = 0;
        for (long t = 0; t < 2_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, 10, 1.0)) emitted++;
        }
        assertTrue(emitted > 0, "Should emit after clear");
    }
}
```

**Step 2: Run to verify failure**

```bash
cd Dev && ./gradlew test --tests "com.masteryj.core.JitteredCpsLimiterTest" 2>&1 | tail -20
```
Expected: COMPILE ERROR — JitteredCpsLimiter class not found.

**Step 3: Commit**

```bash
git add Dev/src/test/java/com/masteryj/core/JitteredCpsLimiterTest.java
git commit -m "test(core): add JitteredCpsLimiter contract tests"
```

---

## Task 2: Implement JitteredCpsLimiter

**Objective:** Create the jittered limiter that passes Task 1's tests.

**Files:**
- Create: `Dev/src/main/java/com/masteryj/core/JitteredCpsLimiter.java`

**Step 1: Write the implementation**

```java
package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Monotonic limiter with per-window jitter. Each "second" window of {@code cps}
 * actions is stretched or compressed to a random duration between
 * {@code MIN_WINDOW_MS} and {@code MAX_WINDOW_MS}, blended by
 * {@code jitterStrength}.
 *
 * <p>At most one action is released per call. Missed time is always discarded
 * by scheduling from {@code now}, so lag, low FPS, menus and focus changes can
 * never create catch-up bursts.
 */
public final class JitteredCpsLimiter {

    public static final int MAX_CPS = 40;
    public static final long MIN_WINDOW_MS = 800L;
    public static final long MAX_WINDOW_MS = 1500L;
    public static final long FIXED_WINDOW_MS = 1000L;
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long MILLI_NANOS = 1_000_000L;

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private long nextActionAtNanos = Long.MIN_VALUE;
    private long currentWindowNanos;
    private int actionsRemainingInWindow;
    private int windowCps;

    /**
     * Returns true when exactly one action is due. Window jitter is recalculated
     * at the start of each batch (when all actions from the previous window are
     * exhausted).
     */
    public boolean acquire(long nowNanos, int configuredCps, boolean jitterEnabled,
                           double jitterStrength) {
        int cps = FixedCpsLimiter.clampCps(configuredCps);
        double strength = clampStrength(jitterStrength);
        long windowMs = jitterEnabled
                ? blendWindow(strength)
                : FIXED_WINDOW_MS;

        if (nextActionAtNanos == Long.MIN_VALUE || windowCps != cps
                || actionsRemainingInWindow <= 0) {
            // New window
            currentWindowNanos = windowMs * MILLI_NANOS;
            windowCps = cps;
            actionsRemainingInWindow = cps;
            long interval = Math.max(1L, currentWindowNanos / cps);
            nextActionAtNanos = nowNanos + interval;
            return false; // First tick schedules, doesn't emit
        }

        if (nowNanos < nextActionAtNanos) return false;

        long interval = Math.max(1L, currentWindowNanos / windowCps);
        nextActionAtNanos = nowNanos + interval;
        actionsRemainingInWindow--;
        return true;
    }

    /** Overload for backward compat when jitter is not configured. */
    public boolean acquire(long nowNanos, int configuredCps, double jitterStrength) {
        return acquire(nowNanos, configuredCps, jitterStrength > 0.0, jitterStrength);
    }

    /** Zero-jitter convenience. */
    public boolean acquire(long nowNanos, int configuredCps) {
        return acquire(nowNanos, configuredCps, false, 0.0);
    }

    private long blendWindow(double strength) {
        long jittered = MIN_WINDOW_MS + RNG.nextLong(MAX_WINDOW_MS - MIN_WINDOW_MS + 1L);
        return Math.round(FIXED_WINDOW_MS + (jittered - FIXED_WINDOW_MS) * strength);
    }

    public void clearTimingState() {
        nextActionAtNanos = Long.MIN_VALUE;
        windowCps = 0;
        actionsRemainingInWindow = 0;
    }

    public static double clampStrength(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
```

**Step 2: Run tests**

```bash
cd Dev && ./gradlew test --tests "com.masteryj.core.JitteredCpsLimiterTest" 2>&1 | tail -20
```
Expected: ALL TESTS PASS (7/7)

**Step 3: Commit**

```bash
git add Dev/src/main/java/com/masteryj/core/JitteredCpsLimiter.java
git commit -m "feat(core): add JitteredCpsLimiter with 0.8s–1.5s jitter window"
```

---

## Task 3: Update AutoLeft config — add jitter fields

**Objective:** Add `jitterEnabled`, `jitterStrength`, and `JitteredCpsLimiter` instance to AutoLeftClient.Config. Wire runtime fields. Upgrade configVersion.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java`

**Step 1: Add runtime fields (top of class, near line 40–48)**

```java
public static boolean jitterEnabled;
public static double jitterStrength = 0.5;
```

**Step 2: Add to Config class (near line 194)**

```java
public static final class Config {
    public int configVersion = CURRENT_CONFIG_VERSION;  // bump to 9 in Task 5
    public boolean enabled = false;
    public int toggleKeyCode = -1;
    public int cps = RecommendedSettings.AUTO_LEFT_CPS;
    public boolean jitterEnabled = true;
    public double jitterStrength = 0.5;

    public Integer minCps;
    public Integer maxCps;

    public static Config recommendedDefaults() {
        Config cfg = new Config();
        cfg.configVersion = CURRENT_CONFIG_VERSION;
        cfg.enabled = false;
        cfg.toggleKeyCode = -1;
        cfg.cps = RecommendedSettings.AUTO_LEFT_CPS;
        cfg.jitterEnabled = true;
        cfg.jitterStrength = 0.5;
        cfg.minCps = null;
        cfg.maxCps = null;
        cfg.normalize();
        return cfg;
    }

    public Config copy() {
        // ... add jitterEnabled, jitterStrength to copy
    }

    public void normalize() {
        // ... existing normalize logic ...
        configVersion = CURRENT_CONFIG_VERSION;
        cps = FixedCpsLimiter.clampCps(cps);
        jitterStrength = JitteredCpsLimiter.clampStrength(jitterStrength);
        toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        minCps = null;
        maxCps = null;
    }
}
```

**Step 3: Update applyRuntimeConfig**

```java
public static void applyRuntimeConfig(Config cfg) {
    if (cfg == null) return;
    config = cfg;
    enabled = cfg.enabled;
    toggleKeyCode = cfg.toggleKeyCode;
    cps = cfg.cps;
    jitterEnabled = cfg.jitterEnabled;
    jitterStrength = cfg.jitterStrength;
}
```

**Step 4: Commit**

```bash
git add Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java
git commit -m "feat(autoleft): add jitterEnabled and jitterStrength to config"
```

---

## Task 4: Wire AutoLeft to use JitteredCpsLimiter

**Objective:** Replace the FixedCpsLimiter usage in AutoLeft with JitteredCpsLimiter.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java`

**Step 1: Replace limiter field**

```java
// Remove: private final FixedCpsLimiter cpsLimiter = new FixedCpsLimiter();
// Replace with:
private final JitteredCpsLimiter clickLimiter = new JitteredCpsLimiter();
```

**Step 2: Replace call site in frame() — the combatPolicy.shouldEmitFollowUp call**

The current code uses `combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps, ...)`. We need to replace that pattern with the jittered limiter.

Actually, looking more carefully: AutoLeft uses `LegacyMultiVersionCombatPolicy.shouldEmitFollowUp` which internally uses `FixedCpsLimiter`. We modify the policy to accept a `JitteredCpsLimiter` or bypass it and use the jittered limiter directly in AutoLeftClient.

Simplest approach: **Bypass the combat policy's internal limiter** and use `clickLimiter.acquire()` directly in `AutoLeftClient.frame()`:

```java
// In frame(), replace the combatPolicy block with:
if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted)) {
    // Replace with jittered check:
    if (clickLimiter.acquire(System.nanoTime(), cps, jitterEnabled, jitterStrength)) {
        ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
    }
}
```

Wait — we need to be more careful. The `LegacyMultiVersionCombatPolicy` wraps the `FixedCpsLimiter` internally. For a clean approach, we modify the policy class to accept a pluggable limiter interface.

**Better approach — minimal change:** Modify `LegacyMultiVersionCombatPolicy` to accept a `JitteredCpsLimiter` parameter, and add a new `pulsesThisTick` method:

```java
// Add to LegacyMultiVersionCombatPolicy.java:
public int pulsesThisTick(long nowNanos, int configuredCps,
                          boolean jitterEnabled, double jitterStrength,
                          JitteredCpsLimiter limiter) {
    if (limiter == null) return 0;
    return limiter.acquire(nowNanos, configuredCps, jitterEnabled, jitterStrength) ? 1 : 0;
}
```

**Step 3: Update clearRuntimeState**

```java
private void clearRuntimeState() {
    combatPolicy.clearRuntimeState();
    clickLimiter.clearTimingState();
}
```

**Step 4: Commit**

```bash
git add Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java \
        Dev/src/main/java/com/masteryj/autoleft/LegacyMultiVersionCombatPolicy.java
git commit -m "feat(autoleft): wire JitteredCpsLimiter for jittered left-click timing"
```

---

## Task 5: Update AutoRight config — add jitter fields

**Objective:** Mirror Task 3 for AutoRight. Add `jitterEnabled`, `jitterStrength` to AutoRightClient.Config.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java`

Same pattern as Task 3: add static fields, add to Config, update normalize(), copy(), applyRuntimeConfig(), recommendedDefaults().

**Step 1: Commit**

```bash
git add Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java
git commit -m "feat(autoright): add jitterEnabled and jitterStrength to config"
```

---

## Task 6: Wire AutoRight to use JitteredCpsLimiter

**Objective:** Replace the FixedCpsLimiter in AutoRight's placementPolicy with JitteredCpsLimiter.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java`
- Modify: `Dev/src/main/java/com/masteryj/autoright/LegacyMultiVersionPlacementPolicy.java`

**Step 1: Add limiter field to AutoRightClient**

```java
private final JitteredCpsLimiter clickLimiter = new JitteredCpsLimiter();
```

**Step 2: Replace in tickRightAutoClick**

Current code:
```java
int pulses = placementPolicy.pulsesThisTick(cps,
        enabled, activeGameplay, physicalDown, validCandidate);
```

Replace with jittered version that also passes the limiter:
```java
int pulses = placementPolicy.pulsesThisTick(cps,
        enabled, activeGameplay, physicalDown, validCandidate,
        jitterEnabled, jitterStrength, clickLimiter);
```

Add method to `LegacyMultiVersionPlacementPolicy`:
```java
public int pulsesThisTick(int cps, boolean enabled, boolean active,
                          boolean physicalDown, boolean validCandidate,
                          boolean jitterEnabled, double jitterStrength,
                          JitteredCpsLimiter limiter) {
    if (limiter == null) return 0;
    return limiter.acquire(System.nanoTime(), cps, jitterEnabled, jitterStrength) ? 1 : 0;
}
```

**Step 3: Update clearRuntimeState/clearPressState**

```java
private void clearRuntimeState() {
    clearPressState();
    clickLimiter.clearTimingState();
}
```

**Step 4: Commit**

```bash
git add Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java \
        Dev/src/main/java/com/masteryj/autoright/LegacyMultiVersionPlacementPolicy.java
git commit -m "feat(autoright): wire JitteredCpsLimiter for jittered right-click timing"
```

---

## Task 7: Add jitter controls to AutoLeft GUI

**Objective:** Add `jitterEnabled` toggle + `jitterStrength` slider to the AutoLeft settings screen.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/modgui/ModGuiClient.java` (AutoLeftScreen inner class, ~line 525-565)

**Step 1: Add jitter ToggleSwitch and slider**

After the CPS slider (`addSlider(x, y + 66, w, "CPS", ...)`), add:

```java
addDrawableChild(new ToggleSwitch(x, y + 96, w, 22, "Jitter", cfg.jitterEnabled, value -> {
    cfg.jitterEnabled = value;
    saveNow();
}));
addSlider(x, y + 126, w, "Jitter Strength", 0.0, 1.0, cfg.jitterStrength, false,
        value -> cfg.jitterStrength = value);
```

Update `addHelp` for jitter.

**Step 2: Adjust winH to accommodate extra controls**

```java
@Override
protected int winH() {
    return Math.max(340, Math.min(480, height - 20));
}
```

**Step 3: Commit**

```bash
git add Dev/src/main/java/com/masteryj/modgui/ModGuiClient.java
git commit -m "feat(modgui): add jitter controls to AutoLeft screen"
```

---

## Task 8: Add jitter controls to AutoRight GUI

**Objective:** Mirror Task 7 for AutoRight screen.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/modgui/ModGuiClient.java` (AutoRightScreen inner class)

Same additions as Task 7 but in the AutoRightScreen.

**Step 1: Commit**

```bash
git add Dev/src/main/java/com/masteryj/modgui/ModGuiClient.java
git commit -m "feat(modgui): add jitter controls to AutoRight screen"
```

---

## Task 9: Update config version + migration

**Objective:** Bump configVersion for both AutoLeft (→9) and AutoRight (→10). Add migration for old configs without jitter fields.

**Files:**
- Modify: `Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java`
- Modify: `Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java`

**Step 1: AutoLeft normalize() migration**

```java
if (configVersion < 9) {
    jitterEnabled = true;
    jitterStrength = 0.5;
}
```

And bump `CURRENT_CONFIG_VERSION = 9` for AutoLeft.

**Step 2: AutoRight normalize() migration**

Same version bump and migration.

**Step 3: Commit**

```bash
git add Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java \
        Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java
git commit -m "feat(config): bump config versions for jitter migration"
```

---

## Task 10: Update tests to cover jitter config

**Objective:** Update existing config tests to verify jitter fields normalize correctly.

**Files:**
- Modify: `Dev/src/test/java/com/masteryj/autoleft/AutoLeftHoldPolicyTest.java`
- Modify: `Dev/src/test/java/com/masteryj/autoright/AutoRightLongHoldPolicyTest.java`
- Modify: `Dev/src/test/java/com/masteryj/config/ConfigNormalizationTest.java`

**Step 1: Add jitter normalization assertions**

```java
@Test
void jitterStrengthClampedInConfig() {
    AutoLeftClient.Config cfg = AutoLeftClient.Config.recommendedDefaults();
    cfg.jitterStrength = 99.0;
    cfg.normalize();
    assertEquals(1.0, cfg.jitterStrength, 0.001);

    cfg.jitterStrength = -5.0;
    cfg.normalize();
    assertEquals(0.0, cfg.jitterStrength, 0.001);
}
```

Same for AutoRight.

**Step 2: Commit**

---

## Task 11: Full test run + build

**Objective:** Run full test suite and verify everything passes.

```bash
cd Dev && ./gradlew clean test build --warning-mode all 2>&1
```
Expected: BUILD SUCCESSFUL, all tests pass (20+ JUnit + new ones).

**Step 1: Commit if any test file changes needed**

```bash
git add -A && git commit -m "test: finalize jitter tests"
```

---

## Task 12: Bump version, build release JAR, move to Downloads

**Objective:** Update version to `1.3.2`, build the JAR, copy to `~/Downloads`.

**Step 1: Bump version**

```bash
# Modify Dev/gradle.properties: mod_version=1.3.1 → mod_version=1.3.2
```

**Step 2: Clean build**

```bash
cd Dev && ./gradlew clean test build --warning-mode all
```

**Step 3: Move JAR to Downloads**

```bash
cp YJHack-1.3.2-mc1.21.5.jar ~/Downloads/
ls -lh ~/Downloads/YJHack-1.3.2-mc1.21.5.jar
```

**Step 4: Commit and push**

```bash
git add -A
git commit -m "🔖 release: YJHack v1.3.2 — jittered CPS for AutoLeft & AutoRight"
git push origin main
```

---

## Files Modified Summary

| File | Changes |
|------|---------|
| `core/JitteredCpsLimiter.java` | **NEW** — Jittered CPS limiter |
| `core/JitteredCpsLimiterTest.java` | **NEW** — Contract tests |
| `autoleft/AutoLeftClient.java` | +jitterEnabled, +jitterStrength fields; wire JitteredCpsLimiter; bump configVersion; add GUI jitter controls |
| `autoleft/LegacyMultiVersionCombatPolicy.java` | Add pulsesThisTick overload accepting JitteredCpsLimiter |
| `autoright/AutoRightClient.java` | Same as AutoLeft mirror |
| `autoright/LegacyMultiVersionPlacementPolicy.java` | Add pulsesThisTick overload |
| `modgui/ModGuiClient.java` | Add jitter Toggle + Strength slider to both screens |
| `autoleft/AutoLeftHoldPolicyTest.java` | Add jitter config normalization test |
| `autoright/AutoRightLongHoldPolicyTest.java` | Same |
| `config/ConfigNormalizationTest.java` | Add jitter clamp assertions |
| `gradle.properties` | mod_version → 1.3.2 |

## Files NOT Modified (by design)
- `aimassist/`, `tracker/`, `ninjabridge/` — untouched
- `config/RecommendedSettings.java` — untouched (jitter defaults live in each Config)
- `mixin/` — untouched
- `build.gradle` — untouched
- `.github/workflows/ci.yml` — untouched
