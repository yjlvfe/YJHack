package com.masteryj.autoright;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.config.RecommendedSettings;
import com.masteryj.core.FixedCpsLimiter;
import com.masteryj.core.GameplayGate;
import com.masteryj.core.HumanizedCpsLimiter;
import com.masteryj.core.PhysicalKeyBinding;
import com.masteryj.core.ActionBudget;
import com.masteryj.autoleft.AutoLeftClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fixed-CPS held right click with vanilla queued input.
 *
 * <p>The first physical use remains vanilla. Blocks receive fixed tick-aligned follow-ups through
 * Minecraft's configured use binding; instant items use once per physical press; hold/charge items
 * remain completely vanilla. There is no min/max range, elapsed-time catch-up, or packet spoofing.
 */
public final class AutoRightClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoRight");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoright.json");
    private static final int CURRENT_CONFIG_VERSION = 10;
    private static final int LEGACY_CONSERVATIVE_FIXED_CPS = 10;
    private static final int BLOCK_RESTOCK_GRACE_TICKS = 4;

    private final LegacyMultiVersionPlacementPolicy placementPolicy =
            new LegacyMultiVersionPlacementPolicy();
    private final HumanizedCpsLimiter clickLimiter = new HumanizedCpsLimiter();

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = -1;
    public static int cps = RecommendedSettings.AUTO_RIGHT_CPS;
    public static boolean jitterEnabled = true;

    private World lastWorld;
    private boolean physicalWasDown;
    private boolean requireRelease;
    private boolean pressInvalidated;
    private int pressedSlot = -1;
    private Item pressedItem;
    private RightClickPolicy.Kind pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
    private int blockRestockGraceTicks;
    private boolean toggleKeyWasDown;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        // Queue before vanilla's normal input handling instead of calling doItemUse from rendering.
        ClientTickEvents.START_CLIENT_TICK.register(this::tickRightAutoClick);
    }

    private void tickRightAutoClick(MinecraftClient client) {
        handleToggleKey(client);

        boolean physicalDown = isUseDown(client);
        boolean rising = physicalDown && !physicalWasDown;

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            requireRelease = physicalDown;
            physicalWasDown = physicalDown;
            restoreVanillaUse(client, physicalDown);
            clearRuntimeState();
            return;
        }

        boolean activeGameplay = isInActiveGameplay(client);
        if (!enabled || !activeGameplay) {
            if (physicalDown) requireRelease = true;
            physicalWasDown = physicalDown;
            restoreVanillaUse(client, physicalDown);
            clearRuntimeState();
            return;
        }

        if (requireRelease) {
            restoreVanillaUse(client, physicalDown);
            clearPressState();
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
            restoreVanillaUse(client, false);
            clearPressState();
            return;
        }

        ItemStack held = client.player == null ? ItemStack.EMPTY : client.player.getMainHandStack();
        int currentSlot = client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        Item currentItem = held.isEmpty() ? null : held.getItem();
        RightClickPolicy.Kind currentKind = RightClickPolicy.classify(held, client.player);

        if (rising) {
            pressedSlot = currentSlot;
            pressedItem = currentItem;
            pressedKind = currentKind;
            pressInvalidated = false;
            blockRestockGraceTicks = 0;
            physicalWasDown = true;
            restoreVanillaUse(client, true);
            placementPolicy.clearRuntimeState();
            return;
        }

        physicalWasDown = true;
        if (pressIdentityChanged(pressedSlot, pressedItem, currentSlot, currentItem)) {
            if (LegacyMultiVersionPlacementPolicy.canContinueAcrossSlotChange(pressedKind, currentKind)) {
                adoptCurrentBlock(currentSlot, currentItem, currentKind);
            } else if (pressedKind == RightClickPolicy.Kind.BLOCK && held.isEmpty()
                    && blockRestockGraceTicks < BLOCK_RESTOCK_GRACE_TICKS) {
                // A stack can become empty one or two ticks before NinjaBridge selects the next
                // block stack. Suppress the empty-hand interval instead of permanently killing hold.
                blockRestockGraceTicks++;
                restoreVanillaUse(client, false);
                placementPolicy.clearRuntimeState();
                return;
            } else {
                pressInvalidated = true;
            }
        }

        if (pressInvalidated) {
            restoreVanillaUse(client, false);
            placementPolicy.clearRuntimeState();
            return;
        }

        if (pressedKind == RightClickPolicy.Kind.SINGLE_PRESS) {
            restoreVanillaUse(client, false);
            placementPolicy.clearRuntimeState();
            return;
        }

        if (pressedKind != RightClickPolicy.Kind.BLOCK) {
            // Food, bows, shields and charge/hold items remain completely vanilla.
            restoreVanillaUse(client, true);
            placementPolicy.clearRuntimeState();
            return;
        }

        // Budget gate

        restoreVanillaUse(client, false);
        boolean validCandidate = client.crosshairTarget instanceof BlockHitResult;
        long nowNanos = System.nanoTime();
        int pulses;
        if (jitterEnabled) {
            pulses = placementPolicy.pulsesThisTick(cps,
                    enabled, activeGameplay, physicalDown, validCandidate);
            if (pulses > 0) {
                pulses = clickLimiter.acquire(nowNanos, cps, true);
            }
        } else {
            pulses = placementPolicy.pulsesThisTick(cps,
                    enabled, activeGameplay, physicalDown, validCandidate);
        }
        for (int i = 0; i < pulses; i++) {
            if (!PhysicalKeyBinding.queuePress(client, client.options.useKey)) break;
            AutoLeftClient.rightCpsTracker.recordClick();
        }
    }

    private void adoptCurrentBlock(int currentSlot, Item currentItem, RightClickPolicy.Kind currentKind) {
        pressedSlot = currentSlot;
        pressedItem = currentItem;
        pressedKind = currentKind;
        blockRestockGraceTicks = 0;
        placementPolicy.clearRuntimeState();
    }

    static boolean shouldSuppressVanillaHold(boolean enabled,
                                             boolean activeGameplay,
                                             boolean waitingForRelease,
                                             boolean pressInvalidated,
                                             RightClickPolicy.Kind kind) {
        if (!enabled || !activeGameplay || waitingForRelease) return false;
        return pressInvalidated || kind == RightClickPolicy.Kind.SINGLE_PRESS
                || kind == RightClickPolicy.Kind.BLOCK;
    }

    static boolean pressIdentityChanged(int initialSlot, Object initialItem,
                                        int currentSlot, Object currentItem) {
        return initialSlot != currentSlot || initialItem != currentItem;
    }

    private void restoreVanillaUse(MinecraftClient client, boolean pressed) {
        if (client != null && client.options != null) client.options.useKey.setPressed(pressed);
    }

    private void clearPressState() {
        placementPolicy.clearRuntimeState();
        pressedSlot = -1;
        pressedItem = null;
        pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
        pressInvalidated = false;
        blockRestockGraceTicks = 0;
    }

    private void clearRuntimeState() {
        clearPressState();
        clickLimiter.clearTimingState();
    }

    private boolean isUseDown(MinecraftClient client) {
        return client != null && client.options != null
                && PhysicalKeyBinding.isPressed(client, client.options.useKey);
    }

    private boolean isInActiveGameplay(MinecraftClient client) {
        boolean hasPlayer = client != null && client.player != null;
        boolean hasWorld = client != null && client.world != null;
        return client != null && GameplayGate.active(hasPlayer, hasWorld,
                client.currentScreen != null, client.isWindowFocused(), client.mouse.isCursorLocked(),
                hasPlayer && client.player.isAlive(), hasPlayer && client.player.isSpectator());
    }

    private void handleToggleKey(MinecraftClient client) {
        int key = normalizeToggleKeyCode(toggleKeyCode);
        if (key == -1) {
            toggleKeyWasDown = false;
            return;
        }
        boolean pressed = isToggleBindingPressed(client, key);
        if (pressed && !toggleKeyWasDown) {
            enabled = !enabled;
            if (config != null) {
                config.enabled = enabled;
                saveConfig(config);
            }
            if (!enabled) {
                boolean physicalDown = isUseDown(client);
                requireRelease = physicalDown;
                restoreVanillaUse(client, physicalDown);
                clearRuntimeState();
            }
            sendToggleMessage(client, enabled);
        }
        toggleKeyWasDown = pressed;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on) {
        if (client == null || client.player == null) return;
        MutableText message = Text.literal("AutoRight " + (on ? "enabled" : "disabled"))
                .formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(message, true);
    }

    public static Config recommendedDefaults() {
        return Config.recommendedDefaults();
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public int toggleKeyCode = -1;
        public int cps = RecommendedSettings.AUTO_RIGHT_CPS;
        public boolean jitterEnabled = true;

        // Read-only migration fields for v7 and older files; normalized back to null.
        public Integer minCps;
        public Integer maxCps;

        public static Config recommendedDefaults() {
            Config cfg = new Config();
            cfg.configVersion = CURRENT_CONFIG_VERSION;
            cfg.enabled = false;
            cfg.toggleKeyCode = -1;
            cfg.cps = RecommendedSettings.AUTO_RIGHT_CPS;
            cfg.jitterEnabled = true;
            cfg.minCps = null;
            cfg.maxCps = null;
            cfg.normalize();
            return cfg;
        }

        public Config copy() {
            Config result = new Config();
            result.configVersion = configVersion;
            result.enabled = enabled;
            result.toggleKeyCode = toggleKeyCode;
            result.cps = cps;
            result.jitterEnabled = jitterEnabled;
            result.minCps = minCps;
            result.maxCps = maxCps;
            result.normalize();
            return result;
        }

        public void normalize() {
            int previousVersion = configVersion;
            if (previousVersion < 8) {
                if (maxCps != null) cps = maxCps;
                else if (minCps != null) cps = minCps;
            } else if (previousVersion == 8 && cps == LEGACY_CONSERVATIVE_FIXED_CPS) {
                // v1.3.0's exact default was half the original effective placement cadence.
                cps = RecommendedSettings.AUTO_RIGHT_CPS;
            }
            if (previousVersion < 10) jitterEnabled = true;
            configVersion = CURRENT_CONFIG_VERSION;
            cps = FixedCpsLimiter.clampCps(cps);
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
            minCps = null;
            maxCps = null;
        }
    }

    private Config loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (loaded != null) {
                    loaded.normalize();
                    saveConfig(loaded);
                    return loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load AutoRight config", e);
        }
        Config fresh = recommendedDefaults();
        saveConfig(fresh);
        return fresh;
    }

    private void saveConfig(Config cfg) {
        saveConfigStatic(cfg);
    }

    public static void saveConfigStatic(Config cfg) {
        if (cfg == null) return;
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
        } catch (IOException e) {
            LOGGER.error("Failed to save AutoRight config", e);
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        toggleKeyCode = cfg.toggleKeyCode;
        cps = cfg.cps;
        jitterEnabled = cfg.jitterEnabled;
    }

    private static int normalizeToggleKeyCode(int key) {
        if (key >= 1000) return key;
        return key > 0 ? key : -1;
    }

    private static boolean isToggleBindingPressed(MinecraftClient client, int key) {
        if (client == null || client.getWindow() == null
                || client.currentScreen != null || !client.isWindowFocused()) return false;
        long handle = client.getWindow().getHandle();
        if (key >= 1000) return GLFW.glfwGetMouseButton(handle, key - 1000) == GLFW.GLFW_PRESS;
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }
}
