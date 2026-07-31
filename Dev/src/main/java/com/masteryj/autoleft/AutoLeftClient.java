package com.masteryj.autoleft;

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
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fixed-rate direct left click: no shared budget, queue, random range, or catch-up. */
public final class AutoLeftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoLeft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoleft.json");
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
    private boolean toggleKeyWasDown;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        WorldRenderEvents.END.register(context -> frame(MinecraftClient.getInstance()));
    }

    private void frame(MinecraftClient client) {
        handleToggleKey(client);

        boolean physicalDown = isAttackDown(client);
        boolean rising = physicalDown && !physicalWasDown;

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            requireRelease = physicalDown;
            physicalWasDown = physicalDown;
            limiter.reset();
            return;
        }

        boolean activeGameplay = isInActiveGameplay(client);
        if (!enabled || !activeGameplay) {
            if (physicalDown) requireRelease = true;
            physicalWasDown = physicalDown;
            limiter.reset();
            return;
        }

        if (requireRelease) {
            limiter.reset();
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
            limiter.reset();
            return;
        }

        physicalWasDown = true;
        if (rising) {
            // The real physical press is handled immediately by vanilla.
            limiter.reset();
            return;
        }

        boolean entityTargeted = client.crosshairTarget instanceof EntityHitResult;
        if (!shouldRunDirectAttack(enabled, activeGameplay, physicalDown, entityTargeted)) return;

        if (limiter.acquire(System.nanoTime(), cps)) {
            ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
        }
    }

    static boolean shouldRunDirectAttack(boolean enabled,
                                         boolean activeGameplay,
                                         boolean physicalDown,
                                         boolean entityTargeted) {
        return enabled && activeGameplay && physicalDown && entityTargeted;
    }

    private boolean isAttackDown(MinecraftClient client) {
        return client != null && client.options != null
                && PhysicalKeyBinding.isPressed(client, client.options.attackKey);
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
                requireRelease = isAttackDown(client);
                limiter.reset();
            }
            sendToggleMessage(client, enabled);
        }
        toggleKeyWasDown = pressed;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on) {
        if (client == null || client.player == null) return;
        MutableText message = Text.literal("AutoLeft " + (on ? "enabled" : "disabled"))
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
            LOGGER.error("Failed to load AutoLeft config", e);
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
            LOGGER.error("Failed to save AutoLeft config", e);
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
