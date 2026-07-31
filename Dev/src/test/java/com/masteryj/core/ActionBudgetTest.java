package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionBudgetTest {

    private static final long TICK = 50_000_000L;

    @Test
    void eachModuleGetsOneIndependentTickSlot() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();

        budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
        budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
        budget.flush(0L);

        assertEquals(1, left.get());
        assertEquals(1, right.get());
        assertEquals(1, budget.maxInOneTick(ActionBudget.Module.LEFT));
        assertEquals(1, budget.maxInOneTick(ActionBudget.Module.RIGHT));
        assertEquals(2, budget.maxInOneTick());
    }

    @Test
    void duplicateSameTickRequestsAreDroppedNotBacklogged() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
        budget.flush(0L);
        assertEquals(1, emitted.get());
        assertEquals(1, budget.dropped(ActionBudget.Module.LEFT));
        budget.flush(TICK);
        assertEquals(1, emitted.get(), "rejected work must never replay later");
    }

    @Test
    void falseGuardCancelsStaleWork() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.RIGHT, 1, () -> false, emitted::incrementAndGet);
        budget.flush(0L);
        assertEquals(0, emitted.get());
        assertEquals(1, budget.dropped(ActionBudget.Module.RIGHT));
    }

    @Test
    void cancelRemovesPendingWithoutClearingRateHistory() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.LEFT, 1, () -> true, emitted::incrementAndGet);
        budget.cancel(ActionBudget.Module.LEFT);
        budget.flush(0L);
        assertEquals(0, emitted.get());
    }

    @Test
    void eachModuleHasItsOwnSlidingTwentyPerSecondWindow() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
            budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
            budget.flush(tick * TICK);
        }
        assertEquals(20, left.get());
        assertEquals(20, right.get());

        budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
        budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
        budget.flush(999_999_999L);
        assertEquals(20, left.get());
        assertEquals(20, right.get());

        budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
        budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
        budget.flush(1_000_000_000L);
        assertEquals(21, left.get());
        assertEquals(21, right.get());
    }

    @Test
    void resettingOneModuleDoesNotEraseTheOthersWindow() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
            budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
            budget.flush(tick * TICK);
        }

        budget.reset(ActionBudget.Module.LEFT);
        budget.request(ActionBudget.Module.LEFT, 1, () -> true, left::incrementAndGet);
        budget.request(ActionBudget.Module.RIGHT, 1, () -> true, right::incrementAndGet);
        budget.flush(999_999_999L);

        assertEquals(21, left.get(), "left reset starts a fresh session");
        assertEquals(20, right.get(), "right rolling window remains intact");
    }

    @Test
    void resetAllDropsHistoryPendingAndDiagnostics() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.LEFT, 1, () -> true, emitted::incrementAndGet);
        budget.flush(0L);
        budget.request(ActionBudget.Module.RIGHT, 1, () -> true, emitted::incrementAndGet);
        budget.resetAll();
        budget.flush(TICK);

        assertEquals(1, emitted.get());
        assertEquals(0, budget.dropped());
        assertEquals(0, budget.maxInOneTick());
    }
}
