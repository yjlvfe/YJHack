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
    private static final int CURRENT_CONFIG_VERSION = 5;
    /**
     * CPS hard-ceiling. The phase-accumulator scheduler emits at most TWO pulses per client
     * tick and the client ticks at ~20 TPS, so ~40 CPS is the real executable rate — anything
     * above would just be a displayed value the mod can never actually deliver. Also the GUI
     * slider maximum. Kept in sync with {@link com.masteryj.core.ActionBudget} and
     * {@link com.masteryj.core.ClickScheduler#MAX_PULSES_PER_TICK}.
     */
    private static final int MAX_SAFE_CPS = 40;
    /** Shipped defaults (v5). A config still on the legacy defaults is migrated up to these. */
    private static final int DEFAULT_MIN_CPS = 30;
    private static final int DEFAULT_MAX_CPS = 40;
    private static final int LEGACY_DEFAULT_MIN_CPS = 14;
    private static final int LEGACY_DEFAULT_MAX_CPS = 20;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;
    private static final long RIGHT_BLOCK_HOLD_DELAY_NANOS = 10_000_000L;
    private static final long QUICK_TAP_THRESHOLD_NANOS = 10_000_000L;
    private static final InputUtil.Key RIGHT_MOUSE = InputUtil.Type.MOUSE.createFromCode(1);

    private final Random random = new Random();
    private final ClickScheduler rightScheduler = new ClickScheduler();

    public static Config config;
    public static boolean enabled = true;
    public static boolean blockMode = true;
    public static int toggleKeyCode = -1;
    private boolean rightWasDown = false;
    private long rightPressedAtNanos = 0L;
    private boolean blockBurstStarted = false;
    private long lastConfigCheckAtNanos = Long.MIN_VALUE;
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

        if (DebugStats.ENABLED) {
            DebugStats.setAutoRightConfiguredCps(config == null ? 0 : config.minCps, config == null ? 0 : config.maxCps);
            if ((!enabled || !isInActiveGameplay(client)) && isMouseDown(client, 1)) DebugStats.onAutoRightGateRejected();
        }

        // Not playing (disabled, GUI open, no world, unfocused, dead, spectator):
        // release any synthetic press and require a fresh press before acting again.
        if (!enabled || !isInActiveGameplay(client)) {
            resetRightAutoClickState();
            return;
        }

        long now = System.nanoTime();

        ItemStack held = client.player != null ? client.player.getMainHandStack() : ItemStack.EMPTY;
        RightClickPolicy.Kind kind = RightClickPolicy.classify(held, client.player);

        boolean mouseDown = isMouseDown(client, 1);
        boolean rising = mouseDown && !rightWasDown;

        // A fresh press resets per-press timing state (used by the block burst path).
        if (rising) {
            rightPressedAtNanos = now;
            blockBurstStarted = false;
            rightScheduler.armImmediate();
            DebugStats.onAutoRightPhysicalPress();
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
            if (rightWasDown && now - rightPressedAtNanos < QUICK_TAP_THRESHOLD_NANOS) {
                emitBlockPulse(now);
            }
            rightScheduler.clear();
            blockBurstStarted = false;
            return;
        }

        long pressDuration = now - rightPressedAtNanos;
        if (pressDuration < RIGHT_BLOCK_HOLD_DELAY_NANOS) {
            // Brief initial hold: let the first vanilla placement happen, don't burst yet.
            return;
        }

        // First synthetic placement is prompt, then the tick-aware cadence takes over.
        if (!blockBurstStarted) {
            blockBurstStarted = true;
            rightScheduler.armImmediate();
        }

        // Tick-aware cadence: 0..2 placements this tick, NO catch-up. A FIXED cps is added to
        // the phase each tick (never elapsed-time derived), so a stall drops missed placements
        // instead of bursting a backlog of block-place packets.
        int pulses = rightScheduler.pulsesThisTick(pickRightCps());
        int emitted = 0;
        for (int i = 0; i < pulses; i++) {
            if (emitBlockPulse(now)) emitted++;
        }
        if (DebugStats.ENABLED) DebugStats.onAutoRightTickPulses(emitted);
    }

    /** Emit one synthetic block-place click if the shared budget allows; else drop it. Returns true if emitted. */
    private boolean emitBlockPulse(long now) {
        if (ActionBudget.INSTANCE.tryConsume(ActionBudget.Module.RIGHT, now)) {
            clickMouseKey(RIGHT_MOUSE);
            DebugStats.onAutoRightBlockPulse();
            return true;
        }
        DebugStats.onAutoRightBudgetRejected();
        return false;
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
        ActionBudget.INSTANCE.reset(ActionBudget.Module.RIGHT);
        rightPressedAtNanos = 0L;
        rightWasDown = false;
        blockBurstStarted = false;
    }

    /** Random target CPS in [minCps, maxCps] for this tick — the phase-accumulator increment. */
    private int pickRightCps() {
        int min = config.minCps;
        int max = config.maxCps;
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
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
        public int minCps = DEFAULT_MIN_CPS;
        public int maxCps = DEFAULT_MAX_CPS;
        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                // One-time default bump: a config still on the shipped legacy defaults is moved
                // to the new faster defaults; any hand-customised value is preserved untouched.
                if (minCps == LEGACY_DEFAULT_MIN_CPS && maxCps == LEGACY_DEFAULT_MAX_CPS) {
                    minCps = DEFAULT_MIN_CPS;
                    maxCps = DEFAULT_MAX_CPS;
                }
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
