package com.masteryj.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanizedCpsLimiterTest {

    @Test
    void jitterRatioIncreasesWithCps() {
        double r10 = HumanizedCpsLimiter.jitterRatio(10);
        double r20 = HumanizedCpsLimiter.jitterRatio(20);
        assertTrue(r20 > r10);
        assertEquals(0.10, r10, 0.02);
        assertEquals(0.15, r20, 0.02);
    }

    @Test
    void disabledJitterDelegatesToFixed() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 1_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, false);
        }
        assertTrue(emitted >= 18 && emitted <= 20,
                "Jitter OFF at 20 CPS: got " + emitted);
    }

    @Test
    void jitter10HitsTarget() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 10, true);
        }
        double avg = emitted / 5.0;
        assertTrue(avg >= 8 && avg <= 12,
                "10 CPS avg " + avg);
    }

    @Test
    void jitter20HitsTarget() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, true);
        }
        double avg = emitted / 5.0;
        assertTrue(avg >= 17 && avg <= 23,
                "20 CPS avg " + avg);
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
