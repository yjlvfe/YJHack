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
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
    private static final int CURRENT_CONFIG_VERSION = 5;
    private static final int MAX_SAFE_CPS = ClickScheduler.MAX_CPS;
    private static final int DEFAULT_MIN_CPS = 30;
    private static final int DEFAULT_MAX_CPS = 40;
    private static final int LEGACY_DEFAULT_MIN_CPS = 8;
    private static final int LEGACY_DEFAULT_MAX_CPS = 16;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;
    private static final InputUtil.Key LEFT_MOUSE = InputUtil.Type.MOUSE.createFromCode(0);

    private final Random random = new Random();
    private final ClickScheduler scheduler = new ClickScheduler();

    public static Config config;
    public static boolean enabled = true;
    public static boolean weaponCheck = false;
    public static int toggleKeyCode = -1;

    private boolean physicalWasDown;
    private boolean requireRelease;
    private boolean toggleKeyWasDown;
    private long lastConfigCheckAtNanos = Long.MIN_VALUE;
    private FileTime lastKnownConfigWriteTime;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("AutoLeft", this::tickLeftAutoClick));
    }

    private void tickLeftAutoClick(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        boolean physicalDown = isMouseDown(client, 0);
        boolean rising = physicalDown && !physicalWasDown;

        if (DebugStats.ENABLED) {
            if (rising) DebugStats.onAutoLeftPhysicalPress();
            DebugStats.setAutoLeftConfiguredCps(config == null ? 0 : config.minCps,
                    config == null ? 0 : config.maxCps);
        }

        if (!enabled || !isInActiveGameplay(client)) {
            if (physicalDown) requireRelease = true;
            resetAutomation();
            physicalWasDown = physicalDown;
            return;
        }

        // A button held through a menu, focus loss, death, disable, or world transition must be
        // released before automation can start again.
        if (requireRelease) {
            resetAutomation();
            if (!physicalDown) {
                requireRelease = false;
                physicalWasDown = false;
            } else {
                physicalWasDown = true;
            }
            return;
        }

        if (weaponCheck && !isHoldingAllowedWeapon(client)) {
            if (physicalDown) requireRelease = true;
            resetAutomation();
            physicalWasDown = physicalDown;
            return;
        }

        if (!physicalDown) {
            physicalWasDown = false;
            resetAutomation();
            return;
        }

        physicalWasDown = true;

        // Vanilla already handled the real rising-edge click. Never add a duplicate synthetic
        // pulse in that same tick.
        if (rising) {
            resetAutomation();
            return;
        }

        // Mining remains completely vanilla; no synthetic attack pulses while targeting blocks.
        if (isLookingAtBlock(client)) {
            resetAutomation();
            return;
        }

        int pulses = scheduler.pulsesThisTick(pickCps());
        if (pulses <= 0) return;

        ActionBudget.INSTANCE.request(ActionBudget.Module.LEFT, pulses,
                () -> mayEmit(client),
                () -> {
                    KeyBinding.onKeyPressed(LEFT_MOUSE);
                    DebugStats.onAutoLeftPulse();
                });
    }

    private boolean mayEmit(MinecraftClient client) {
        return enabled
                && isInActiveGameplay(client)
                && isMouseDown(client, 0)
                && !isLookingAtBlock(client)
                && (!weaponCheck || isHoldingAllowedWeapon(client));
    }

    private void resetAutomation() {
        scheduler.clear();
        ActionBudget.INSTANCE.cancel(ActionBudget.Module.LEFT);
    }

    private int pickCps() {
        int a = config == null ? DEFAULT_MIN_CPS : config.minCps;
        int b = config == null ? DEFAULT_MAX_CPS : config.maxCps;
        int min = Math.max(1, Math.min(MAX_SAFE_CPS, Math.min(a, b)));
        int max = Math.max(min, Math.min(MAX_SAFE_CPS, Math.max(a, b)));
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    private boolean isLookingAtBlock(MinecraftClient client) {
        return client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK;
    }

    private boolean isHoldingAllowedWeapon(MinecraftClient client) {
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandStack();
        return isSwordOrAxe(held);
    }

    private boolean isMouseDown(MinecraftClient client, int button) {
        return client != null && client.getWindow() != null
                && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
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
            if (!enabled) {
                requireRelease = isMouseDown(client, 0);
                resetAutomation();
            }
            sendToggleMessage(client, enabled, "AutoLeft");
        }
        toggleKeyWasDown = pressed;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on, String moduleName) {
        if (client.player == null) return;
        String status = on ? "enabled" : "disabled";
        MutableText text = Text.literal(moduleName + " " + status)
                .formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(text, true);
    }

    public static class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = true;
        public boolean weaponCheck = false;
        public int toggleKeyCode = -1;
        public int minCps = DEFAULT_MIN_CPS;
        public int maxCps = DEFAULT_MAX_CPS;

        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                if (minCps == LEGACY_DEFAULT_MIN_CPS && maxCps == LEGACY_DEFAULT_MAX_CPS) {
                    minCps = DEFAULT_MIN_CPS;
                    maxCps = DEFAULT_MAX_CPS;
                }
                configVersion = CURRENT_CONFIG_VERSION;
            }
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
        weaponCheck = cfg.weaponCheck;
        toggleKeyCode = cfg.toggleKeyCode;
    }

    private static int normalizeToggleKeyCode(int key) {
        if (key >= 1000) return key;
        return key > 0 ? key : -1;
    }

    private static boolean isToggleBindingPressed(MinecraftClient client, int key) {
        if (client.currentScreen != null || !client.isWindowFocused()) return false;
        long handle = client.getWindow().getHandle();
        if (key >= 1000) return GLFW.glfwGetMouseButton(handle, key - 1000) == GLFW.GLFW_PRESS;
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }
}
