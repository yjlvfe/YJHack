package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClickSchedulerTest {

    private static int emittedOverTicks(int ticks, int cps) {
        ClickScheduler scheduler = new ClickScheduler();
        int total = 0;
        for (int tick = 0; tick < ticks; tick++) total += scheduler.pulsesThisTick(cps);
        return total;
    }

    @Test
    void steadyCadenceMatchesExecutableCps() {
        assertEquals(10, emittedOverTicks(200, 1));
        assertEquals(50, emittedOverTicks(200, 5));
        assertEquals(100, emittedOverTicks(200, 10));
        assertEquals(200, emittedOverTicks(200, 20));
    }

    @Test
    void ratesAboveTwentyClampToOnePerTick() {
        assertEquals(200, emittedOverTicks(200, 21));
        assertEquals(200, emittedOverTicks(200, 40));
        assertEquals(200, emittedOverTicks(200, 1000));
    }

    @Test
    void neverReturnsMoreThanOne() {
        ClickScheduler scheduler = new ClickScheduler();
        for (int i = 0; i < 100; i++) {
            assertTrue(scheduler.pulsesThisTick(1000) <= ClickScheduler.MAX_PULSES_PER_TICK);
        }
    }

    @Test
    void clearDropsCadenceProgress() {
        ClickScheduler scheduler = new ClickScheduler();
        assertEquals(0, scheduler.pulsesThisTick(10));
        scheduler.clear();
        assertEquals(0, scheduler.pulsesThisTick(10),
                "a fresh physical press is vanilla-only; no immediate synthetic duplicate");
    }

    @Test
    void negativeRatesAreClampedToZero() {
        assertEquals(0, emittedOverTicks(20, -100));
    }

    @Test
    void stallsCannotCreateCatchUp() {
        ClickScheduler scheduler = new ClickScheduler();
        for (int i = 0; i < 10; i++) scheduler.pulsesThisTick(20);
        // A stall is represented by no calls. The next call is still one normal tick.
        assertEquals(1, scheduler.pulsesThisTick(20));
    }
}
