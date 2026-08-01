package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter — catches up missed clicks accumulated between calls.
 *
 * <p>Returns 0 to N clicks — however many are due since the last call.
 * Missed time is never discarded; the limiter advances through all due clicks.
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

        // Count all clicks that are due
        int count = 0;
        while (nowNanos >= nextClickAt) {
            count++;
            scheduleNext(nextClickAt, cps);
        }
        return count;
    }

    private void scheduleNext(long base, int cps) {
        double baseNs = 1_000_000_000.0 / cps;
        double ratio = jitterRatio(cps);
        double minMultiplier = 1.0 - ratio;
        double maxMultiplier = 1.0 + ratio;
        double jittered = baseNs * (minMultiplier + RNG.nextDouble() * (maxMultiplier - minMultiplier));
        nextClickAt = base + (long) jittered;
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
