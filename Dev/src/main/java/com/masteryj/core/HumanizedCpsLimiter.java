package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter — makes clicks look like a real person, not a robot.
 *
 * <p>Every batch of clicks has a randomized count and duration. Inside each batch,
 * the gap between clicks is also randomized (micro-jitter). The long-term average
 * stays at the user's configured CPS, but every second looks different — no pattern
 * for anti-cheat to detect.
 *
 * <p>When jitter is OFF, this delegates directly to FixedCpsLimiter with zero
 * overhead.
 *
 * <p>Jitter ratio scales with CPS:
 * <pre>
 *   10 CPS → 10% jitter
 *   20 CPS → 15% jitter
 *   30 CPS → 20% jitter
 *   40 CPS → 25% jitter
 * </pre>
 */
public final class HumanizedCpsLimiter {

    public static final int MAX_CPS = 40;

    private static final double JITTER_INTERCEPT = 5.0;
    private static final double JITTER_SLOPE = 0.5;
    private static final double MICRO_JITTER_MIN = 0.7;
    private static final double MICRO_JITTER_RANGE = 0.6;

    private static final RandomGenerator RNG =
            RandomGeneratorFactory.getDefault().create();

    private final FixedCpsLimiter delegate = new FixedCpsLimiter();

    private int remainingInBatch;
    private long nextClickNanos;
    private long intervalNanos;

    /**
     * Returns true when exactly one click is due this tick.
     */
    public boolean acquire(long nowNanos, int configuredCps, boolean jitterEnabled) {
        int cps = FixedCpsLimiter.clampCps(configuredCps);

        if (!jitterEnabled) {
            return delegate.acquire(nowNanos, cps);
        }

        return acquireJittered(nowNanos, cps);
    }

    public boolean acquire(long nowNanos, int configuredCps) {
        return acquire(nowNanos, configuredCps, false);
    }

    private boolean acquireJittered(long nowNanos, int cps) {
        if (remainingInBatch <= 0) {
            generateBatch(cps, nowNanos);
            // First click of the batch fires right now
            nextClickNanos = nowNanos + intervalNanos;
            remainingInBatch--;
            return true;
        }

        if (nowNanos < nextClickNanos) {
            return false;
        }

        // Micro-jitter on the interval so clicks are never evenly spaced
        long microJitter = (long) (MICRO_JITTER_MIN + RNG.nextDouble() * MICRO_JITTER_RANGE);
        nextClickNanos = nowNanos + microJitter * intervalNanos;
        remainingInBatch--;
        return true;
    }

    private void generateBatch(int cps, long nowNanos) {
        double ratio = jitterRatio(cps);

        // Randomize click count: cps ± ratio%
        int offset = (int) Math.round(cps * ratio * (RNG.nextDouble() * 2.0 - 1.0));
        remainingInBatch = Math.max(1, cps + offset);

        // Randomize batch duration: 1000ms ± ratio%
        double windowMs = 1000.0 * (1.0 + ratio * (RNG.nextDouble() * 2.0 - 1.0));
        intervalNanos = Math.max(1L, (long) (windowMs * 1_000_000L / remainingInBatch));
        nextClickNanos = Long.MIN_VALUE;
    }

    public void clearTimingState() {
        remainingInBatch = 0;
        delegate.clearTimingState();
    }

    public static double jitterRatio(int cps) {
        int safe = Math.max(1, Math.min(MAX_CPS, cps));
        return (JITTER_INTERCEPT + safe * JITTER_SLOPE) / 100.0;
    }
}
