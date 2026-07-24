package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the shared synthetic-action ceiling. A denied action is dropped (never queued), so it
 * can never burst later; AutoLeft and AutoRight combined can never exceed the per-tick and
 * per-second caps.
 */
class ActionBudgetTest {

    @Test
    void atMostTwoSyntheticActionsPerTick() {
        ActionBudget b = new ActionBudget();
        long now = 1000L;                       // one tick bucket
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (b.tryConsume(now)) allowed++;
        }
        assertEquals(ActionBudget.MAX_PER_TICK, allowed, "per-tick cap holds even under a spam attempt");
        assertTrue(b.dropped() >= 8, "the excess attempts were dropped, not queued");
    }

    @Test
    void blockPlacementStaysWithinPerSecondBudget() {
        ActionBudget b = new ActionBudget();
        int allowed = 0;
        for (long t = 0; t < 1000; t += 5) {    // 200 attempts in one second
            if (b.tryConsume(t)) allowed++;
        }
        assertTrue(allowed <= ActionBudget.MAX_PER_SECOND,
                "block pulses cannot exceed the per-second budget (was " + allowed + ")");
    }

    @Test
    void combinedLeftAndRightShareTheOneBudget() {
        ActionBudget b = new ActionBudget();
        int allowed = 0;
        for (long t = 0; t < 1000; t += 50) {   // 20 ticks; each tick both modules try once
            if (b.tryConsume(t)) allowed++;      // AutoLeft
            if (b.tryConsume(t)) allowed++;      // AutoRight (same tick)
        }
        assertTrue(allowed <= ActionBudget.MAX_PER_SECOND,
                "AutoLeft + AutoRight combined cannot exceed the shared per-second cap (was " + allowed + ")");
    }

    @Test
    void windowRollsOverAfterASecond() {
        ActionBudget b = new ActionBudget();
        int first = 0;
        for (long t = 0; t < 1000; t += 50) {
            if (b.tryConsume(t)) first++;
        }
        int second = 0;
        for (long t = 1000; t < 2000; t += 50) {
            if (b.tryConsume(t)) second++;
        }
        assertTrue(first <= ActionBudget.MAX_PER_SECOND);
        assertTrue(second <= ActionBudget.MAX_PER_SECOND);
        assertTrue(second > 0, "budget refills in the next second — it is not permanently exhausted");
    }
}
