package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter — variable inter-click timing.
 *
 * <p>The jitter range scales with CPS: 10cps→±10%, 20cps→±15%, 40cps→±25%.
 * Higher CPS = wider timing variation = harder for anti-cheat to pattern-match.
 *
 * <p>At most 1 click per call, or 2 when behind schedule.
 */
public final class HumanizedCpsLimiter {

    public static final int MAX_CPS = 20;

    private static final double JITTER_INTERCEPT = 5.0;
    private static final double JITTER_SLOPE = 0.5;

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private final FixedCpsLimiter delegate = new FixedCpsLimiter();

    private long nextClickAt;
    private boolean initialized;

    public int acquire(long nowNanos, int configuredCps, boolean jitterEnabled) {
        int cps = FixedCpsLimiter.clampCps(configuredCps);
        if (!jitterEnabled) return delegate.acquire(nowNanos, cps) ? 1 : 0;
        return acquireJittered(nowNanos, cps);
    }

    public int acquire(long nowNanos, int configuredCps) {
        return acquire(nowNanos, configuredCps, false);
    }

    private int acquireJittered(long nowNanos, int cps) {
        if (!initialized) {
            initialized = true;
            scheduleNext(nowNanos, cps);
            return 1;
        }

        if (nowNanos < nextClickAt) return 0;

        int emitted = 1;
        if (nowNanos - nextClickAt > avgIntervalNs(cps)) {
            emitted = 2;
        }

        scheduleNext(nowNanos, cps);
        return emitted;
    }

    private void scheduleNext(long now, int cps) {
        double baseNs = 1_000_000_000.0 / cps;
        double ratio = jitterRatio(cps);
        // Jitter range scales with CPS: ratio from 10% to 25%
        double minMultiplier = 1.0 - ratio;
        double maxMultiplier = 1.0 + ratio;
        double jittered = baseNs * (minMultiplier + RNG.nextDouble() * (maxMultiplier - minMultiplier));
        nextClickAt = now + (long) jittered;
    }

    private long avgIntervalNs(int cps) {
        return (long) (1_000_000_000.0 / cps * 1.15);
    }

    public void clearTimingState() {
        initialized = false;
        delegate.clearTimingState();
    }

    public static double jitterRatio(int cps) {
        int safe = Math.max(1, Math.min(MAX_CPS, cps));
        return (JITTER_INTERCEPT + safe * JITTER_SLOPE) / 100.0;
    }
}
