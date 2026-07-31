package com.masteryj.autoleft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.config.RecommendedSettings;
import com.masteryj.core.FixedCpsLimiter;
import com.masteryj.core.GameplayGate;
import com.masteryj.core.HumanizedCpsLimiter;
import com.masteryj.core.PhysicalKeyBinding;
import com.masteryj.mixin.MinecraftClientInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One fixed physical-hold attack path. The first click remains vanilla; follow-up attempts use
 * Minecraft's own doAttack() through a monotonic, no-backlog multi-version policy.
 */
public final class AutoLeftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AutoLeft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("autoleft.json");
    private static final int CURRENT_CONFIG_VERSION = 9;

    private final LegacyMultiVersionCombatPolicy combatPolicy = new LegacyMultiVersionCombatPolicy();
    private final HumanizedCpsLimiter clickLimiter = new HumanizedCpsLimiter();

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = -1;
    public static int cps = RecommendedSettings.AUTO_LEFT_CPS;
    public static boolean jitterEnabled = true;

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
            restoreVanillaAttack(client, physicalDown);
            clearRuntimeState();
            return;
        }

        boolean activeGameplay = isInActiveGameplay(client);
        if (!enabled || !activeGameplay) {
            if (physicalDown) requireRelease = true;
            physicalWasDown = physicalDown;
            restoreVanillaAttack(client, physicalDown);
            clearRuntimeState();
            return;
        }

        if (requireRelease) {
            restoreVanillaAttack(client, physicalDown);
            clearRuntimeState();
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
            restoreVanillaAttack(client, false);
            clearRuntimeState();
            return;
        }

        physicalWasDown = true;
        boolean entityTargeted = client.crosshairTarget instanceof EntityHitResult;

        if (rising) {
            // The real press is not synthesized or duplicated. It follows the normal client path.
            restoreVanillaAttack(client, true);
            clearRuntimeState();
            if (entityTargeted) {
                combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
                        true, true, true, true);
            }
            return;
        }

        if (!shouldRunDirectAttack(enabled, activeGameplay, physicalDown, entityTargeted)) {
            // Mining and empty-space holds remain vanilla. No artificial miss packets are created.
            restoreVanillaAttack(client, true);
            clearRuntimeState();
            return;
        }

        // There is one follow-up owner only: vanilla held-repeat is suppressed while the policy
        // invokes Minecraft's own doAttack(). The physical state is read separately from GLFW.
        restoreVanillaAttack(client, false);
        if (combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
                enabled, activeGameplay, physicalDown, entityTargeted)
                && clickLimiter.acquire(System.nanoTime(), cps, jitterEnabled)) {
            ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
        }
    }

    static boolean shouldRunDirectAttack(boolean enabled,
                                         boolean activeGameplay,
                                         boolean physicalDown,
                                         boolean entityTargeted) {
        return enabled && activeGameplay && physicalDown && entityTargeted;
    }

    private void clearRuntimeState() {
        combatPolicy.clearRuntimeState();
        clickLimiter.clearTimingState();
    }

    private void restoreVanillaAttack(MinecraftClient client, boolean pressed) {
        if (client != null && client.options != null) client.options.attackKey.setPressed(pressed);
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
                boolean physicalDown = isAttackDown(client);
                requireRelease = physicalDown;
                restoreVanillaAttack(client, physicalDown);
                clearRuntimeState();
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

    public static Config recommendedDefaults() {
        return Config.recommendedDefaults();
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public int toggleKeyCode = -1;
        public int cps = RecommendedSettings.AUTO_LEFT_CPS;
        public boolean jitterEnabled = true;

        // Read-only migration fields for v7 and older files; normalized back to null.
        public Integer minCps;
        public Integer maxCps;

        public static Config recommendedDefaults() {
            Config cfg = new Config();
            cfg.configVersion = CURRENT_CONFIG_VERSION;
            cfg.enabled = false;
            cfg.toggleKeyCode = -1;
            cfg.cps = RecommendedSettings.AUTO_LEFT_CPS;
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
            if (configVersion < CURRENT_CONFIG_VERSION) {
                if (maxCps != null) cps = maxCps;
                else if (minCps != null) cps = minCps;
                if (configVersion < 9) jitterEnabled = true;
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
            LOGGER.error("Failed to save AutoLeft config", e);
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
