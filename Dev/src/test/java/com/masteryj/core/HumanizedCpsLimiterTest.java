package com.masteryj.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HumanizedCpsLimiterTest {

    @Test
    void jitterRatioIncreasesWithCps() {
        double r10 = HumanizedCpsLimiter.jitterRatio(10);
        double r20 = HumanizedCpsLimiter.jitterRatio(20);
        double r40 = HumanizedCpsLimiter.jitterRatio(40);

        assertTrue(r20 > r10, "20 CPS should have more jitter than 10 CPS");
        assertTrue(r40 > r20, "40 CPS should have more jitter than 20 CPS");

        assertEquals(0.10, r10, 0.01);
        assertEquals(0.15, r20, 0.01);
        assertEquals(0.25, r40, 0.01);
    }

    @Test
    void disabledJitterProducesConsistentRate() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int emitted = 0;
        for (long t = 0; t < 1_000_000_000L; t += 5_000_000L) {
            emitted += limiter.acquire(t, 20, false);
        }
        assertTrue(emitted >= 18 && emitted <= 22,
                "Jitter OFF: expected ~20, got " + emitted);
    }

    @Test
    void enabledJitterAveragesToTarget() {
        // Simulates 50ms ticks (20/sec game loop)
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        int clicks = 0;
        for (long t = 0; t < 5_000_000_000L; t += 50_000_000L) {
            clicks += limiter.acquire(t, 20, true);
        }
        double avg = clicks / 5.0;
        assertTrue(avg >= 12 && avg <= 28,
                "Long-term average should be ~20, got " + avg);
    }

    @Test
    void clearTimingStateResetsBatch() {
        HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
        limiter.acquire(0L, 20, true);
        limiter.acquire(50_000_000L, 20, true);
        limiter.clearTimingState();

        int emitted = 0;
        for (long t = 0; t < 2_000_000_000L; t += 50_000_000L) {
            emitted += limiter.acquire(t, 20, true);
        }
        assertTrue(emitted > 0, "Should emit clicks after state clear");
    }

    @Test
    void highCpsProducesWiderVariation() {
        HumanizedCpsLimiter lowCps = new HumanizedCpsLimiter();
        HumanizedCpsLimiter highCps = new HumanizedCpsLimiter();

        int[] low = countPerSecond(lowCps, 10, 3);
        int[] high = countPerSecond(highCps, 40, 3);

        int lowRange = maxMinusMin(low);
        int highRange = maxMinusMin(high);

        assertTrue(highRange >= lowRange,
                "40 CPS range(" + highRange + ") should be >= 10 CPS range(" + lowRange + ")");
    }

    @Test
    void anyCpsWorks() {
        for (int cps : new int[]{1, 10, 20, 30, 40}) {
            HumanizedCpsLimiter limiter = new HumanizedCpsLimiter();
            int emitted = 0;
            for (long t = 0; t < 1_000_000_000L; t += 50_000_000L) {
                emitted += limiter.acquire(t, cps, true);
            }
            assertTrue(emitted > 0, "Should emit at least some clicks at " + cps + " CPS, got " + emitted);
        }
    }

    private static int[] countPerSecond(HumanizedCpsLimiter limiter, int cps, int seconds) {
        int[] result = new int[seconds];
        int sec = 0, clicks = 0;
        for (long t = 0; t < (long) seconds * 1_000_000_000L && sec < seconds; t += 50_000_000L) {
            int currentSec = (int) (t / 1_000_000_000L);
            if (currentSec != sec) { result[sec] = clicks; clicks = 0; sec = currentSec; }
            clicks += limiter.acquire(t, cps, true);
        }
        return result;
    }

    private static int maxMinusMin(int[] values) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : values) { if (v < min) min = v; if (v > max) max = v; }
        return max - min;
    }
}
