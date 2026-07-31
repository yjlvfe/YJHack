package com.masteryj.core;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Humanized CPS limiter — variable inter-click timing with a hard ceiling of
 * 1 click per game tick (or 2 when CPS exceeds tick rate).
 *
 * <p>This is NOT a batch system. It's a real-time interval system:
 * the limiter tracks when the next click is due. Jitter means the interval
 * between clicks varies randomly, so the number of clicks in any given
 * second varies — but the long-term average stays at the configured CPS.
 *
 * <p>At most 1 click per call, or 2 when behind schedule. Missed calls
 * during lag are treated as \"no-op\" — no catch-up bursts.
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
            // Emit first click immediately
            return 1;
        }

        if (nowNanos < nextClickAt) return 0;

        // Click is due. Count 1, or 2 if we've fallen behind.
        int emitted = 1;
        long nextBase = nowNanos;

        if (nowNanos - nextClickAt > scheduleNextNanos(cps)) {
            // We're more than one interval behind — emit 2 but skip the overshoot
            emitted = 2;
            nextBase = nowNanos;
        }

        scheduleNext(nextBase, cps);
        return emitted;
    }

    private void scheduleNext(long now, int cps) {
        double baseNs = 1_000_000_000.0 / cps;
        double jittered = baseNs * (0.85 + RNG.nextDouble() * 0.30);
        nextClickAt = now + (long) jittered;
    }

    /** Average interval with jitter ratio factored in. */
    private long scheduleNextNanos(int cps) {
        double baseNs = 1_000_000_000.0 / cps;
        return (long) (baseNs * 1.15);
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
