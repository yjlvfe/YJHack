package com.masteryj.autoleft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyMultiVersionCombatPolicyTest {

    @Test
    void emitsAtMostOneFollowUpAndNeverCatchesUp() {
        LegacyMultiVersionCombatPolicy policy = new LegacyMultiVersionCombatPolicy();
        assertFalse(policy.shouldEmitFollowUp(0L, 40, true, true, true, true));
        assertTrue(policy.shouldEmitFollowUp(2_000_000_000L, 40, true, true, true, true));
        assertFalse(policy.shouldEmitFollowUp(2_000_000_001L, 40, true, true, true, true));
    }

    @Test
    void noVanillaEntityTargetMeansNoSyntheticMissAttempt() {
        LegacyMultiVersionCombatPolicy policy = new LegacyMultiVersionCombatPolicy();
        assertFalse(policy.shouldEmitFollowUp(0L, 20, true, true, true, false));
        assertFalse(policy.shouldEmitFollowUp(1_000_000_000L, 20, true, true, true, false));
    }

    @Test
    void gameplayGateOrReleaseClearsOldTiming() {
        LegacyMultiVersionCombatPolicy policy = new LegacyMultiVersionCombatPolicy();
        assertFalse(policy.shouldEmitFollowUp(0L, 20, true, true, true, true));
        assertFalse(policy.shouldEmitFollowUp(25_000_000L, 20, true, false, true, true));
        assertFalse(policy.shouldEmitFollowUp(50_000_000L, 20, true, true, true, true));
        assertTrue(policy.shouldEmitFollowUp(100_000_000L, 20, true, true, true, true));
    }
}
