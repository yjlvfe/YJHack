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
    void knownVanillaDiscreteItemsAreSinglePress() {
        for (String path : new String[]{
                "fire_charge", "ender_pearl", "snowball", "egg", "splash_potion",
                "lingering_potion", "experience_bottle", "ender_eye", "wind_charge",
                "fishing_rod", "bucket", "water_bucket", "lava_bucket"}) {
            assertTrue(RightClickPolicy.isSinglePressId("minecraft", path), path);
        }
    }

    @Test
    void samePathFromAnotherNamespacePassesThrough() {
        assertFalse(RightClickPolicy.isSinglePressId("examplemod", "fire_charge"));
        assertFalse(RightClickPolicy.isSinglePressId("examplemod", "water_bucket"));
    }

    @Test
    void holdChargeAndBlockPathsRemainVanilla() {
        for (String path : new String[]{
                "bow", "crossbow", "trident", "shield", "spyglass", "goat_horn",
                "apple", "bread", "stone", "dirt", "cobblestone", "oak_planks"}) {
            assertFalse(RightClickPolicy.isSinglePressPath(path), path);
        }
    }

    @Test
    void nullInputsAreSafe() {
        assertFalse(RightClickPolicy.isSinglePressPath(null));
        assertFalse(RightClickPolicy.isSinglePressId(null, "fire_charge"));
        assertEquals(RightClickPolicy.Kind.PASS_THROUGH,
                RightClickPolicy.classify(null, null));
    }
}
