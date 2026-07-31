package com.masteryj.autoright;

import java.util.Set;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Central right-click classification with conservative compatibility for modded items. */
public final class RightClickPolicy {

    public enum Kind {
        /** Instant vanilla items that must use once per physical press. */
        SINGLE_PRESS,
        /** Placeable blocks eligible for configured Block Mode cadence. */
        BLOCK,
        /** Vanilla owns the whole press, including hold/charge behaviour. */
        PASS_THROUGH
    }

    private static final Set<String> VANILLA_SINGLE_PRESS_IDS = Set.of(
            "fire_charge",
            "ender_pearl",
            "snowball",
            "egg",
            "splash_potion",
            "lingering_potion",
            "experience_bottle",
            "ender_eye",
            "wind_charge",
            "fishing_rod"
    );

    private RightClickPolicy() {
    }

    public static Kind classify(ItemStack stack, LivingEntity user) {
        if (stack == null || stack.isEmpty()) return Kind.PASS_THROUGH;
        if (isSinglePressItem(stack, user)) return Kind.SINGLE_PRESS;
        if (stack.getItem() instanceof BlockItem) return Kind.BLOCK;
        return Kind.PASS_THROUGH;
    }

    public static boolean isSinglePressItem(ItemStack stack, LivingEntity user) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id != null && isSinglePressId(id.getNamespace(), id.getPath());
    }

    /**
     * Only known vanilla identifiers are suppressed. A modded item that happens to use a path such
     * as fire_charge or *_bucket is left PASS_THROUGH because its hold semantics are unknown.
     */
    public static boolean isSinglePressId(String namespace, String path) {
        if (!"minecraft".equals(namespace) || path == null) return false;
        return VANILLA_SINGLE_PRESS_IDS.contains(path) || path.equals("bucket") || path.endsWith("_bucket");
    }

    /** Backward-compatible pure helper used by older tests. */
    public static boolean isSinglePressPath(String path) {
        return isSinglePressId("minecraft", path);
    }
}
