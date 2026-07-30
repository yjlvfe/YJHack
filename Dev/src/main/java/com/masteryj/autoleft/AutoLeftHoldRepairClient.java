package com.masteryj.autoleft;

import com.masteryj.core.ActionBudget;
import com.masteryj.core.ClickScheduler;
import com.masteryj.core.GameplayGate;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Replaces the legacy AutoLeft held-request path with a stricter combat-only cadence.
 *
 * <p>The physical rising-edge click remains vanilla. Follow-up held attacks are submitted only
 * while an entity is actually under the crosshair. Transient invalid weapons or terrain under the
 * crosshair pause the cadence without forcing the user to release and press the mouse again.
 */
public final class AutoLeftHoldRepairClient implements ClientModInitializer {

    private static final int DEFAULT_MIN_CPS = 8;
    private static final int DEFAULT_MAX_CPS = 10;

    private final ClickScheduler scheduler = new ClickScheduler();
    private final Random random = new Random();

    private boolean physicalWasDown;
    private boolean requireRelease;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tickHeldAttack);
    }

    private void tickHeldAttack(MinecraftClient client) {
        // AutoLeftClient runs earlier in the same END tick. Always replace its pending LEFT request
        // so only this corrected held-input path reaches the shared dispatcher.
        ActionBudget.INSTANCE.cancel(ActionBudget.Module.LEFT);

        boolean physicalDown = isMouseDown(client, 0);
        boolean rising = physicalDown && !physicalWasDown;

        if (!AutoLeftClient.enabled || !isInActiveGameplay(client)) {
            if (physicalDown) requireRelease = true;
            clearCadence();
            physicalWasDown = physicalDown;
            return;
        }

        // A press held through a menu, focus loss, death, disable or world transition must still be
        // released once. Ordinary weapon/crosshair changes do not enter this state.
        if (requireRelease) {
            clearCadence();
            if (!physicalDown) {
                requireRelease = false;
                physicalWasDown = false;
            } else {
                physicalWasDown = true;
            }
            return;
        }

        if (!physicalDown) {
            physicalWasDown = false;
            clearCadence();
            return;
        }

        physicalWasDown = true;

        // Vanilla owns the first real click. Follow-up cadence begins on later ticks.
        if (rising) {
            clearCadence();
            return;
        }

        boolean weaponAllowed = !AutoLeftClient.weaponCheck || isHoldingAllowedWeapon(client);
        boolean entityTargeted = client.crosshairTarget instanceof EntityHitResult;
        if (!shouldRunHeldAttack(true, true, physicalDown, weaponAllowed, entityTargeted)) {
            clearCadence();
            return;
        }

        int pulses = scheduler.pulsesThisTick(pickCps());
        if (pulses <= 0) return;

        ActionBudget.INSTANCE.request(ActionBudget.Module.LEFT, pulses,
                () -> mayEmit(client),
                () -> {
                    // Runtime dispatch invokes MinecraftClient.doAttack() directly. This callback
                    // exists only for ActionBudget's deterministic unit-test path.
                });
    }

    private boolean mayEmit(MinecraftClient client) {
        boolean weaponAllowed = !AutoLeftClient.weaponCheck || isHoldingAllowedWeapon(client);
        boolean entityTargeted = client.crosshairTarget instanceof EntityHitResult;
        return shouldRunHeldAttack(AutoLeftClient.enabled, isInActiveGameplay(client),
                isMouseDown(client, 0), weaponAllowed, entityTargeted);
    }

    static boolean shouldRunHeldAttack(boolean enabled,
                                       boolean activeGameplay,
                                       boolean physicalDown,
                                       boolean weaponAllowed,
                                       boolean entityTargeted) {
        return enabled && activeGameplay && physicalDown && weaponAllowed && entityTargeted;
    }

    private void clearCadence() {
        scheduler.clear();
    }

    private int pickCps() {
        int a = AutoLeftClient.config == null ? DEFAULT_MIN_CPS : AutoLeftClient.config.minCps;
        int b = AutoLeftClient.config == null ? DEFAULT_MAX_CPS : AutoLeftClient.config.maxCps;
        int min = Math.max(1, Math.min(ClickScheduler.MAX_CPS, Math.min(a, b)));
        int max = Math.max(min, Math.min(ClickScheduler.MAX_CPS, Math.max(a, b)));
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private boolean isHoldingAllowedWeapon(MinecraftClient client) {
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandStack();
        if (held == null || held.isEmpty()) return false;
        return held.isIn(ItemTags.SWORDS) || held.getItem() instanceof AxeItem;
    }

    private boolean isMouseDown(MinecraftClient client, int button) {
        return client != null && client.getWindow() != null
                && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
    }

    private boolean isInActiveGameplay(MinecraftClient client) {
        boolean hasPlayer = client != null && client.player != null;
        boolean hasWorld = client != null && client.world != null;
        return client != null && GameplayGate.active(hasPlayer, hasWorld,
                client.currentScreen != null, client.isWindowFocused(), client.mouse.isCursorLocked(),
                hasPlayer && client.player.isAlive(), hasPlayer && client.player.isSpectator());
    }
}
