package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the anti-burst contract of {@link ClickScheduler}: at most one pulse per tick and
 * NO catch-up — a client-thread stall must never be replayed as a burst of synthetic clicks.
 */
class ClickSchedulerTest {

    /** Model of a module: check due() at most once per tick, emit if due, rearm from now. */
    private static int emittedOverTicks(long[] tickTimesMs, int delayMs) {
        ClickScheduler s = new ClickScheduler();
        int emitted = 0;
        for (long now : tickTimesMs) {
            if (s.due(now)) {
                emitted++;
                s.rearm(now, delayMs);
            }
        }
        return emitted;
    }

    @Test
    void freshSchedulerFiresImmediately() {
        assertTrue(new ClickScheduler().due(0L));
    }

    @Test
    void lagOf500msProducesExactlyOnePulse() {
        ClickScheduler s = new ClickScheduler();
        s.rearm(0L, 25);                 // ~40 CPS schedule, next due at 25ms
        long resume = 500L;              // 500ms stall
        int pulses = 0;
        if (s.due(resume)) { pulses++; s.rearm(resume, 25); }
        assertEquals(1, pulses, "a 500ms stall yields ONE pulse, not a backlog burst");
        assertFalse(s.due(resume), "rescheduled from now — cannot fire again this tick");
    }

    @Test
    void lagOfTwoSecondsProducesExactlyOnePulse() {
        ClickScheduler s = new ClickScheduler();
        s.rearm(0L, 50);                 // 20 CPS
        long resume = 2000L;             // 2s stall
        int pulses = 0;
        if (s.due(resume)) { pulses++; s.rearm(resume, 50); }
        assertEquals(1, pulses, "a 2s stall still yields exactly ONE pulse");
        assertFalse(s.due(resume));
    }

    @Test
    void atMostOnePulsePerTickEvenIfPolledRepeatedly() {
        ClickScheduler s = new ClickScheduler();
        long now = 5000L;
        int pulses = 0;
        for (int i = 0; i < 40; i++) {   // hammer due() 40x within a single tick
            if (s.due(now)) { pulses++; s.rearm(now, 25); }
        }
        assertEquals(1, pulses, "one tick can emit at most one pulse");
    }

    @Test
    void noBacklogReplayAcrossAOneSecondGap() {
        // 20 CPS. Ten normal ticks, a 1000ms gap, then three more ticks.
        // Without catch-up the gap contributes ONE pulse, never ~20.
        long[] ticks = {0, 50, 100, 150, 200, 250, 300, 350, 400, 450, 1450, 1500, 1550};
        int pulses = emittedOverTicks(ticks, 50);
        assertTrue(pulses <= ticks.length, "never more pulses than ticks — no backlog replay");
        assertTrue(pulses >= 10, "normal cadence still produces its pulses");
    }

    @Test
    void clearCancelsPendingThenReArmsImmediate() {
        ClickScheduler s = new ClickScheduler();
        s.rearm(1000L, 50);              // pending pulse queued at 1050
        s.clear();                        // GUI open / focus loss / world null / disable
        assertTrue(s.due(1000L), "after a reset the next physical press fires immediately");
    }

    @Test
    void lagOfOneSecondProducesExactlyOnePulse() {
        ClickScheduler s = new ClickScheduler();
        s.rearm(0L, 50);                 // 20 CPS
        long resume = 1000L;             // 1s stall
        int pulses = 0;
        if (s.due(resume)) { pulses++; s.rearm(resume, 50); }
        assertEquals(1, pulses, "a 1s stall yields exactly ONE pulse, not ~20");
        assertFalse(s.due(resume));
    }

    /** 200 ticks == 10 s at 20 TPS: times 0,50,…,9950 ms. */
    private static long[] tenSecondsOfTicks() {
        long[] t = new long[200];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * 50L;
        }
        return t;
    }

    @Test
    void cadenceMatchesConfiguredCpsOverTenSeconds() {
        long[] ticks = tenSecondsOfTicks();
        // delay = ceil(1000/cps); one pulse per tick is the hard ceiling (20 CPS).
        assertEquals(10, emittedOverTicks(ticks, 1000), "1 CPS -> ~10 pulses in 10s");
        assertEquals(50, emittedOverTicks(ticks, 200), "5 CPS -> ~50 pulses in 10s");
        assertEquals(100, emittedOverTicks(ticks, 100), "10 CPS -> ~100 pulses in 10s");
        assertEquals(200, emittedOverTicks(ticks, 50), "20 CPS -> one pulse every tick");
    }

    @Test
    void changingCpsDoesNotCompensateForThePast() {
        // Fast schedule, a long stall, then resume at a slower CPS: the stall must still
        // contribute exactly ONE pulse — the past is never replayed to "catch up".
        ClickScheduler s = new ClickScheduler();
        s.rearm(0L, 50);                 // 20 CPS
        long resume = 5000L;             // 5s stall
        int pulses = 0;
        if (s.due(resume)) { pulses++; s.rearm(resume, 200); }   // switched to 5 CPS
        assertEquals(1, pulses, "one pulse for the whole stall, regardless of the new CPS");
        assertFalse(s.due(resume), "rescheduled from now at the new rate — no backlog");
    }

    @Test
    void disableThenEnableDoesNotReplayMissedPulses() {
        ClickScheduler s = new ClickScheduler();
        s.rearm(0L, 50);
        s.clear();                        // disable
        s.armImmediate();                 // re-enable: fresh press
        long now = 10_000L;
        int pulses = 0;
        if (s.due(now)) { pulses++; s.rearm(now, 50); }
        assertEquals(1, pulses, "re-enabling emits ONE fresh pulse, never a backlog of missed ones");
        assertFalse(s.due(now));
    }
}
