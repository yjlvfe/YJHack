package com.masteryj.autoright;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.ActionBudget;
import com.masteryj.core.ClickScheduler;
import com.masteryj.core.DebugStats;
import com.masteryj.core.GameplayGate;
import com.masteryj.core.PhysicalKeyBinding;
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
import java.nio.file.attribute.FileTime;
import java.util.Random;

public final class AutoRightClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoRight");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoright.json");
    private static final int CURRENT_CONFIG_VERSION = 6;
    private static final int MAX_SAFE_CPS = ClickScheduler.MAX_CPS;
    private static final int DEFAULT_MIN_CPS = 8;
    private static final int DEFAULT_MAX_CPS = 10;
    private static final int LEGACY_DEFAULT_MIN_CPS = 14;
    private static final int LEGACY_DEFAULT_MAX_CPS = 20;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;

    private final Random random = new Random();
    private final ClickScheduler scheduler = new ClickScheduler();

    public static Config config;
    public static boolean enabled = false;
    public static boolean blockMode = true;
    public static int toggleKeyCode = -1;

    private World lastWorld;
    private boolean rightWasDown;
    private boolean requireRelease;
    private boolean pressInvalidated;
    private int pressedSlot = -1;
    private Item pressedItem;
    private RightClickPolicy.Kind pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
    private boolean toggleKeyWasDown;
    private long lastConfigCheckAtNanos = Long.MIN_VALUE;
    private FileTime lastKnownConfigWriteTime;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("AutoRight", this::tickRightAutoClick));
    }

    private void tickRightAutoClick(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        boolean mouseDown = isMouseDown(client, 1);
        boolean rising = mouseDown && !rightWasDown;

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            requireRelease = mouseDown;
            rightWasDown = mouseDown;
            resetSession();
            return;
        }

        boolean activeGameplay = isInActiveGameplay(client);
        if (DebugStats.ENABLED) {
            DebugStats.setAutoRightConfiguredCps(config == null ? 0 : config.minCps,
                    config == null ? 0 : config.maxCps);
            if (rising) DebugStats.onAutoRightPhysicalPress();
        }

        if (!enabled || !activeGameplay) {
            if (mouseDown) requireRelease = true;
            rightWasDown = mouseDown;
            resetSession();
            return;
        }

        if (requireRelease) {
            resetPress();
            if (!mouseDown) {
                requireRelease = false;
                rightWasDown = false;
            } else {
                rightWasDown = true;
            }
            return;
        }

        if (!mouseDown) {
            resetPress();
            rightWasDown = false;
            return;
        }

        ItemStack held = client.player == null ? ItemStack.EMPTY : client.player.getMainHandStack();
        int currentSlot = client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        Item currentItem = held.isEmpty() ? null : held.getItem();

        if (rising) {
            pressedSlot = currentSlot;
            pressedItem = currentItem;
            pressedKind = RightClickPolicy.classify(held, client.player);
            pressInvalidated = false;
            scheduler.clear();
            ActionBudget.INSTANCE.cancel(ActionBudget.Module.RIGHT);
            rightWasDown = true;
            // Vanilla owns the physical first use.
            return;
        }

        if (pressIdentityChanged(pressedSlot, pressedItem, currentSlot, currentItem)) {
            pressInvalidated = true;
        }

        // A held press may never activate a replacement item after a slot/item change.
        if (pressInvalidated) {
            if (shouldSuppressVanillaHold(enabled, activeGameplay, requireRelease, true, pressedKind)) {
                suppressUseKey(client);
            }
            resetCadence();
            rightWasDown = true;
            return;
        }

        switch (pressedKind) {
            case SINGLE_PRESS -> handleSinglePress(client, activeGameplay);
            case BLOCK -> handleBlockHold(client);
            case PASS_THROUGH -> passThrough();
        }

        rightWasDown = true;
    }

    private void handleSinglePress(MinecraftClient client, boolean activeGameplay) {
        if (shouldSuppressVanillaHold(enabled, activeGameplay, requireRelease, false, pressedKind)) {
            suppressUseKey(client);
        }
        resetCadence();
        DebugStats.onSinglePressSuppressed();
    }

    private void handleBlockHold(MinecraftClient client) {
        if (!blockMode) {
            passThrough();
            return;
        }

        // Only schedule a placement while a real block face is targeted. Dispatch is one queued
        // vanilla use press in the next tick; no direct doItemUse calls and never two predictions
        // against the same stale hit result in one tick.
        if (!(client.crosshairTarget instanceof BlockHitResult)) {
            resetCadence();
            return;
        }

        int pulses = scheduler.pulsesThisTick(pickCps());
        if (pulses <= 0) return;

        ActionBudget.INSTANCE.request(ActionBudget.Module.RIGHT, pulses,
                () -> mayEmitBlock(client),
                () -> PhysicalKeyBinding.queuePress(client, client.options.useKey));
    }

    private void passThrough() {
        resetCadence();
        // Bows, crossbows, tridents, shields, food and other held-use items remain fully vanilla.
    }

    private boolean mayEmitBlock(MinecraftClient client) {
        if (!enabled || !blockMode || !isInActiveGameplay(client) || !isMouseDown(client, 1)
                || !(client.crosshairTarget instanceof BlockHitResult)) {
            return false;
        }
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandStack();
        Item item = held.isEmpty() ? null : held.getItem();
        int slot = client.player.getInventory().getSelectedSlot();
        return !pressInvalidated
                && !pressIdentityChanged(pressedSlot, pressedItem, slot, item)
                && RightClickPolicy.classify(held, client.player) == RightClickPolicy.Kind.BLOCK;
    }

    static boolean shouldSuppressVanillaHold(boolean enabled,
                                             boolean activeGameplay,
                                             boolean waitingForRelease,
                                             boolean pressInvalidated,
                                             RightClickPolicy.Kind kind) {
        if (!enabled || !activeGameplay || waitingForRelease) return false;
        return pressInvalidated || kind == RightClickPolicy.Kind.SINGLE_PRESS;
    }

    static boolean pressIdentityChanged(int initialSlot, Object initialItem,
                                        int currentSlot, Object currentItem) {
        return initialSlot != currentSlot || initialItem != currentItem;
    }

    private void suppressUseKey(MinecraftClient client) {
        if (client != null && client.options != null) client.options.useKey.setPressed(false);
    }

    private void resetCadence() {
        scheduler.clear();
        ActionBudget.INSTANCE.cancel(ActionBudget.Module.RIGHT);
    }

    private void resetPress() {
        resetCadence();
        pressedSlot = -1;
        pressedItem = null;
        pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
        pressInvalidated = false;
    }

    private void resetSession() {
        scheduler.clear();
        ActionBudget.INSTANCE.reset(ActionBudget.Module.RIGHT);
        pressedSlot = -1;
        pressedItem = null;
        pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
        pressInvalidated = false;
    }

    private int pickCps() {
        int a = config == null ? DEFAULT_MIN_CPS : config.minCps;
        int b = config == null ? DEFAULT_MAX_CPS : config.maxCps;
        int min = Math.max(1, Math.min(MAX_SAFE_CPS, Math.min(a, b)));
        int max = Math.max(min, Math.min(MAX_SAFE_CPS, Math.max(a, b)));
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private boolean isMouseDown(MinecraftClient client, int button) {
        return client != null && client.options != null && button == 1
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
                requireRelease = isMouseDown(client, 1);
                resetSession();
            }
            sendToggleMessage(client, enabled, "AutoRight");
        }
        toggleKeyWasDown = pressed;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on, String moduleName) {
        if (client == null || client.player == null) return;
        String status = on ? "enabled" : "disabled";
        MutableText text = Text.literal(moduleName + " " + status)
                .formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(text, true);
    }

    public static class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public boolean blockMode = true;
        public int toggleKeyCode = -1;
        public int minCps = DEFAULT_MIN_CPS;
        public int maxCps = DEFAULT_MAX_CPS;

        public void normalize() {
            if (configVersion < 5
                    && minCps == LEGACY_DEFAULT_MIN_CPS && maxCps == LEGACY_DEFAULT_MAX_CPS) {
                minCps = DEFAULT_MIN_CPS;
                maxCps = DEFAULT_MAX_CPS;
            }
            configVersion = CURRENT_CONFIG_VERSION;
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

    private void maybeReloadConfig() {
        long now = System.nanoTime();
        if (lastConfigCheckAtNanos != Long.MIN_VALUE
                && now - lastConfigCheckAtNanos < CONFIG_RELOAD_INTERVAL_NANOS) return;
        lastConfigCheckAtNanos = now;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            FileTime wt = Files.getLastModifiedTime(CONFIG_PATH);
            if (lastKnownConfigWriteTime != null && wt.equals(lastKnownConfigWriteTime)) return;
            config = loadConfig();
            applyRuntimeConfig(config);
        } catch (IOException e) {
            LOGGER.error("Failed to reload config", e);
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
            LOGGER.error("Failed to load config", e);
        }
        Config cfg = new Config();
        cfg.normalize();
        saveConfig(cfg);
        return cfg;
    }

    public void saveConfig(Config cfg) {
        if (cfg == null) return;
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    public static void saveConfigStatic(Config cfg) {
        if (cfg == null) return;
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        blockMode = cfg.blockMode;
        toggleKeyCode = cfg.toggleKeyCode;
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
