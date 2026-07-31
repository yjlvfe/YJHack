package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter.
 *
 * <p>Returns 1 click per call normally. When the configured CPS exceeds the
 * call rate (e.g. 40 CPS at 20 ticks/sec), two clicks may fire in one tick to
 * reach the target. The jitter comes from randomized batch sizes and per-click
 * micro-jittered intervals.
 */
public final class HumanizedCpsLimiter {

    public static final int MAX_CPS = 40;

    private static final double JITTER_INTERCEPT = 5.0;
    private static final double JITTER_SLOPE = 0.5;
    private static final double MICRO_MIN = 0.7;
    private static final double MICRO_RANGE = 0.6;

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private final FixedCpsLimiter delegate = new FixedCpsLimiter();

    private int remaining;
    private long nextNanos;
    private long intervalNs;
    private long lastCallNanos = Long.MIN_VALUE;
    private transient int callRate; // estimated calls per second

    public int acquire(long nowNanos, int configuredCps, boolean jitterEnabled) {
        int cps = FixedCpsLimiter.clampCps(configuredCps);
        if (!jitterEnabled) return delegate.acquire(nowNanos, cps) ? 1 : 0;
        return acquireJittered(nowNanos, cps);
    }

    public int acquire(long nowNanos, int configuredCps) {
        return acquire(nowNanos, configuredCps, false);
    }

    private int acquireJittered(long nowNanos, int cps) {
        if (lastCallNanos != Long.MIN_VALUE) {
            long gap = nowNanos - lastCallNanos;
            if (gap > 0) callRate = (int) (1_000_000_000L / gap);
        }
        lastCallNanos = nowNanos;

        if (remaining <= 0) {
            generateBatch(cps, nowNanos);
        }

        int maxPerTick = Math.max(1, cps / Math.max(1, callRate));
        int emitted = 0;

        while (remaining > 0 && nowNanos >= nextNanos && emitted < maxPerTick) {
            remaining--;
            emitted++;
            if (remaining > 0) {
                long jitter = (long) (MICRO_MIN + RNG.nextDouble() * MICRO_RANGE);
                nextNanos += jitter * intervalNs;
            }
        }

        // If we missed several (lag), skip them but count at most 1 extra
        while (remaining > 0 && nowNanos >= nextNanos) {
            remaining--;
            if (remaining > 0) {
                long jitter = (long) (MICRO_MIN + RNG.nextDouble() * MICRO_RANGE);
                nextNanos += jitter * intervalNs;
            }
        }

        return emitted;
    }

    private void generateBatch(int cps, long nowNanos) {
        double ratio = jitterRatio(cps);
        int offset = (int) Math.round(cps * ratio * (RNG.nextDouble() * 2.0 - 1.0));
        remaining = Math.max(1, cps + offset);

        double windowMs = 1000.0 * (1.0 + ratio * (RNG.nextDouble() * 2.0 - 1.0));
        intervalNs = Math.max(1L, (long) (windowMs * 1_000_000L / remaining));
        nextNanos = nowNanos;
    }

    public void clearTimingState() {
        remaining = 0;
        lastCallNanos = Long.MIN_VALUE;
        delegate.clearTimingState();
    }

    public static double jitterRatio(int cps) {
        int safe = Math.max(1, Math.min(MAX_CPS, cps));
        return (JITTER_INTERCEPT + safe * JITTER_SLOPE) / 100.0;
    }
}
