package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.masteryj.core.ActionBudget.Module;
import org.junit.jupiter.api.Test;

/**
 * Locks the shared, per-module synthetic-action ceiling at the 40 CPS policy: at most TWO
 * actions per module per tick and 40 per rolling second. Each module has an independent quota
 * (so callback order can never starve one), a denied action is dropped (never queued, so it
 * can never burst later), and the one-second cap is a SLIDING window that cannot double up
 * across a second boundary. Timing is nanoseconds, matching the monotonic clock the production
 * callers feed in.
 */
class ActionBudgetTest {

    private static final long TICK = 50_000_000L;   // one client tick in nanos (~20 TPS)

    @Test
    void atMostTwoActionsPerModulePerTick() {
        ActionBudget b = new ActionBudget();
        long now = 1_000 * TICK;                     // one tick bucket
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (b.tryConsume(Module.LEFT, now)) allowed++;
        }
        assertEquals(ActionBudget.MAX_PER_TICK_PER_MODULE, allowed, "per-tick cap of two holds under a spam attempt");
        assertEquals(2, allowed, "exactly two synthetic actions allowed in one tick");
        assertTrue(b.dropped(Module.LEFT) >= 8, "the excess attempts were dropped, not queued");
    }

    @Test
    void aThirdActionInOneTickIsRejected() {
        ActionBudget b = new ActionBudget();
        long now = 7 * TICK;
        assertTrue(b.tryConsume(Module.LEFT, now), "first pulse allowed");
        assertTrue(b.tryConsume(Module.LEFT, now), "second pulse allowed");
        assertFalse(b.tryConsume(Module.LEFT, now), "a third pulse in the same tick is rejected (duplicate path guard)");
    }

    @Test
    void leftAndRightEachGetTheirOwnTwoInTheSameTick() {
        ActionBudget b = new ActionBudget();
        long now = 5 * TICK;
        assertTrue(b.tryConsume(Module.LEFT, now));
        assertTrue(b.tryConsume(Module.LEFT, now), "left gets its two per-tick actions");
        assertTrue(b.tryConsume(Module.RIGHT, now));
        assertTrue(b.tryConsume(Module.RIGHT, now), "right still gets its own two — the budget is per-module");
    }

    @Test
    void neitherModuleStarvesTheOtherOverASecond() {
        // Left always asks (twice) first, for a full second. Right must still get its full share:
        // independent quotas mean callback order cannot let one module monopolise the budget.
        ActionBudget b = new ActionBudget();
        int left = 0;
        int right = 0;
        for (int t = 0; t < 20; t++) {
            long now = t * TICK;
            if (b.tryConsume(Module.LEFT, now)) left++;    // first every tick
            if (b.tryConsume(Module.LEFT, now)) left++;
            if (b.tryConsume(Module.RIGHT, now)) right++;  // after left every tick
            if (b.tryConsume(Module.RIGHT, now)) right++;
        }
        assertEquals(40, left, "left gets its two pulses per tick");
        assertEquals(40, right, "right is NOT starved by left going first");
    }

    @Test
    void perModuleSecondCapIsNeverExceededByASlidingWindow() {
        // Drive two actions per tick for two seconds; every rolling 1s window over the accepted
        // stamps must hold at most the per-second cap — no fixed-window boundary burst.
        ActionBudget b = new ActionBudget();
        long[] stamps = new long[80];
        int n = 0;
        for (int t = 0; t < 40; t++) {
            long now = t * TICK;
            if (b.tryConsume(Module.LEFT, now)) stamps[n++] = now;
            if (b.tryConsume(Module.LEFT, now)) stamps[n++] = now;
        }
        for (int i = 0; i < n; i++) {
            int inWindow = 0;
            for (int j = 0; j < n; j++) {
                if (stamps[j] >= stamps[i] && stamps[j] < stamps[i] + 1_000_000_000L) inWindow++;
            }
            assertTrue(inWindow <= ActionBudget.MAX_PER_SECOND_PER_MODULE,
                    "no 1s window exceeds the per-second cap (was " + inWindow + ")");
        }
    }

    @Test
    void steadyFortyPerSecondIsSustainedWithoutFalseDrops() {
        // At exactly the 40 CPS ceiling (two per tick) the module should never be falsely
        // throttled by its own sliding window.
        ActionBudget b = new ActionBudget();
        int allowed = 0;
        for (int t = 0; t < 20; t++) {
            long now = t * TICK;
            if (b.tryConsume(Module.LEFT, now)) allowed++;
            if (b.tryConsume(Module.LEFT, now)) allowed++;
        }
        assertEquals(40, allowed, "40 actions (two per tick) in one second are all allowed");
        assertEquals(0, b.dropped(Module.LEFT), "no false drops at a steady 40 CPS");
    }

    @Test
    void resetClearsOneModuleWindow() {
        // World change / disconnect / GUI open / disable / death.
        ActionBudget b = new ActionBudget();
        long now = 1_000 * TICK;
        assertTrue(b.tryConsume(Module.LEFT, now));
        assertTrue(b.tryConsume(Module.LEFT, now));
        assertFalse(b.tryConsume(Module.LEFT, now), "third action in the same tick is denied");
        b.reset(Module.LEFT);
        assertTrue(b.tryConsume(Module.LEFT, now), "after reset a fresh action fires immediately");
    }

    @Test
    void resetIsPerModuleAndDoesNotTouchTheOther() {
        ActionBudget b = new ActionBudget();
        long now = 3 * TICK;
        assertTrue(b.tryConsume(Module.RIGHT, now));
        assertTrue(b.tryConsume(Module.RIGHT, now));
        b.reset(Module.LEFT);                                  // resetting LEFT must not free RIGHT
        assertFalse(b.tryConsume(Module.RIGHT, now), "RIGHT's per-tick state survived a LEFT reset");
    }
}
