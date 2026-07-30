package com.masteryj.autoright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RightClickPolicyTest {

    @Test
    void kindHasThreeCategories() {
        assertEquals(3, RightClickPolicy.Kind.values().length);
    }

    @Test
    void instantDiscreteItemsAreSinglePress() {
        for (String path : new String[]{
                "fire_charge", "ender_pearl", "snowball", "egg", "splash_potion",
                "lingering_potion", "experience_bottle", "ender_eye", "wind_charge",
                "fishing_rod"}) {
            assertTrue(RightClickPolicy.isSinglePressPath(path), path);
        }
    }

    @Test
    void bucketsAreSinglePress() {
        assertTrue(RightClickPolicy.isSinglePressPath("bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("water_bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("lava_bucket"));
        assertTrue(RightClickPolicy.isSinglePressPath("powder_snow_bucket"));
    }

    @Test
    void holdAndChargeItemsRemainVanilla() {
        for (String path : new String[]{
                "bow", "crossbow", "trident", "shield", "spyglass", "goat_horn",
                "apple", "bread"}) {
            assertFalse(RightClickPolicy.isSinglePressPath(path), path + " must pass through");
        }
    }

    @Test
    void blocksAreNotForcedIntoSinglePress() {
        for (String path : new String[]{"stone", "dirt", "cobblestone", "oak_planks"}) {
            assertFalse(RightClickPolicy.isSinglePressPath(path));
        }
    }

    @Test
    void nullInputsAreSafe() {
        assertFalse(RightClickPolicy.isSinglePressPath(null));
        assertEquals(RightClickPolicy.Kind.PASS_THROUGH,
                RightClickPolicy.classify(null, null));
    }
}
