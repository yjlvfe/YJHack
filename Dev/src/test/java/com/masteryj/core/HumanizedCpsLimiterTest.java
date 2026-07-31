package com.masteryj.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanizedCpsLimiterTest {

    @Test
    void jitterRatioIncreasesWithCps() {
        double r10 = HumanizedCpsLimiter.jitterRatio(10);
        double r20 = HumanizedCpsLimiter.jitterRatio(20);
        double r40 = HumanizedCpsLimiter.jitterRatio(40);

        assertTrue(r20 > r10);
        assertTrue(r40 > r20);

        assertEquals(0.10, r10, 0.02);
        assertEquals(0.15, r20, 0.02);
        assertEquals(0.25, r40, 0.02);
    }

    @Test
    void disabledJitterDelegatesToFixed() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 1_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, false);
        }
        // FixedCpsLimiter first tick schedules, doesn't emit. So ~19 clicks.
        assertTrue(emitted >= 18 && emitted <= 20,
                "Jitter OFF at 20 CPS: got " + emitted);
    }

    @Test
    void jitter10NearTarget() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 10, true);
        }
        double avg = emitted / 5.0;
        assertTrue(avg >= 7 && avg <= 15,
                "10 CPS jittered avg should be ~10, got " + avg);
    }

    @Test
    void jitter20NearTarget() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, true);
        }
        double avg = emitted / 5.0;
        assertTrue(avg >= 13 && avg <= 22,
                "20 CPS jittered avg should be ~20, got " + avg);
    }

    @Test
    void jitter40UsesBurstForHighCps() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 40, true);
        }
        double avg = emitted / 5.0;
        assertTrue(avg >= 22,
                "40 CPS jittered should use burst mode (>22), got " + avg);
    }

    @Test
    void clearTimingStateResets() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        limiter.acquire(0L, 20, true);
        limiter.clearTimingState();
        int emitted = 0;
        for (long t = 0; t < 2_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, true);
        }
        assertTrue(emitted > 0, "Should emit after clear");
    }
}
