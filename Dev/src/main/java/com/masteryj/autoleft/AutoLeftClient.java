package com.masteryj.autoleft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Random;

public final class AutoLeftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoLeft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoleft.json");
    private static final int CURRENT_CONFIG_VERSION = 4;
    /** Hard CPS ceiling for hand-edited configs — matches the GUI slider maximum. */
    private static final int MAX_SAFE_CPS = 40;
    private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
    private static final int MAX_CATCHUP_PULSES_PER_TICK = 50;
    private static final InputUtil.Key LEFT_MOUSE = InputUtil.Type.MOUSE.createFromCode(0);

    private final Random random = new Random();

    public static Config config;
    public static boolean enabled = true;
    public static boolean weaponCheck = false;
    public static int toggleKeyCode = -1;
    private long nextClickAtMs = 0L;
    private int currentDelayMs = 0;
    private boolean leftSyntheticDown = false;
    private long lastConfigCheckAtMs = 0L;
    private FileTime lastKnownConfigWriteTime = null;
    private boolean toggleKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tickLeftAutoClick);
    }

    private void tickLeftAutoClick(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        if (!enabled || !isInActiveGameplay(client)) {
            resetState();
            return;
        }

        if (weaponCheck && client.player != null) {
            ItemStack held = client.player.getMainHandStack();
            if (!isSwordOrAxe(held)) {
                resetState();
                return;
            }
        }

        if (!isMouseDown(client, 0)) {
            resetState();
            return;
        }

        long now = System.currentTimeMillis();

        // Creative inventory screen: hold left button only, no auto-click
        if (client.currentScreen instanceof CreativeInventoryScreen) {
            nextClickAtMs = 0L;
            holdLeftMouse();
            return;
        }

        // Looking at a block → hold for mining (no CPS pulsing)
        if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            nextClickAtMs = 0L;
            holdLeftMouse();
            return;
        }

        // First click in a sequence
        if (nextClickAtMs == 0L) {
            triggerLeftPulse();
            scheduleNextDelay();
            nextClickAtMs = now + currentDelayMs;
            return;
        }

        // Catch-up pulses if behind
        int pulses = 0;
        while (now >= nextClickAtMs && pulses < MAX_CATCHUP_PULSES_PER_TICK) {
            triggerLeftPulse();
            scheduleNextDelay();
            nextClickAtMs += currentDelayMs;
            pulses++;
            if (nextClickAtMs > now + 1000L) {
                nextClickAtMs = now + currentDelayMs;
                break;
            }
        }

        if (now >= nextClickAtMs) {
            nextClickAtMs = now + currentDelayMs;
        }

        releaseLeftHold();
    }

    private void triggerLeftPulse() {
        holdLeftMouse();
        KeyBinding.onKeyPressed(LEFT_MOUSE);
    }

    private void holdLeftMouse() {
        if (!leftSyntheticDown) {
            KeyBinding.setKeyPressed(LEFT_MOUSE, true);
            leftSyntheticDown = true;
        }
    }

    private void releaseLeftHold() {
        if (leftSyntheticDown) {
            KeyBinding.setKeyPressed(LEFT_MOUSE, false);
            leftSyntheticDown = false;
        }
    }

    private void resetState() {
        releaseLeftHold();
        nextClickAtMs = 0L;
    }

    /** Simple CPS: random integer between minCps and maxCps (inclusive). */
    private void scheduleNextDelay() {
        int min = config.minCps;
        int max = config.maxCps;
        int cps;
        if (min >= max) {
            cps = min;
        } else {
            cps = min + random.nextInt(max - min + 1);
        }
        currentDelayMs = cpsToDelay(cps);
    }

    private int cpsToDelay(int cps) {
        int abs = Math.abs(cps);
        if (abs == 0) return Integer.MAX_VALUE;
        return Math.max(1, (int) Math.ceil(1000.0 / abs));
    }

    private boolean isMouseDown(MinecraftClient client, int button) {
        return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == 1;
    }

    private boolean isInActiveGameplay(MinecraftClient client) {
        if (client.player == null) return false;
        if (client.world == null) return false;
        if (client.currentScreen != null) return false;
        if (!client.isWindowFocused()) return false;
        if (!client.mouse.isCursorLocked()) return false;
        if (!client.player.isAlive()) return false;
        if (client.player.isSpectator()) return false;
        return true;
    }

    private boolean isSwordOrAxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem;
    }

    // --- Toggle key ---

    private void handleToggleKey(MinecraftClient client) {
        int key = normalizeToggleKeyCode(toggleKeyCode);
        if (key == -1) {
            toggleKeyWasDown = false;
            return;
        }
        boolean pressed = isToggleBindingPressed(client, key);
        if (pressed && !toggleKeyWasDown) {
            enabled = !enabled;
            config.enabled = enabled;
            saveConfig(config);
            if (!enabled) resetState();
            sendToggleMessage(client, enabled, "AutoLeft");
        }
        toggleKeyWasDown = pressed;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on, String moduleName) {
        if (client.player == null) return;
        String status = on ? "enabled" : "disabled";
        MutableText text = Text.literal(moduleName + " " + status).formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(text, false);
        client.player.sendMessage(text, true);
    }

    // --- Config ---

    public static class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = true;
        public boolean weaponCheck = false;
        public int toggleKeyCode = -1;
        public int minCps = 8;
        public int maxCps = 16;
        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                configVersion = CURRENT_CONFIG_VERSION;
            }
            // Clamp CPS to the same ceiling the GUI slider enforces (MAX_SAFE_CPS)
            // so a hand-edited file cannot produce absurd click rates / packet spam.
            // Ordering (min>max) is tolerated by scheduleNextDelay().
            minCps = Math.max(1, Math.min(MAX_SAFE_CPS, minCps));
            maxCps = Math.max(1, Math.min(MAX_SAFE_CPS, maxCps));
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }
    }

    // --- Config persistence ---

    private void maybeReloadConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheckAtMs < CONFIG_RELOAD_INTERVAL_MS) return;
        lastConfigCheckAtMs = now;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            FileTime wt = Files.getLastModifiedTime(CONFIG_PATH);
            if (lastKnownConfigWriteTime != null && wt.equals(lastKnownConfigWriteTime)) return;
            config = loadConfig();
            applyRuntimeConfig(config);
        } catch (IOException e) {
            LOGGER.error("Failed to reload config: {}", e.getMessage());
        }
    }

    private Config loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config cfg = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (cfg != null) {
                    // Migration: preserve user settings, don't delete on version bump.
                    // New fields absent from the JSON get their class-level default from Gson.
                    cfg.normalize();
                    lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
                    return cfg;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config: {}", e.getMessage());
        }
        Config cfg = new Config();
        cfg.normalize();
        saveConfig(cfg);
        return cfg;
    }

    public void saveConfig(Config cfg) {
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static void saveConfigStatic(Config cfg) {
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        weaponCheck = cfg.weaponCheck;
        toggleKeyCode = cfg.toggleKeyCode;
    }

    // --- Util ---

    private static int normalizeToggleKeyCode(int key) {
        if (key >= 1000) return key;
        return key > 0 ? key : -1;
    }

    private static boolean isToggleBindingPressed(MinecraftClient client, int key) {
        if (client.currentScreen != null || !client.isWindowFocused()) return false;
        long handle = client.getWindow().getHandle();
        if (key >= 1000) {
            return GLFW.glfwGetMouseButton(handle, key - 1000) == 1;
        }
        return GLFW.glfwGetKey(handle, key) == 1;
    }
}
