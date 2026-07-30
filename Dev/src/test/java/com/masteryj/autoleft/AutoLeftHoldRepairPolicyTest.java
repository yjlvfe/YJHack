package com.masteryj.autoleft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoLeftHoldRepairPolicyTest {

    @Test
    void heldAttackRunsOnlyDuringValidCombatInput() {
        assertTrue(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                true, true, true, true, true));

        assertFalse(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                false, true, true, true, true), "disabled module must not emit");
        assertFalse(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                true, false, true, true, true), "menus and focus loss must stop emission");
        assertFalse(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                true, true, false, true, true), "released mouse must cancel pending work");
        assertFalse(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                true, true, true, false, true), "weapon gate must remain respected");
        assertFalse(AutoLeftHoldRepairClient.shouldRunHeldAttack(
                true, true, true, true, false), "terrain and air must not receive combat pulses");
    }
}
