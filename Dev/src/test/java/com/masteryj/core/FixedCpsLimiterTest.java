package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FixedCpsLimiterTest {

    @Test
    void everySupportedCpsUsesItsFixedInterval() {
        for (int cps = 1; cps <= 40; cps++) {
            FixedCpsLimiter limiter = new FixedCpsLimiter();
            long interval = 1_000_000_000L / cps;
            assertFalse(limiter.acquire(0L, cps));
            assertFalse(limiter.acquire(interval - 1L, cps));
            assertTrue(limiter.acquire(interval, cps), "CPS " + cps);
            assertFalse(limiter.acquire(interval + 1L, cps), "one pulse only for CPS " + cps);
        }
    }

    @Test
    void fiveHundredMillisecondStallDropsMissedActions() {
        FixedCpsLimiter limiter = new FixedCpsLimiter();
        assertFalse(limiter.acquire(0L, 40));
        assertTrue(limiter.acquire(500_000_000L, 40));
        assertFalse(limiter.acquire(500_000_001L, 40));
    }

    @Test
    void twoSecondStallDropsMissedActionsInsteadOfCatchingUp() {
        FixedCpsLimiter limiter = new FixedCpsLimiter();
        assertFalse(limiter.acquire(0L, 40));
        assertTrue(limiter.acquire(2_000_000_000L, 40));
        assertFalse(limiter.acquire(2_000_000_001L, 40),
                "a long stall releases one action only, never a burst");
    }

    @Test
    void leftAndRightCanUseIndependentLimiters() {
        FixedCpsLimiter left = new FixedCpsLimiter();
        FixedCpsLimiter right = new FixedCpsLimiter();
        left.acquire(0L, 40);
        right.acquire(0L, 10);
        assertTrue(left.acquire(25_000_000L, 40));
        assertFalse(right.acquire(25_000_000L, 10));
        assertTrue(right.acquire(100_000_000L, 10));
    }

    @Test
    void clearingTimingRequiresANewFullInterval() {
        FixedCpsLimiter limiter = new FixedCpsLimiter();
        limiter.acquire(0L, 20);
        limiter.clearTimingState();
        assertFalse(limiter.acquire(1_000_000_000L, 20));
        assertTrue(limiter.acquire(1_050_000_000L, 20));
    }

    @Test
    void cpsIsClampedToOneThroughForty() {
        assertEquals(1, FixedCpsLimiter.clampCps(-100));
        assertEquals(1, FixedCpsLimiter.clampCps(1));
        assertEquals(20, FixedCpsLimiter.clampCps(20));
        assertEquals(40, FixedCpsLimiter.clampCps(40));
        assertEquals(40, FixedCpsLimiter.clampCps(1000));
    }
}
