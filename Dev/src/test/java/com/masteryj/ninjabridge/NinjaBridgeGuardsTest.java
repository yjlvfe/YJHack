package com.masteryj.ninjabridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NinjaBridgeGuardsTest {

    @Test
    void sneakIsNotResentForTheSameState() {
        assertFalse(NinjaBridgeClient.sneakShouldChange(true, true, true));
        assertFalse(NinjaBridgeClient.sneakShouldChange(false, false, true));
    }

    @Test
    void sneakTogglesOnlyOnAGroundedStateChange() {
        assertTrue(NinjaBridgeClient.sneakShouldChange(true, false, true));
        assertFalse(NinjaBridgeClient.sneakShouldChange(true, false, false));
    }

    @Test
    void autoSwitchRunsOnlyAtARealGroundedEdge() {
        assertTrue(NinjaBridgeClient.shouldAutoSwitch(true, true, true, true));
        assertFalse(NinjaBridgeClient.shouldAutoSwitch(true, true, false, true),
                "safe ground must not fight the selected weapon or tool");
        assertFalse(NinjaBridgeClient.shouldAutoSwitch(true, true, true, false));
        assertFalse(NinjaBridgeClient.shouldAutoSwitch(false, true, true, true));
        assertFalse(NinjaBridgeClient.shouldAutoSwitch(true, false, true, true));
    }

    @Test
    void slotIsChangedOnlyWhenDifferent() {
        assertFalse(NinjaBridgeClient.needsSlotSwitch(3, 3));
        assertTrue(NinjaBridgeClient.needsSlotSwitch(4, 3));
    }

    @Test
    void slotDelayIsConservativelyClamped() {
        assertEquals(50_000_000L, NinjaBridgeClient.switchDelayNanos(-1));
        assertEquals(120_000_000L, NinjaBridgeClient.switchDelayNanos(120));
        assertEquals(500_000_000L, NinjaBridgeClient.switchDelayNanos(999));
    }
}
