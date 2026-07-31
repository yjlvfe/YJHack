package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoRightLongHoldPolicyTest {

    @Test
    void blockAndDiscreteHoldsAreSuppressedAfterTheFirstVanillaUse() {
        assertTrue(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.BLOCK),
                "fixed direct CPS must be the only repeated block-use path");
        assertTrue(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.SINGLE_PRESS));
    }

    @Test
    void chargeAndConsumableItemsRemainVanilla() {
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.PASS_THROUGH),
                "bows, food, shields and other hold items must remain vanilla");
    }

    @Test
    void disabledOrGatedAutomationNeverReleasesTheRealKey() {
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                false, true, false, false, RightClickPolicy.Kind.BLOCK));
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, false, false, false, RightClickPolicy.Kind.BLOCK));
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, true, true, false, RightClickPolicy.Kind.BLOCK));
    }

    @Test
    void changingSlotDuringAHoldIsAlwaysSuppressed() {
        assertTrue(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, true, RightClickPolicy.Kind.PASS_THROUGH));
    }
}
