package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyMultiVersionPlacementPolicyTest {

    @Test
    void invalidPlacementNeverArmsOrEmits() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertFalse(policy.shouldEmitFollowUp(0L, 40, true, true, true, false));
        assertFalse(policy.shouldEmitFollowUp(2_000_000_000L, 40, true, true, true, false));
    }

    @Test
    void longStallReleasesOneAttemptOnly() {
        LegacyMultiVersionPlacementPolicy policy = new LegacyMultiVersionPlacementPolicy();
        assertFalse(policy.shouldEmitFollowUp(0L, 40, true, true, true, true));
        assertTrue(policy.shouldEmitFollowUp(2_000_000_000L, 40, true, true, true, true));
        assertFalse(policy.shouldEmitFollowUp(2_000_000_001L, 40, true, true, true, true));
    }

    @Test
    void blockToBlockCanContinueButOtherTransitionsCannot() {
        assertTrue(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.BLOCK));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.SINGLE_PRESS));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.BLOCK, RightClickPolicy.Kind.PASS_THROUGH));
        assertFalse(LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(
                RightClickPolicy.Kind.SINGLE_PRESS, RightClickPolicy.Kind.BLOCK));
    }
}
