package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoRightPressGuardTest {

    @Test
    void unchangedPressIdentityRemainsValid() {
        Object item = new Object();
        assertFalse(AutoRightClient.pressIdentityChanged(2, item, 2, item));
    }

    @Test
    void slotChangeInvalidatesHeldPress() {
        Object item = new Object();
        assertTrue(AutoRightClient.pressIdentityChanged(2, item, 3, item));
    }

    @Test
    void itemChangeInvalidatesHeldPressEvenInSameSlot() {
        assertTrue(AutoRightClient.pressIdentityChanged(2, new Object(), 2, new Object()));
    }
}
