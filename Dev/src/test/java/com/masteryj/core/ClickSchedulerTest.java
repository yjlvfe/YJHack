package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void steadyCadenceMatchesConfiguredCps() {
        assertEquals(10, emittedOverTicks(200, 1));
        assertEquals(50, emittedOverTicks(200, 5));
        assertEquals(100, emittedOverTicks(200, 10));
        assertEquals(200, emittedOverTicks(200, 20));
        assertEquals(250, emittedOverTicks(200, 25));
        assertEquals(300, emittedOverTicks(200, 30));
        assertEquals(350, emittedOverTicks(200, 35));
        assertEquals(400, emittedOverTicks(200, 40));
    }

    @Test
    void thirtyCpsAlternatesOneAndTwo() {
        ClickScheduler scheduler = new ClickScheduler();
        int[] sequence = new int[6];
        for (int i = 0; i < sequence.length; i++) sequence[i] = scheduler.pulsesThisTick(30);
        assertArrayEquals(new int[]{1, 2, 1, 2, 1, 2}, sequence);
    }

    @Test
    void neverReturnsMoreThanTwo() {
        ClickScheduler scheduler = new ClickScheduler();
        assertTrue(scheduler.pulsesThisTick(1000) <= ClickScheduler.MAX_PULSES_PER_TICK);
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
    void absurdAndNegativeRatesAreClamped() {
        assertEquals(40, emittedOverTicks(20, 1000));
        assertEquals(0, emittedOverTicks(20, -100));
    }

    @Test
    void stallsCannotCreateCatchUp() {
        ClickScheduler scheduler = new ClickScheduler();
        for (int i = 0; i < 10; i++) scheduler.pulsesThisTick(40);
        // A stall is represented by no calls. The next call remains a normal tick.
        assertEquals(2, scheduler.pulsesThisTick(40));
    }
}
