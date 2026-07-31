package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoRightLongHoldPolicyTest {

    @Test
    void blocksAndChargeItemsKeepTheirVanillaHold() {
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.BLOCK),
                "block placement must keep the real use key pressed");
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.PASS_THROUGH),
                "bows, food, shields and other hold items must remain vanilla");
    }

    @Test
    void disabledOrGatedAutomationNeverReleasesTheRealKey() {
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                false, true, false, false, RightClickPolicy.Kind.SINGLE_PRESS));
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, false, false, false, RightClickPolicy.Kind.SINGLE_PRESS));
        assertFalse(AutoRightClient.shouldSuppressVanillaHold(
                true, true, true, false, RightClickPolicy.Kind.SINGLE_PRESS));
    }

    @Test
    void OnlyDiscreteOrInvalidatedPressesAreSuppressed() {
        assertTrue(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, false, RightClickPolicy.Kind.SINGLE_PRESS));
        assertTrue(AutoRightClient.shouldSuppressVanillaHold(
                true, true, false, true, RightClickPolicy.Kind.BLOCK),
                "changing slot during a hold must not activate the replacement item");
    }
}
