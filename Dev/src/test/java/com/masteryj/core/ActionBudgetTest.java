package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionBudgetTest {

    private static final long TICK = 50_000_000L;

    @Test
    void globalTickCapIsTwoAcrossBothModules() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();

        budget.request(ActionBudget.Module.LEFT, 2, () -> true, left::incrementAndGet);
        budget.request(ActionBudget.Module.RIGHT, 2, () -> true, right::incrementAndGet);
        budget.flush(0L);

        assertEquals(1, left.get(), "left receives one fair slot");
        assertEquals(1, right.get(), "right receives one fair slot");
        assertEquals(2, budget.maxInOneTick());
    }

    @Test
    void soleRequesterCanUseBothTickSlots() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
        budget.flush(0L);
        assertEquals(2, emitted.get());
    }

    @Test
    void falseGuardCancelsStaleWork() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.RIGHT, 2, () -> false, emitted::incrementAndGet);
        budget.flush(0L);
        assertEquals(0, emitted.get());
        assertEquals(2, budget.dropped(ActionBudget.Module.RIGHT));
    }

    @Test
    void cancelRemovesPendingRequest() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
        budget.cancel(ActionBudget.Module.LEFT);
        budget.flush(0L);
        assertEquals(0, emitted.get());
    }

    @Test
    void slidingSecondCapIsGlobalForty() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
            budget.flush(tick * TICK);
        }
        assertEquals(40, emitted.get());

        budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
        budget.flush(999_999_999L);
        assertEquals(40, emitted.get(), "a rolling one-second window cannot exceed forty");

        budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
        budget.flush(1_000_000_000L);
        assertEquals(42, emitted.get(), "oldest tick expires exactly at one second");
    }

    @Test
    void fairnessDoesNotDependOnCallbackOrder() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        for (int tick = 0; tick < 10; tick++) {
            budget.request(ActionBudget.Module.LEFT, 2, () -> true, left::incrementAndGet);
            budget.request(ActionBudget.Module.RIGHT, 2, () -> true, right::incrementAndGet);
            budget.flush(tick * TICK);
        }
        assertEquals(10, left.get());
        assertEquals(10, right.get());
    }

    @Test
    void resetAllDropsHistoryAndPendingWork() {
        ActionBudget budget = new ActionBudget();
        AtomicInteger emitted = new AtomicInteger();
        for (int tick = 0; tick < 20; tick++) {
            budget.request(ActionBudget.Module.LEFT, 2, () -> true, emitted::incrementAndGet);
            budget.flush(tick * TICK);
        }
        budget.request(ActionBudget.Module.RIGHT, 2, () -> true, emitted::incrementAndGet);
        budget.resetAll();
        budget.flush(1_000_000L);
        assertEquals(40, emitted.get(), "pending work was cancelled");

        budget.request(ActionBudget.Module.RIGHT, 2, () -> true, emitted::incrementAndGet);
        budget.flush(2_000_000L);
        assertEquals(42, emitted.get(), "rolling history was reset for the new world");
    }
}
