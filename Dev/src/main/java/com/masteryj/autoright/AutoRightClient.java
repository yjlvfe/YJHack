package com.masteryj.autoright;

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
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
    /** Conservative CPS hard-ceiling for Release; also the GUI slider maximum. */
    private static final int MAX_SAFE_CPS = 30;
    private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
    private static final long RIGHT_BLOCK_HOLD_DELAY_MS = 10L;
    private static final long QUICK_TAP_THRESHOLD_MS = 10L;
    private static final InputUtil.Key RIGHT_MOUSE = InputUtil.Type.MOUSE.createFromCode(1);

    private final Random random = new Random();
    private final ClickScheduler rightScheduler = new ClickScheduler();

    public static Config config;
    public static boolean enabled = true;
    public static boolean blockMode = true;
    public static int toggleKeyCode = -1;
    private boolean rightWasDown = false;
    private long rightPressedAtMs = 0L;
    private int rightCurrentDelayMs = 0;
    private boolean blockBurstStarted = false;
    private long lastConfigCheckAtMs = 0L;
    private FileTime lastKnownConfigWriteTime = null;
    private boolean toggleKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("AutoRight", this::tickRightAutoClick));
    }

    private void tickRightAutoClick(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        // Not playing (disabled, GUI open, no world, unfocused, dead, spectator):
        // release any synthetic press and require a fresh press before acting again.
        if (!enabled || !isInActiveGameplay(client)) {
            resetRightAutoClickState();
            return;
        }

        long now = System.currentTimeMillis();

        ItemStack held = client.player != null ? client.player.getMainHandStack() : ItemStack.EMPTY;
        RightClickPolicy.Kind kind = RightClickPolicy.classify(held, client.player);

        boolean mouseDown = isMouseDown(client, 1);
        boolean rising = mouseDown && !rightWasDown;

        // A fresh press resets per-press timing state (used by the block burst path).
        if (rising) {
            rightPressedAtMs = now;
            blockBurstStarted = false;
            rightScheduler.armImmediate();
        }

        switch (kind) {
            case SINGLE_PRESS -> handleSinglePress(mouseDown, rising);
            case BLOCK -> {
                if (blockMode) {
                    handleBlockBurst(now, mouseDown);
                } else {
                    // Block Mode off: do not automate block placement — vanilla feel.
                    passThrough();
                }
            }
            default -> passThrough();
        }

        rightWasDown = mouseDown;
    }

    /**
     * Fire Charge / fireball / pearls / bows … : exactly ONE use per physical press.
     * Vanilla already emits a single use when the button is physically pressed (our
     * END_CLIENT_TICK runs after vanilla input handling), so we must only suppress the
     * vanilla HOLD-repeat and never inject synthetic clicks. A new use requires RELEASE
     * then a fresh PRESS.
     */
    private void handleSinglePress(boolean mouseDown, boolean rising) {
        if (mouseDown && !rising) {
            // Held beyond the initial press-tick: force the use key released so the
            // vanilla itemUseCooldown loop cannot re-fire this item while held.
            KeyBinding.setKeyPressed(RIGHT_MOUSE, false);
            DebugStats.onSinglePressSuppressed();
        }
        rightScheduler.armImmediate();
        blockBurstStarted = false;
    }

    /** Block Mode CPS placement for BlockItems only. */
    private void handleBlockBurst(long now, boolean mouseDown) {
        if (!mouseDown) {
            // Released: a very short tap still places one block (vanilla-like feel).
            if (rightWasDown && now - rightPressedAtMs < QUICK_TAP_THRESHOLD_MS) {
                emitBlockPulse(now);
            }
            rightScheduler.clear();
            blockBurstStarted = false;
            return;
        }

        long pressDuration = now - rightPressedAtMs;
        if (pressDuration < RIGHT_BLOCK_HOLD_DELAY_MS) {
            // Brief initial hold: let the first vanilla placement happen, don't burst yet.
            return;
        }

        if (!blockBurstStarted) {
            blockBurstStarted = true;
            emitBlockPulse(now);
            scheduleNextRightDelay();
            rightScheduler.rearm(now, rightCurrentDelayMs);
            return;
        }

        // One pulse per tick, NO catch-up: a late tick drops the missed placements and
        // reschedules from now instead of bursting a backlog of block-place packets.
        if (rightScheduler.due(now)) {
            emitBlockPulse(now);
            scheduleNextRightDelay();
            rightScheduler.rearm(now, rightCurrentDelayMs);
        }
    }

    /** Emit one synthetic block-place click if the shared budget allows; else drop it. */
    private void emitBlockPulse(long now) {
        if (ActionBudget.INSTANCE.tryConsume(now)) {
            clickMouseKey(RIGHT_MOUSE);
            DebugStats.onAutoRightBlockPulse();
        } else {
            DebugStats.onDroppedBacklog();
        }
    }

    /** Leave vanilla input untouched (no synthetic clicks, no suppression). */
    private void passThrough() {
        rightScheduler.armImmediate();
        blockBurstStarted = false;
    }

    private void clickMouseKey(InputUtil.Key key) {
        KeyBinding.setKeyPressed(key, true);
        KeyBinding.onKeyPressed(key);
        KeyBinding.setKeyPressed(key, false);
    }

    private void resetRightAutoClickState() {
        // Guarantee nothing is left pressed and a fresh physical press is required.
        KeyBinding.setKeyPressed(RIGHT_MOUSE, false);
        rightScheduler.clear();
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
        boolean hasPlayer = client.player != null;
        boolean hasWorld = client.world != null;
        return GameplayGate.active(hasPlayer, hasWorld,
                client.currentScreen != null, client.isWindowFocused(), client.mouse.isCursorLocked(),
                hasPlayer && client.player.isAlive(), hasPlayer && client.player.isSpectator());
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
            // Clamp CPS to the conservative Release ceiling (MAX_SAFE_CPS) so a hand-edited
            // file cannot produce packet-spam placement rates, then enforce min <= max.
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
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
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
