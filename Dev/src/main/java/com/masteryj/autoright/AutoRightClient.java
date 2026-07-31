package com.masteryj.autoright;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.FixedCpsLimiter;
import com.masteryj.core.GameplayGate;
import com.masteryj.core.PhysicalKeyBinding;
import com.masteryj.mixin.MinecraftClientInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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

/** Fixed-rate direct right click for blocks; no shared budget, queue, random range, or catch-up. */
public final class AutoRightClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoRight");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoright.json");
    private static final int CURRENT_CONFIG_VERSION = 7;
    private static final int DEFAULT_CPS = 10;

    private final FixedCpsLimiter limiter = new FixedCpsLimiter();

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = -1;
    public static int cps = DEFAULT_CPS;

    private World lastWorld;
    private boolean physicalWasDown;
    private boolean requireRelease;
    private boolean pressInvalidated;
    private int pressedSlot = -1;
    private Item pressedItem;
    private RightClickPolicy.Kind pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
    private boolean toggleKeyWasDown;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        WorldRenderEvents.END.register(context -> frame(MinecraftClient.getInstance()));
    }

    private void frame(MinecraftClient client) {
        handleToggleKey(client);

        boolean physicalDown = isUseDown(client);
        boolean rising = physicalDown && !physicalWasDown;

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            requireRelease = physicalDown;
            physicalWasDown = physicalDown;
            resetSession();
            return;
        }

        boolean activeGameplay = isInActiveGameplay(client);
        if (!enabled || !activeGameplay) {
            if (physicalDown) requireRelease = true;
            physicalWasDown = physicalDown;
            resetSession();
            return;
        }

        if (requireRelease) {
            resetPress();
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
            resetPress();
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
            physicalWasDown = true;
            limiter.reset();
            // Vanilla handles the first real physical use immediately.
            return;
        }

        physicalWasDown = true;
        if (pressIdentityChanged(pressedSlot, pressedItem, currentSlot, currentItem)) {
            pressInvalidated = true;
        }

        if (pressInvalidated) {
            if (shouldSuppressVanillaHold(enabled, activeGameplay, requireRelease, true, pressedKind)) {
                suppressUseKey(client);
            }
            limiter.reset();
            return;
        }

        if (pressedKind == RightClickPolicy.Kind.SINGLE_PRESS) {
            if (shouldSuppressVanillaHold(enabled, activeGameplay, requireRelease, false, pressedKind)) {
                suppressUseKey(client);
            }
            limiter.reset();
            return;
        }

        if (pressedKind != RightClickPolicy.Kind.BLOCK) {
            // Food, bows, shields and charge/hold items remain completely vanilla.
            limiter.reset();
            return;
        }

        if (!(client.crosshairTarget instanceof BlockHitResult)) return;

        if (limiter.acquire(System.nanoTime(), cps)) {
            ((MinecraftClientInvoker) client).yjhack$invokeDoItemUse();
        }
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

    private void resetPress() {
        limiter.reset();
        pressedSlot = -1;
        pressedItem = null;
        pressedKind = RightClickPolicy.Kind.PASS_THROUGH;
        pressInvalidated = false;
    }

    private void resetSession() {
        resetPress();
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
                requireRelease = isUseDown(client);
                resetSession();
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

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public int toggleKeyCode = -1;
        public int cps = DEFAULT_CPS;

        // Read-only migration fields for v6 and older files; normalized back to null.
        public Integer minCps;
        public Integer maxCps;

        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                if (maxCps != null) cps = maxCps;
                else if (minCps != null) cps = minCps;
            }
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
        Config fresh = new Config();
        fresh.normalize();
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
