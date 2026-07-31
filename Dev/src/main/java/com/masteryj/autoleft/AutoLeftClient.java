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
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
    private static final Identifier CPS_HUD_LAYER_ID = Identifier.of("yjhack", "cps_hud");

    private final LegacyMultiVersionCombatPolicy combatPolicy = new LegacyMultiVersionCombatPolicy();
    private final HumanizedCpsLimiter clickLimiter = new HumanizedCpsLimiter();

    // CPS tracking for HUD display
    private static final CpsTracker leftCpsTracker = new CpsTracker();
    public static final CpsTracker rightCpsTracker = new CpsTracker();
    public static int leftCps;
    public static int rightCps;
    public static int cpsHudX = 8;
    public static int cpsHudY = 20;

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
        HudLayerRegistrationCallback.EVENT.register(layeredDrawer ->
                layeredDrawer.attachLayerAfter(
                        IdentifiedLayer.MISC_OVERLAYS,
                        CPS_HUD_LAYER_ID,
                        this::renderCpsHud));
    }

    private void frame(MinecraftClient client) {
        handleToggleKey(client);

        // Update CPS counts for HUD display
        leftCps = leftCpsTracker.currentCps();
        rightCps = rightCpsTracker.currentCps();

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
        boolean shouldFire;
        if (jitterEnabled) {
            shouldFire = combatPolicy.shouldEmitFollowUp(
                    enabled, activeGameplay, physicalDown, entityTargeted)
                    && clickLimiter.acquire(System.nanoTime(), cps, true);
        } else {
            shouldFire = combatPolicy.shouldEmitFollowUp(System.nanoTime(), cps,
                    enabled, activeGameplay, physicalDown, entityTargeted);
        }
        if (shouldFire) {
            ((MinecraftClientInvoker) client).yjhack$invokeDoAttack();
            leftCpsTracker.recordClick();
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

    // ── CPS HUD ──────────────────────────────────────────────────────

    private void renderCpsHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        String text = "L:" + leftCps + " R:" + rightCps;
        int textWidth = client.textRenderer.getWidth(text);
        int sw = context.getScaledWindowWidth();
        int sh = context.getScaledWindowHeight();
        int maxX = Math.max(4, sw - textWidth - 4);
        int maxY = Math.max(4, sh - 12);
        int x = Math.max(4, Math.min(maxX, cpsHudX));
        int y = Math.max(4, Math.min(maxY, cpsHudY));

        context.fill(x - 4, y - 2, x + textWidth + 4, y + 10, -1879048192);
        context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFF55FF55);
    }

    /** Tracks clicks and returns CPS over the last second. */
    public static final class CpsTracker {
        private final long[] timestamps = new long[120];
        private int idx;

        public void recordClick() {
            timestamps[idx % timestamps.length] = System.nanoTime();
            idx++;
        }

        public int currentCps() {
            long cutoff = System.nanoTime() - 1_000_000_000L;
            int count = 0;
            for (int i = 0; i < timestamps.length; i++) {
                if (timestamps[i] > cutoff) count++;
            }
            return count;
        }
    }
}
