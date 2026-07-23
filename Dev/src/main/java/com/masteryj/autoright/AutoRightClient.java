package com.masteryj.autoright;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Random;

public final class AutoRightClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoRight");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoright.json");
    private static final int CURRENT_CONFIG_VERSION = 4;
    private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
    private static final long RIGHT_BLOCK_HOLD_DELAY_MS = 10L;
    private static final long RIGHT_HOLD_DELAY_MS = 30L;
    private static final long QUICK_TAP_THRESHOLD_MS = 10L;
    private static final int MAX_CATCHUP_CLICKS_PER_TICK = 50;
    private static final InputUtil.Key RIGHT_MOUSE = InputUtil.Type.MOUSE.createFromCode(1);
    private static final int MOUSE_KEY_OFFSET = 1000;

    private final Random random = new Random();

    public static Config config;
    public static boolean enabled = true;
    public static boolean blockMode = true;
    public static int toggleKeyCode = -1;
    private boolean rightWasDown = false;
    private boolean rightUseHold = false;
    private long rightPressedAtMs = 0L;
    private long rightNextClickAtMs = 0L;
    private int rightCurrentDelayMs = 0;
    private boolean blockBurstStarted = false;
    private long lastConfigCheckAtMs = 0L;
    private FileTime lastKnownConfigWriteTime = null;
    private boolean toggleKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tickRightAutoClick);
    }

    private void tickRightAutoClick(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        if (!enabled || !isInActiveGameplay(client)) {
            resetRightAutoClickState();
            return;
        }

        long now = System.currentTimeMillis();

        boolean isBlockItem = false;
        if (client.player != null) {
            ItemStack held = client.player.getMainHandStack();
            isBlockItem = held.getItem() instanceof BlockItem;
        }

        boolean mouseDown = isMouseDown(client, 1);

        // Track first press — set timestamps when mouse goes down
        if (mouseDown && !rightWasDown) {
            rightPressedAtMs = now;
            blockBurstStarted = false;
            rightNextClickAtMs = 0L;
        }

        // --- Mouse NOT down: handle short-click / reset ---
        if (!mouseDown) {
            boolean immediatePlace = blockMode && isBlockItem;
            if (rightWasDown) {
                long pressDuration = now - rightPressedAtMs;
                if (immediatePlace) {
                    // Quick tap with block: if press was very short, trigger a click
                    // so the block places immediately (vanilla-like behavior).
                    if (pressDuration < QUICK_TAP_THRESHOLD_MS) {
                        clickMouseKey(RIGHT_MOUSE);
                    }
                    resetRightAutoClickState();
                    return;
                }
                if (pressDuration < RIGHT_HOLD_DELAY_MS) {
                    clickMouseKey(RIGHT_MOUSE);
                    resetRightAutoClickState();
                    return;
                }
            }
            resetRightAutoClickState();
            return;
        }

        // --- Mouse IS down ---
        if (blockMode && isBlockItem) {
            // Building-block burst mode
            long pressDuration = now - rightPressedAtMs;
            if (pressDuration < RIGHT_BLOCK_HOLD_DELAY_MS) {
                releaseRightHold();
                rightWasDown = true;
                return;
            }

            releaseRightHold();

            if (!blockBurstStarted) {
                blockBurstStarted = true;
                scheduleNextRightDelay();
                clickMouseKey(RIGHT_MOUSE);
                rightNextClickAtMs = now + rightCurrentDelayMs;
                rightWasDown = true;
                return;
            }

            // Catch-up clicks
            int clicks = 0;
            while (now >= rightNextClickAtMs && clicks < MAX_CATCHUP_CLICKS_PER_TICK) {
                clickMouseKey(RIGHT_MOUSE);
                scheduleNextRightDelay();
                rightNextClickAtMs += rightCurrentDelayMs;
                clicks++;
                if (rightNextClickAtMs > now + 1000L) {
                    rightNextClickAtMs = now + rightCurrentDelayMs;
                    break;
                }
            }
            if (now >= rightNextClickAtMs) {
                rightNextClickAtMs = now + rightCurrentDelayMs;
            }
            rightWasDown = true;
        } else {
            // Normal auto-right-click (blockMode off, or not holding a block)
            releaseRightHold();

            // Fix: When blockMode is on but not holding a block, continue with normal clicking
            // instead of resetting state (prevents race condition)
            if (blockMode && !isBlockItem) {
                // Continue with normal auto-right-click behavior
            }

            long pressDuration = now - rightPressedAtMs;
            if (pressDuration < RIGHT_HOLD_DELAY_MS) {
                rightWasDown = true;
                return;
            }

            // First click
            if (rightNextClickAtMs == 0L) {
                scheduleNextRightDelay();
                clickMouseKey(RIGHT_MOUSE);
                rightNextClickAtMs = now + rightCurrentDelayMs;
                rightWasDown = true;
                return;
            }

            // Catch-up clicks
            int clicks = 0;
            while (now >= rightNextClickAtMs && clicks < MAX_CATCHUP_CLICKS_PER_TICK) {
                clickMouseKey(RIGHT_MOUSE);
                scheduleNextRightDelay();
                rightNextClickAtMs += rightCurrentDelayMs;
                clicks++;
                if (rightNextClickAtMs > now + 1000L) {
                    rightNextClickAtMs = now + rightCurrentDelayMs;
                    break;
                }
            }
            if (now >= rightNextClickAtMs) {
                rightNextClickAtMs = now + rightCurrentDelayMs;
            }
            rightWasDown = true;
        }
    }

    private void clickMouseKey(InputUtil.Key key) {
        KeyBinding.setKeyPressed(key, true);
        KeyBinding.onKeyPressed(key);
        KeyBinding.setKeyPressed(key, false);
    }

    private void releaseRightHold() {
        if (rightUseHold) {
            KeyBinding.setKeyPressed(RIGHT_MOUSE, false);
            rightUseHold = false;
        }
    }

    private void resetRightAutoClickState() {
        releaseRightHold();
        rightNextClickAtMs = 0L;
        rightPressedAtMs = 0L;
        rightWasDown = false;
        blockBurstStarted = false;
    }

    /** Simple CPS: random integer between minCps and maxCps (inclusive). */
    private void scheduleNextRightDelay() {
        int min = config.minCps;
        int max = config.maxCps;
        int cps;
        if (min >= max) {
            cps = min;
        } else {
            cps = min + random.nextInt(max - min + 1);
        }
        rightCurrentDelayMs = cpsToDelay(cps);
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
            if (!enabled) resetRightAutoClickState();
            sendToggleMessage(client, enabled, "AutoRight");
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
        public boolean blockMode = true;
        public int toggleKeyCode = -1;
        public int minCps = 14;
        public int maxCps = 28;
        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                configVersion = CURRENT_CONFIG_VERSION;
            }
            // Clamp CPS to sane bounds so a hand-edited file cannot produce
            // absurd click rates. Ordering is tolerated by scheduleNextDelay().
            minCps = Math.max(1, Math.min(1000, minCps));
            maxCps = Math.max(1, Math.min(1000, maxCps));
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
        } catch (IOException ignored) {
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        blockMode = cfg.blockMode;
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
