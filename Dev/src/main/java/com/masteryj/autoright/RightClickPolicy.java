package com.masteryj.autoright;

import java.util.Set;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Central right-click classification for AutoRight.
 *
 * <p>The physical right mouse button is polled directly (GLFW). Before AutoRight
 * decides whether to auto-repeat, it asks this policy what kind of item is held.
 * This is the single source of truth for the rule the user requires:
 *
 * <ul>
 *   <li>{@link Kind#SINGLE_PRESS} — discrete-use items (Fire Charge / fireball,
 *       Ender Pearl, Snowball, Egg, thrown potions, Wind Charge, Bow, Crossbow,
 *       Trident, Fishing Rod, Buckets, and anything with a charge/use duration).
 *       These fire <b>exactly one use per physical press</b>. Holding the button
 *       must NOT produce more uses; AutoRight suppresses the vanilla hold-repeat
 *       and only re-arms after a RELEASE. Never enters CPS, even with Block Mode off.</li>
 *   <li>{@link Kind#BLOCK} — a {@link BlockItem}. Eligible for CPS burst placement,
 *       but only while Block Mode is enabled.</li>
 *   <li>{@link Kind#PASS_THROUGH} — everything else (food, tools, shields, spyglass,
 *       …). AutoRight leaves vanilla input completely alone: no synthetic clicks,
 *       no suppression.</li>
 * </ul>
 *
 * <p>Classification is by registry id (mapping-stable across Yarn builds) plus a
 * use-action / use-duration check, so it never depends on internal item class names.
 */
public final class RightClickPolicy {

    public enum Kind {
        SINGLE_PRESS,
        BLOCK,
        PASS_THROUGH
    }

    /**
     * Registry paths (namespace minecraft) that must always be single-press.
     * Explicit list keeps behaviour predictable and matches the user's spec.
     */
    private static final Set<String> SINGLE_PRESS_IDS = Set.of(
            "fire_charge",        // fireball on servers (Hypixel etc.) — the mandatory case
            "ender_pearl",
            "snowball",
            "egg",
            "splash_potion",
            "lingering_potion",
            "experience_bottle",
            "ender_eye",
            "wind_charge",
            "fishing_rod",
            "trident",
            "bow",
            "crossbow"
    );

    private RightClickPolicy() {
    }

    /** Classify the held stack. {@code user} may be null (used only for use-duration probing). */
    public static Kind classify(ItemStack stack, LivingEntity user) {
        if (stack == null || stack.isEmpty()) {
            return Kind.PASS_THROUGH;
        }
        if (isSinglePressItem(stack, user)) {
            return Kind.SINGLE_PRESS;
        }
        if (stack.getItem() instanceof BlockItem) {
            return Kind.BLOCK;
        }
        return Kind.PASS_THROUGH;
    }

    /** True for discrete-use items that must never auto-repeat under CPS. */
    public static boolean isSinglePressItem(ItemStack stack, LivingEntity user) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id != null) {
            String path = id.getPath();
            if (SINGLE_PRESS_IDS.contains(path) || path.equals("bucket") || path.endsWith("_bucket")) {
                return true;
            }
        }

        // Charge / draw / release items (bow, crossbow, trident, spear, horn, …):
        // a single press should begin ONE use action, never a CPS burst.
        UseAction action = stack.getUseAction();
        if (action == UseAction.BOW || action == UseAction.CROSSBOW
                || action == UseAction.SPEAR || action == UseAction.TOOT_HORN) {
            return true;
        }

        return false;
    }

    /**
     * True when AutoRight is allowed to CPS-repeat the held item.
     * Only real building blocks, and only when Block Mode is on, ever repeat.
     */
    public static boolean shouldAutoRepeat(ItemStack stack, boolean blockMode, LivingEntity user) {
        if (!blockMode || stack == null || stack.isEmpty()) {
            return false;
        }
        if (isSinglePressItem(stack, user)) {
            return false;
        }
        return stack.getItem() instanceof BlockItem;
    }
}
