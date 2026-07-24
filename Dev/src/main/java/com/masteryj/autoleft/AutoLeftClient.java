package com.masteryj.autoleft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.ActionBudget;
import com.masteryj.core.ClickScheduler;
import com.masteryj.core.DebugStats;
import com.masteryj.core.GameplayGate;
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
    /**
     * CPS hard-ceiling. The scheduler emits at most one pulse per client tick and the
     * client ticks at ~20 TPS, so ~20 CPS is the real executable rate — anything above
     * would just be a displayed value the mod can never actually deliver. Also the GUI
     * slider maximum. Kept in sync with {@link com.masteryj.core.ActionBudget}.
     */
    private static final int MAX_SAFE_CPS = 20;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;
    private static final long MS_TO_NANOS = 1_000_000L;
    private static final InputUtil.Key LEFT_MOUSE = InputUtil.Type.MOUSE.createFromCode(0);

    private final Random random = new Random();
    private final ClickScheduler leftScheduler = new ClickScheduler();

    public static Config config;
    public static boolean enabled = true;
    public static boolean weaponCheck = false;
    public static int toggleKeyCode = -1;
    private int currentDelayMs = 0;
    private boolean leftSyntheticDown = false;
    private long lastConfigCheckAtNanos = Long.MIN_VALUE;
    private FileTime lastKnownConfigWriteTime = null;
    private boolean toggleKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("AutoLeft", this::tickLeftAutoClick));
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

        long now = System.nanoTime();

        // Creative inventory screen: hold left button only, no auto-click
        if (client.currentScreen instanceof CreativeInventoryScreen) {
            leftScheduler.armImmediate();
            holdLeftMouse();
            return;
        }

        // Looking at a block → hold for mining (no CPS pulsing)
        if (client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            leftScheduler.armImmediate();
            holdLeftMouse();
            return;
        }

        // One pulse per tick, NO catch-up. If the client stalled, the missed clicks are
        // dropped and the next pulse is rescheduled from now — a lag spike can never be
        // replayed as a burst. The shared ActionBudget is the final combined-rate guard.
        if (leftScheduler.due(now)) {
            if (ActionBudget.INSTANCE.tryConsume(ActionBudget.Module.LEFT, now)) {
                triggerLeftPulse();
                DebugStats.onAutoLeftPulse();
            } else {
                DebugStats.onAutoLeftBudgetRejected();
            }
            scheduleNextDelay();
            leftScheduler.rearm(now, (long) currentDelayMs * MS_TO_NANOS);
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
        leftScheduler.clear();
        ActionBudget.INSTANCE.reset(ActionBudget.Module.LEFT);
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
        boolean hasPlayer = client.player != null;
        boolean hasWorld = client.world != null;
        return GameplayGate.active(hasPlayer, hasWorld,
                client.currentScreen != null, client.isWindowFocused(), client.mouse.isCursorLocked(),
                hasPlayer && client.player.isAlive(), hasPlayer && client.player.isSpectator());
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
            // Clamp CPS to the conservative Release ceiling (MAX_SAFE_CPS) so a hand-edited
            // file cannot produce packet-spam click rates, then enforce min <= max.
            minCps = Math.max(1, Math.min(MAX_SAFE_CPS, minCps));
            maxCps = Math.max(1, Math.min(MAX_SAFE_CPS, maxCps));
            if (minCps > maxCps) {
                int tmp = minCps;
                minCps = maxCps;
                maxCps = tmp;
            }
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }
    }

    // --- Config persistence ---

    private void maybeReloadConfig() {
        long now = System.nanoTime();
        if (lastConfigCheckAtNanos != Long.MIN_VALUE && now - lastConfigCheckAtNanos < CONFIG_RELOAD_INTERVAL_NANOS) return;
        lastConfigCheckAtNanos = now;
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
