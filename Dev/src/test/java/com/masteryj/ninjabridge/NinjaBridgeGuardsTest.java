package com.masteryj.ninjabridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks NinjaBridge's packet-hygiene guards: sneak is toggled only on a genuine state change
 * (never re-sent), and the hotbar slot is switched only when it differs from what is held.
 */
class NinjaBridgeGuardsTest {

    @Test
    void sneakIsNotResentForTheSameState() {
        assertFalse(NinjaBridgeClient.sneakShouldChange(true, true, true), "already sneaking -> no re-send");
        assertFalse(NinjaBridgeClient.sneakShouldChange(false, false, true), "already un-sneaking -> no re-send");
    }

    @Test
    void sneakTogglesOnlyOnAGroundedStateChange() {
        assertTrue(NinjaBridgeClient.sneakShouldChange(true, false, true), "state change while grounded -> toggle");
        assertFalse(NinjaBridgeClient.sneakShouldChange(true, false, false), "airborne -> do not toggle");
    }

    @Test
    void slotIsNotSwitchedWhenAlreadySelected() {
        assertFalse(NinjaBridgeClient.needsSlotSwitch(3, 3), "already holding the target slot -> no switch packet");
    }

    @Test
    void slotIsSwitchedWhenDifferent() {
        assertTrue(NinjaBridgeClient.needsSlotSwitch(4, 3));
    }
}
