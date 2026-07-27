package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the cadence + anti-burst contract of the tick-aware {@link ClickScheduler}:
 * <ul>
 *   <li>exact rates from 1..40 CPS over ten simulated seconds (200 ticks at 20 TPS);</li>
 *   <li>at most TWO pulses per tick, ever;</li>
 *   <li>NO catch-up — a client-thread stall (fewer tick calls) is never replayed as a burst;</li>
 *   <li>an immediate first pulse on a fresh press, even at low CPS, with no third pulse.</li>
 * </ul>
 * The accumulator is wall-time agnostic, so jitter in tick spacing cannot change these counts.
 */
class ClickSchedulerTest {

    /** Total pulses a fresh (non-primed) scheduler emits over {@code ticks} ticks at a fixed cps. */
    private static int emittedOverTicks(int ticks, int cps) {
        ClickScheduler s = new ClickScheduler();
        int total = 0;
        for (int i = 0; i < ticks; i++) {
            total += s.pulsesThisTick(cps);
        }
        return total;
    }

    @Test
    void steadyCadenceMatchesConfiguredCpsOverTenSeconds() {
        // 200 ticks == 10 s at 20 TPS. Pure phase (no immediate priming) yields the round rate.
        assertEquals(10, emittedOverTicks(200, 1), "1 CPS -> 10 pulses in 10s");
        assertEquals(50, emittedOverTicks(200, 5), "5 CPS -> 50 pulses in 10s");
        assertEquals(100, emittedOverTicks(200, 10), "10 CPS -> 100 pulses in 10s");
        assertEquals(200, emittedOverTicks(200, 20), "20 CPS -> one pulse every tick");
        assertEquals(250, emittedOverTicks(200, 25), "25 CPS -> 250 pulses in 10s");
        assertEquals(300, emittedOverTicks(200, 30), "30 CPS -> 300 pulses in 10s");
        assertEquals(350, emittedOverTicks(200, 35), "35 CPS -> 350 pulses in 10s");
        assertEquals(400, emittedOverTicks(200, 40), "40 CPS -> two pulses every tick");
    }

    @Test
    void fortyCpsIsTwoPulsesEveryTick() {
        ClickScheduler s = new ClickScheduler();
        for (int i = 0; i < 20; i++) {
            assertEquals(2, s.pulsesThisTick(40), "40 CPS emits exactly two pulses every normal tick");
        }
    }

    @Test
    void thirtyCpsAlternatesOneAndTwo() {
        ClickScheduler s = new ClickScheduler();
        int[] seq = new int[6];
        for (int i = 0; i < seq.length; i++) {
            seq[i] = s.pulsesThisTick(30);
        }
        // 30 CPS = 1.5 pulses/tick: a steady 1,2,1,2,... never a burst.
        assertArrayEquals(new int[] {1, 2, 1, 2, 1, 2}, seq);
    }

    @Test
    void neverMoreThanTwoPulsesPerTick() {
        ClickScheduler s = new ClickScheduler();
        s.armImmediate();
        assertTrue(s.pulsesThisTick(40) <= ClickScheduler.MAX_PULSES_PER_TICK,
                "immediate + 40 CPS still caps at two — no third pulse");
        // An absurd hand-fed rate is still clamped to the two-pulse ceiling.
        assertTrue(new ClickScheduler().pulsesThisTick(1000) <= ClickScheduler.MAX_PULSES_PER_TICK);
    }

    @Test
    void freshPressFiresImmediatelyEvenAtOneCps() {
        ClickScheduler s = new ClickScheduler();
        s.armImmediate();
        assertEquals(1, s.pulsesThisTick(1), "a fresh press fires one pulse immediately even at 1 CPS");
    }

    @Test
    void immediateDoesNotAddAThirdPulseAtFortyCps() {
        ClickScheduler s = new ClickScheduler();
        s.armImmediate();
        assertEquals(2, s.pulsesThisTick(40), "immediate coincides with the natural two, never a third");
    }

    @Test
    void aStallIsNeverCompensatedAsABurst() {
        // Run ten normal 40 CPS ticks (two each), then the tick loop "stalls" for two seconds:
        // that simply means the next tick is a single call. It must emit at most two pulses —
        // the ~80 missed pulses are gone, never replayed.
        ClickScheduler s = new ClickScheduler();
        for (int i = 0; i < 10; i++) {
            s.pulsesThisTick(40);
        }
        assertTrue(s.pulsesThisTick(40) <= ClickScheduler.MAX_PULSES_PER_TICK,
                "a stall (fewer tick calls) is never replayed as a backlog burst");
    }

    @Test
    void jitterDoesNotReduceTheRate() {
        // The accumulator ignores wall-time: whether ticks land at 48, 50 or 52 ms, each tick
        // contributes exactly `cps`, so 40 CPS never sags toward 20. Twenty ticks -> 40 pulses.
        ClickScheduler s = new ClickScheduler();
        int total = 0;
        for (int i = 0; i < 20; i++) {
            total += s.pulsesThisTick(40);
        }
        assertEquals(40, total, "tick jitter cannot drop the 40 CPS rate");
    }

    @Test
    void clearReprimesForAnImmediatePulse() {
        ClickScheduler s = new ClickScheduler();
        s.pulsesThisTick(30);   // accumulate some phase
        s.clear();              // GUI open / focus loss / world null / disable / mouse up
        assertEquals(1, s.pulsesThisTick(1), "after clear the next physical press fires immediately");
    }

    @Test
    void disableThenEnableDoesNotReplayMissedPulses() {
        ClickScheduler s = new ClickScheduler();
        for (int i = 0; i < 10; i++) {
            s.pulsesThisTick(40);
        }
        s.clear();              // disable
        s.armImmediate();       // re-enable: fresh press
        assertEquals(2, s.pulsesThisTick(40), "re-enabling at 40 CPS emits its normal two, never a backlog");
    }
}
