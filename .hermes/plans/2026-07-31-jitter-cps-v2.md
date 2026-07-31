# YJHack v1.3.2 — Humanized Jitter CPS Implementation Plan

---

## ⚠️ CONSTRAINTS (Iron Rules)

1. **NO DELETION** — No existing code is removed. This is additive only.
2. `FixedCpsLimiter` **untouched**. The new `HumanizedCpsLimiter` wraps/extends it.
3. **AutoLeft + AutoRight ONLY**. AimAssist, Tracker, NinjaBridge, RecommendedSettings — all untouched.
4. One new toggle per module: `Jitter (Anti-Cheat)`. Default: **ON**.
5. No min/max CPS. The user's single `cps` value drives everything automatically.
6. Build, test, release JAR → `~/Downloads/`.

---

## DESIGN — HumanizedCpsLimiter

### Purpose
Add human-like inconsistency to click timing without changing the configured CPS baseline.

### How It Works

Every "batch window" has two properties randomized per batch:

| Property | Formula | Example (CPS=20) |
|----------|---------|-----------------|
| **Click count** | `cps ± floor(cps × jitterRatio)` | 17–23 clicks |
| **Window duration** | `1000ms ± (1000ms × jitterRatio)` | 800–1200ms |
| **Per-click microJitter** | `interval × random(0.7, 1.3)` | each click jitters |

Where `jitterRatio` grows with CPS:

| cps | jitterRatio |
|-----|------------|
| 10  | 0.10 (10%) |
| 20  | 0.15 (15%) |
| 30  | 0.20 (20%) |
| 40  | 0.25 (25%) |

Formula: `jitterRatio = (5.0 + cps * 0.5) / 100.0`

### Real-World Numbers

| CPS | Clicks/Batch Range | Window Range | Effective CPS Range |
|-----|-------------------|--------------|-------------------|
| 10  | 9–11              | 900–1100ms   | 8–12 CPS           |
| 20  | 17–23             | 850–1150ms   | 15–27 CPS          |
| 30  | 24–36             | 800–1200ms   | 20–45 CPS          |
| 40  | 30–50             | 750–1250ms   | 24–67 CPS          |

The long-term average stays at the configured CPS. Individual seconds vary wildly — exactly what anti-cheat cannot pattern-match.

### Per-Click Timing

Inside each batch, the total window is split into `count` intervals, but each interval gets a ±30% microJitter:

```
baseInterval = windowDuration / count
for each click:
  actualInterval = baseInterval * random(0.7, 1.3)
```

This prevents evenly-spaced click patterns even within a single batch.

---

## FILE-BY-FILE PLAN

---

### File 1 (NEW): `Dev/src/main/java/com/masteryj/core/HumanizedCpsLimiter.java`

```java
package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter that adds natural inconsistency to click timing.
 *
 * <p>Every batch window randomizes its click count, total duration, and per-click
 * micro-jitter. The long-term average stays at the configured CPS while individual
 * seconds vary unpredictably — specifically designed to thwart anti-cheat pattern
 * detection.
 *
 * <p>This is a WRAPPER — it delegates actual timing to the existing FixedCpsLimiter
 * underneath. When jitter is disabled, limiter falls back to the original fixed
 * behavior with zero overhead.
 */
public final class HumanizedCpsLimiter {

    public static final int MAX_CPS = 40;

    // jitterRatio = (5 + cps * 0.5) / 100  →  10cps→10%, 20→15%, 30→20%, 40→25%
    private static final double JITTER_INTERCEPT = 5.0;
    private static final double JITTER_SLOPE = 0.5;

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private final FixedCpsLimiter delegate = new FixedCpsLimiter();

    // Per-batch state
    private int remainingInBatch;
    private long batchStartNanos;
    private long batchIntervalNanos;

    /**
     * Returns true when a click is due this tick.
     *
     * @param nowNanos       current monotonic time
     * @param configuredCps  user's CPS setting (1–40)
     * @param jitterEnabled  whether anti-cheat jitter is enabled
     */
    public boolean acquire(long nowNanos, int configuredCps, boolean jitterEnabled) {
        int cps = FixedCpsLimiter.clampCps(configuredCps);

        if (!jitterEnabled) {
            // Pure passthrough — zero overhead, zero behavior change
            return delegate.acquire(nowNanos, cps);
        }

        return acquireJittered(nowNanos, cps);
    }

    private boolean acquireJittered(long nowNanos, int cps) {
        if (remainingInBatch <= 0) {
            generateBatch(cps, nowNanos);
        }

        if (nowNanos < batchStartNanos + batchIntervalNanos) {
            return false;
        }

        // Advance: micro-jittered interval
        long microJitter = (long) (batchIntervalNanos * (0.7 + RNG.nextDouble() * 0.6));
        batchStartNanos = nowNanos + microJitter;
        remainingInBatch--;

        return true;
    }

    private void generateBatch(int cps, long nowNanos) {
        double ratio = (JITTER_INTERCEPT + cps * JITTER_SLOPE) / 100.0;
        int countOffset = (int) Math.round(cps * ratio * (RNG.nextDouble() * 2.0 - 1.0));

        remainingInBatch = Math.max(1, cps + countOffset);

        long windowMs = Math.round(1000.0 * (1.0 + ratio * (RNG.nextDouble() * 2.0 - 1.0)));
        batchIntervalNanos = Math.max(1L, windowMs * 1_000_000L / remainingInBatch);
        batchStartNanos = nowNanos;
    }

    /** Fallback: no jitter (backward compatible). */
    public boolean acquire(long nowNanos, int configuredCps) {
        return acquire(nowNanos, configuredCps, false);
    }

    public void clearTimingState() {
        remainingInBatch = 0;
        delegate.clearTimingState();
    }

    public static double jitterRatio(int cps) {
        return (JITTER_INTERCEPT + Math.max(1, Math.min(MAX_CPS, cps)) * JITTER_SLOPE) / 100.0;
    }
}
```

---

### File 2 (NEW): `Dev/src/test/java/com/masteryj/core/HumanizedCpsLimiterTest.java`

```java
package com.masteryj.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanizedCpsLimiterTest {

    @Test
    void jitterRatioIncreasesWithCps() {
        double r10 = HumanizedCpsLimiter.jitterRatio(10);
        double r20 = HumanizedCpsLimiter.jitterRatio(20);
        double r40 = HumanizedCpsLimiter.jitterRatio(40);
        assertTrue(r20 > r10, "20 CPS should have more jitter than 10");
        assertTrue(r40 > r20, "40 CPS should have more jitter than 20");
        assertEquals(0.10, r10, 0.01);
        assertEquals(0.15, r20, 0.01);
        assertEquals(0.25, r40, 0.01);
    }

    @Test
    void disabledJitterPassesThrough() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 1_000_000_000L; t += 2_000_000L) {
            if (limiter.acquire(t, 20, false)) emitted++;
        }
        // With jitter OFF: exactly 20 CPS from fixed limiter
        assertEquals(20, emitted, "Disabled jitter = fixed CPS");
    }

    @Test
    void enabledJitterVariesOutput() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        // Run 5 seconds, count clicks per second
        int[] perSecond = new int[5];
        int second = 0;
        int clicks = 0;
        for (long t = 0; t < 5_000_000_000L; t += 1_000_000L) {
            int sec = (int) (t / 1_000_000_000L);
            if (sec != second) {
                perSecond[second] = clicks;
                clicks = 0;
                second = sec;
                if (second >= 5) break;
            }
            if (limiter.acquire(t, 20, true)) clicks++;
        }

        // Every second should differ (not all exactly 20)
        boolean allSame = true;
        for (int i = 1; i < perSecond.length; i++) {
            if (perSecond[i] != perSecond[0]) allSame = false;
        }
        assertFalse(allSame, "Jittered clicks should vary per second");

        // Long-term average should be near 20
        long total = 0;
        for (int c : perSecond) total += c;
        double avg = total / (double) perSecond.length;
        assertTrue(avg >= 16 && avg <= 24, "Average should be ~20, got " + avg);
    }

    @Test
    void clearTimingStateResetsEverything() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        limiter.acquire(0L, 20, true);
        limiter.acquire(50_000_000L, 20, true);
        limiter.clearTimingState();
        // After clear, should generate fresh batch
        int emitted = 0;
        for (long t = 0; t < 2_000_000_000L; t += 1_000_000L) {
            if (limiter.acquire(t, 20, true)) emitted++;
        }
        assertTrue(emitted > 0, "Should emit clicks after clear");
    }

    @Test
    void maxCapsAt40() {
        assertEquals(40, FixedCpsLimiter.clampCps(999));
        assertEquals(1, FixedCpsLimiter.clampCps(-5));
    }
}
```

---

### File 3 (MODIFY): `Dev/src/main/java/com/masteryj/autoleft/AutoLeftClient.java`

Changes (additive only):

**a) Add static field (near line 42):**
```java
public static boolean jitterEnabled = true;
```

**b) Replace `FixedCpsLimiter` field with `HumanizedCpsLimiter`:**
```java
// OLD:  private final LegacyMultiVersionCombatPolicy combatPolicy = ...
// NEW additive field:
private final HumanizedCpsLimiter clickLimiter = new HumanizedCpsLimiter();
```
(Keep `combatPolicy` — it still handles legacy multi-version logic. Just ADD the new limiter.)

**c) Modify `frame()` method — the `shouldRunDirectAttack` block (around line 124):**

Current:
```java
restoreVanillaAttack(client, false);
if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted)) {
    ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
}
```

Replace with:
```java
restoreVanillaAttack(client, false);
boolean wantsAction = combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted);
boolean jitterPassesGate = jitterEnabled
        ? clickLimiter.acquire(System.nanoTime(), cps, true)
        : combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
                enabled, activeGameplay, physicalDown, entityTargeted);
if (wantsAction && jitterPassesGate) {
    ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
}
```

Wait — this is too complex. Cleaner approach:

When jitter is ON, `clickLimiter.acquire()` REPLACES the combatPolicy timing check. The combatPolicy's non-timing logic (entity check, legacy compat) still runs first:

```java
restoreVanillaAttack(client, false);
if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted)) {
    // combatPolicy says conditions are met. Use jittered timing gate:
    if (clickLimiter.acquire(System.nanoTime(), cps, jitterEnabled)) {
        ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
    }
}
```

But this double-checks timing (combatPolicy does timing internally). Let's look at the real code flow.

**Simpler approach: Replace the internal FixedCpsLimiter in LegacyMultiVersionCombatPolicy**

Actually, `LegacyMultiVersionCombatPolicy` wraps `FixedCpsLimiter` internally. The cleanest approach: add a `jitterEnabled` parameter to its method, and pass the `HumanizedCpsLimiter` to it instead.

**NEW method in `LegacyMultiVersionCombatPolicy.java`:**
```java
public boolean shouldEmitFollowUp(long nowNanos, int cps,
        boolean enabled, boolean activeGameplay, boolean physicalDown, boolean entityTargeted,
        boolean jitterEnabled, HumanizedCpsLimiter jitterLimiter) {
    if (!enabled || !activeGameplay || !physicalDown || !entityTargeted) return false;
    if (jitterLimiter == null) return false;
    return jitterLimiter.acquire(nowNanos, cps, jitterEnabled);
}
```

**Modify AutoLeftClient.frame():**
```java
// OLD:
if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted)) {
    ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
}

// NEW:
if (clickLimiter.acquire(System.nanoTime(), cps, jitterEnabled)) {
    if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
            enabled, activeGameplay, physicalDown, entityTargeted)) {
        ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
    }
}
```

Wait — this changes order. The jitter timing should gate the action AFTER the policy check. Let me look at what `shouldEmitFollowUp` actually does...

Actually, the simplest additive approach: the combatPolicy still does its internal timing check, and we add a SECOND jitter gate:

```java
if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
        enabled, activeGameplay, physicalDown, entityTargeted)) {
    if (!jitterEnabled || clickLimiter.acquire(System.nanoTime(), cps, true)) {
        ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
    }
}
```

When jitter is OFF: always passes (clickLimiter not used), behavior unchanged.
When jitter is ON: combatPolicy says "yes" AND jitter says "yes" → fire.

This is a secondary gate — the jitter limiter reduces clicks further. The user gets fewer but human-like clicks.

**d) Update `clearRuntimeState()`:**
```java
private void clearRuntimeState() {
    combatPolicy.clearRuntimeState();
    clickLimiter.clearTimingState();
}
```

**e) Add to Config class:**
```java
public boolean jitterEnabled = true;

// In normalize(): nothing needed (boolean, default true)
// In copy():
result.jitterEnabled = jitterEnabled;
// In recommendedDefaults():
cfg.jitterEnabled = true;
// In applyRuntimeConfig():
jitterEnabled = cfg.jitterEnabled;
```

**f) Bump config version CURRENT_CONFIG_VERSION = 9, add migration:**
```java
if (configVersion < 9) {
    jitterEnabled = true;  // new field defaults to on
}
```

---

### File 4 (MODIFY): `Dev/src/main/java/com/masteryj/autoright/AutoRightClient.java`

Mirror changes from AutoLeft:

**a) Add static field:**
```java
public static boolean jitterEnabled = true;
```

**b) Add limiter field:**
```java
private final HumanizedCpsLimiter clickLimiter = new HumanizedCpsLimiter();
```

**c) Modify `tickRightAutoClick()` — the block placement section (around line 170):**

Current:
```java
int pulses = placementPolicy.pulsesThisTick(cps,
        enabled, activeGameplay, physicalDown, validCandidate);
for (int i = 0; i < pulses; i++) {
    if (!PhysicalKeyBinding.queuePress(client, client.options.useKey)) break;
}
```

Replace with:
```java
int pulses = placementPolicy.pulsesThisTick(cps,
        enabled, activeGameplay, physicalDown, validCandidate);
if (jitterEnabled) {
    pulses = clickLimiter.acquire(System.nanoTime(), cps, true) ? 1 : 0;
}
for (int i = 0; i < pulses; i++) {
    if (!PhysicalKeyBinding.queuePress(client, client.options.useKey)) break;
}
```

**d) Update `clearRuntimeState()`:**
```java
private void clearRuntimeState() {
    clearPressState();
    clickLimiter.clearTimingState();
}
```

**e) Add to Config:**
```java
public boolean jitterEnabled = true;
```

**f) Bump config version (CURRENT_CONFIG_VERSION = 10), add migration.**

---

### File 5 (MODIFY): `Dev/src/main/java/com/masteryj/modgui/ModGuiClient.java`

**a) AutoLeftScreen — after CPS slider (`addSlider(x, y + 66, w, "CPS", ...)`):**

```java
addDrawableChild(new ToggleSwitch(x, y + 96, w, 22, "Jitter (Anti-Cheat)", cfg.jitterEnabled, value -> {
    cfg.jitterEnabled = value;
    saveNow();
}));
```

**b) AutoLeftScreen — adjust winH to fit new control:**
```java
@Override
protected int winH() {
    return Math.max(340, Math.min(440, height - 20));
}
```

**c) AutoRightScreen — same ToggleSwitch addition (after its CPS slider):**

```java
addDrawableChild(new ToggleSwitch(x, y + 96, w, 22, "Jitter (Anti-Cheat)", cfg.jitterEnabled, value -> {
    cfg.jitterEnabled = value;
    saveNow();
}));
```

**d) AutoRightScreen — adjust winH:**
```java
@Override
protected int winH() {
    return Math.max(340, Math.min(440, height - 20));
}
```

---

### File 6 (MODIFY): `Dev/gradle.properties`

```
mod_version=1.3.1 → mod_version=1.3.2
```

---

## BUILD & RELEASE

```bash
cd Dev
./gradlew clean test build --warning-mode all
# Verify: BUILD SUCCESSFUL, all tests pass

# Copy JAR to Downloads
cp ../YJHack-1.3.2-mc1.21.5.jar ~/Downloads/
ls -lh ~/Downloads/YJHack-1.3.2-mc1.21.5.jar

# Commit & push
cd ..
git add -A
git commit -m "🔖 YJHack v1.3.2 — humanized anti-cheat jitter for AutoLeft & AutoRight

- New HumanizedCpsLimiter: randomized batch counts, durations, and per-click micro-jitter
- Jitter ratio scales with CPS: 10cps→10%, 20→15%, 30→20%, 40→25%
- One toggle per module: Jitter (Anti-Cheat), default ON
- Zero behavior change when jitter is OFF (FixedCpsLimiter fallback)
- Other modules untouched (AimAssist, Tracker, NinjaBridge)"

git push origin main
```

---

## MODIFIED FILES SUMMARY

| File | Type | Changes |
|------|------|---------|
| `core/HumanizedCpsLimiter.java` | NEW | Jitter engine |
| `core/HumanizedCpsLimiterTest.java` | NEW | 5 tests |
| `autoleft/AutoLeftClient.java` | MODIFY | +jitterEnabled field, +HumanizedCpsLimiter, +jitter gate in frame(), bump configVersion |
| `autoright/AutoRightClient.java` | MODIFY | Mirror of AutoLeft |
| `modgui/ModGuiClient.java` | MODIFY | +Jitter toggle in AutoLeftScreen & AutoRightScreen, taller windows |
| `autoleft/AutoLeftHoldPolicyTest.java` | MODIFY | +jitterEnabled default test |
| `autoright/AutoRightLongHoldPolicyTest.java` | MODIFY | +jitterEnabled default test |
| `gradle.properties` | MODIFY | version → 1.3.2 |

## UNTOUCHED (verified)

- `FixedCpsLimiter.java`
- `LegacyMultiVersionCombatPolicy.java`
- `LegacyMultiVersionPlacementPolicy.java`
- `RightClickPolicy.java`
- `config/RecommendedSettings.java`
- `aimassist/`, `tracker/`, `ninjabridge/`
- `mixin/`
- `build.gradle`
- `fabric.mod.json`
