package com.masteryj.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ActionBudgetTest {

    @Test
    void bothModulesCanFireInSameWindow() {
        ActionBudget.INSTANCE.reset();
        // Simulate 100 windows
        for (int w = 0; w < 100; w++) {
            assertTrue(ActionBudget.INSTANCE.requestLeft(),
                    "Left should get a slot in window " + w);
            assertTrue(ActionBudget.INSTANCE.requestRight(),
                    "Right should get a slot in window " + w);
            // Both used — total is 2
            assertFalse(ActionBudget.INSTANCE.requestLeft(),
                    "Third request should be denied");
            assertFalse(ActionBudget.INSTANCE.requestRight(),
                    "Fourth request should be denied");
            // Force new window
            ActionBudget.INSTANCE.reset();
        }
    }

    @Test
    void eachModuleGetsAtMostOnePerWindow() {
        ActionBudget.INSTANCE.reset();
        assertTrue(ActionBudget.INSTANCE.requestLeft());
        assertFalse(ActionBudget.INSTANCE.requestLeft(),
                "Left should not get 2 slots");
        assertFalse(ActionBudget.INSTANCE.requestLeft(),
                "Left should still be denied");
    }

    @Test
    void rightFirstDoesNotStarveLeft() {
        ActionBudget.INSTANCE.reset();
        for (int w = 0; w < 100; w++) {
            // Right fires first
            assertTrue(ActionBudget.INSTANCE.requestRight(),
                    "Right should fire in window " + w);
            // Left fires second
            assertTrue(ActionBudget.INSTANCE.requestLeft(),
                    "Left should still fire after right in window " + w);
            ActionBudget.INSTANCE.reset();
        }
    }

    @Test
    void leftFirstDoesNotStarveRight() {
        ActionBudget.INSTANCE.reset();
        for (int w = 0; w < 100; w++) {
            assertTrue(ActionBudget.INSTANCE.requestLeft());
            assertTrue(ActionBudget.INSTANCE.requestRight());
            ActionBudget.INSTANCE.reset();
        }
    }

    @Test
    void totalNeverExceedsTwo() {
        ActionBudget.INSTANCE.reset();
        assertTrue(ActionBudget.INSTANCE.requestLeft());
        assertTrue(ActionBudget.INSTANCE.requestRight());
        assertFalse(ActionBudget.INSTANCE.requestLeft());
        assertFalse(ActionBudget.INSTANCE.requestRight());
    }

    @Test
    void resetClearsAllCounters() {
        ActionBudget.INSTANCE.reset();
        // Fill the budget
        assertTrue(ActionBudget.INSTANCE.requestLeft());
        assertTrue(ActionBudget.INSTANCE.requestRight());
        assertFalse(ActionBudget.INSTANCE.requestLeft());

        // Reset
        ActionBudget.INSTANCE.reset();

        // Should work again
        assertTrue(ActionBudget.INSTANCE.requestLeft());
        assertTrue(ActionBudget.INSTANCE.requestRight());
    }
}
