package com.masteryj.ninjabridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.masteryj.config.RecommendedSettings;
import com.masteryj.core.DebugStats;
import com.masteryj.core.PhysicalKeyBinding;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.IdentityHashMap;
import java.util.Map;

public final class NinjaBridgeClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-NinjaBridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ninjabridge.json");
    private static final int CURRENT_CONFIG_VERSION = 10;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;
    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;
    private static final int MOUSE_OFF = 1000;

    private final Map<Block, Boolean> blockCache = new IdentityHashMap<>();

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    public static boolean active;
    public static boolean autoSwitch = RecommendedSettings.NINJA_AUTO_SWITCH;
    public static int switchDelayMs = RecommendedSettings.NINJA_SWITCH_DELAY_MS;

    private World lastWorld;
    private long lastConfigCheckNanos = Long.MIN_VALUE;
    private FileTime lastKnownWriteTime;
    private boolean toggleWasDown;
    private boolean requireToggleRelease;
    private boolean previousAlive = true;
    private int lastSlot = -1;
    private boolean syntheticSneak;
    private long lastSwitchAtNanos = Long.MIN_VALUE;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("NinjaBridge", this::tick));
    }

    private void tick(MinecraftClient client) {
        maybeReload();

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            clearWorldState(client);
        }

        if (client == null || client.player == null || client.world == null) {
            clearWorldState(client);
            return;
        }

        int key = normalizeKey(toggleKeyCode);
        boolean rawDown = key != GLFW.GLFW_KEY_UNKNOWN && isRawPressed(client, key);
        boolean gated = client.currentScreen != null || !client.isWindowFocused()
                || !client.mouse.isCursorLocked();

        if (!enabled) {
            active = false;
            if (rawDown) requireToggleRelease = true;
            toggleWasDown = rawDown;
            releaseSyntheticSneak(client);
            return;
        }

        boolean alive = client.player.isAlive() && client.player.getHealth() > 0.0F;
        if (previousAlive && !alive) {
            active = false;
            releaseSyntheticSneak(client);
            sendMessage(client, false, false);
        }
        previousAlive = alive;
        if (!alive || client.player.isSpectator()) return;

        if (gated) {
            if (rawDown) requireToggleRelease = true;
            toggleWasDown = rawDown;
            releaseSyntheticSneak(client);
            return;
        }

        if (requireToggleRelease) {
            if (!rawDown) {
                requireToggleRelease = false;
                toggleWasDown = false;
            }
            releaseSyntheticSneak(client);
            return;
        }

        if (rawDown && !toggleWasDown) {
            active = !active;
            sendMessage(client, active, true);
            if (!active) releaseSyntheticSneak(client);
        }
        toggleWasDown = rawDown;

        if (!active) {
            releaseSyntheticSneak(client);
            return;
        }

        BlockPos below = BlockPos.ofFloored(
                client.player.getX(), client.player.getY() - 1.0D, client.player.getZ());
        boolean desiredSneak = client.world.getBlockState(below).isAir();
        boolean onGround = client.player.isOnGround();

        if (shouldAutoSwitch(autoSwitch, active, desiredSneak, onGround)) {
            doAutoSwitch(client, System.nanoTime());
        }

        if (sneakShouldChange(desiredSneak, syntheticSneak, onGround)) {
            setSyntheticSneak(client, desiredSneak);
        }
    }

    private void clearWorldState(MinecraftClient client) {
        releaseSyntheticSneak(client);
        active = false;
        toggleWasDown = false;
        requireToggleRelease = false;
        previousAlive = true;
        lastSlot = -1;
        lastSwitchAtNanos = Long.MIN_VALUE;
        blockCache.clear();
    }

    /** Apply synthetic sneak without ever cancelling a real physical sneak hold. */
    private void setSyntheticSneak(MinecraftClient client, boolean sneak) {
        syntheticSneak = sneak;
        if (client == null || client.options == null) return;
        boolean physicalSneak = PhysicalKeyBinding.isPressed(client, client.options.sneakKey);
        client.options.sneakKey.setPressed(physicalSneak || syntheticSneak);
        DebugStats.onSneakTransition();
    }

    static boolean sneakShouldChange(boolean desiredSneak, boolean lastSneak, boolean onGround) {
        return desiredSneak != lastSneak && onGround;
    }

    static boolean shouldAutoSwitch(boolean configured, boolean active,
                                    boolean desiredSneak, boolean onGround) {
        return configured && active && desiredSneak && onGround;
    }

    static boolean needsSlotSwitch(int targetSlot, int currentSlot) {
        return targetSlot != currentSlot;
    }

    static long switchDelayNanos(int configuredMs) {
        int normalized = Math.max(50, Math.min(500, configuredMs));
        return normalized * 1_000_000L;
    }

    private void doAutoSwitch(MinecraftClient client, long now) {
        ItemStack held = client.player.getMainHandStack();
        if (isValidBlock(held)) {
            lastSlot = client.player.getInventory().getSelectedSlot();
            return;
        }

        if (lastSlot >= 0 && lastSlot < 9) {
            ItemStack remembered = client.player.getInventory().getStack(lastSlot);
            if (isValidBlock(remembered)) {
                switchTo(client, lastSlot, now);
                return;
            }
        }

        for (int slot = 0; slot < 9; slot++) {
            if (isValidBlock(client.player.getInventory().getStack(slot))) {
                switchTo(client, slot, now);
                lastSlot = slot;
                return;
            }
        }
        lastSlot = -1;
    }

    private void switchTo(MinecraftClient client, int slot, long now) {
        int selected = client.player.getInventory().getSelectedSlot();
        if (!needsSlotSwitch(slot, selected)) return;
        if (lastSwitchAtNanos != Long.MIN_VALUE
                && now - lastSwitchAtNanos < switchDelayNanos(switchDelayMs)) return;
        client.player.getInventory().setSelectedSlot(slot);
        lastSwitchAtNanos = now;
        DebugStats.onSlotChange();
    }

    private boolean isValidBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        return blockCache.computeIfAbsent(block, b -> !isBadBlock(b));
    }

    private boolean isBadBlock(Block block) {
        return block instanceof SlabBlock || block instanceof StairsBlock
                || block instanceof FenceBlock || block instanceof FenceGateBlock
                || block instanceof ChestBlock || block instanceof EnderChestBlock
                || block instanceof EnchantingTableBlock || block instanceof BrewingStandBlock
                || block instanceof BedBlock || block instanceof DispenserBlock
                || block instanceof HopperBlock || block instanceof AnvilBlock
                || block instanceof TntBlock || block instanceof CobwebBlock
                || block instanceof LeverBlock || block instanceof ButtonBlock
                || block instanceof AbstractSkullBlock || block instanceof FluidBlock
                || block instanceof CactusBlock || block instanceof CarpetBlock
                || block instanceof TripwireBlock || block instanceof TripwireHookBlock
                || block instanceof TallPlantBlock || block instanceof FlowerPotBlock
                || block instanceof SignBlock || block instanceof LadderBlock
                || block instanceof TorchBlock || block instanceof PaneBlock
                || block instanceof SandBlock || block instanceof SoulSandBlock
                || block instanceof SaplingBlock || block instanceof WallBlock
                || block instanceof RailBlock || block instanceof CraftingTableBlock
                || block instanceof BeaconBlock || block instanceof DaylightDetectorBlock
                || block instanceof NoteBlock || block instanceof MushroomPlantBlock
                || block instanceof LilyPadBlock || block instanceof VineBlock
                || block instanceof TrapdoorBlock || block instanceof PlantBlock
                || block instanceof LeavesBlock || block instanceof DoorBlock
                || block instanceof PressurePlateBlock || block instanceof ShulkerBoxBlock;
    }

    private void releaseSyntheticSneak(MinecraftClient client) {
        if (!syntheticSneak) return;
        setSyntheticSneak(client, false);
    }

    private void sendMessage(MinecraftClient client, boolean on, boolean toggle) {
        if (client == null || client.player == null) return;
        String message = toggle
                ? "Ninja Bridge " + (on ? "enabled" : "disabled")
                : "Ninja Bridge disabled after death";
        Text text = Text.literal(message).formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(text, true);
    }

    private void maybeReload() {
        long now = System.nanoTime();
        if (lastConfigCheckNanos != Long.MIN_VALUE
                && now - lastConfigCheckNanos < CONFIG_RELOAD_INTERVAL_NANOS) return;
        lastConfigCheckNanos = now;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            FileTime wt = Files.getLastModifiedTime(CONFIG_PATH);
            if (lastKnownWriteTime != null && wt.equals(lastKnownWriteTime)) return;
            config = loadConfig();
            applyRuntimeConfig(config);
        } catch (IOException e) {
            LOGGER.error("Failed to reload config", e);
        }
    }

    private Config loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                boolean autoSwitchPresent = root.has("autoSwitch");
                boolean switchDelayPresent = root.has("switchDelayMs");
                Config loaded = GSON.fromJson(root, Config.class);
                if (loaded != null) {
                    loaded.normalize(autoSwitchPresent, switchDelayPresent);
                    lastKnownWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
                    saveConfig(loaded);
                    return loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
        }
        Config fresh = recommendedDefaults();
        saveConfig(fresh);
        return fresh;
    }

    public void saveConfig(Config cfg) {
        saveConfigStatic(cfg);
        try {
            if (Files.exists(CONFIG_PATH)) lastKnownWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to read config write time", e);
        }
    }

    public static void saveConfigStatic(Config cfg) {
        if (cfg == null) return;
        cfg.normalize(true, true);
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
        toggleKeyCode = cfg.toggleKeyCode;
        autoSwitch = cfg.autoSwitch;
        switchDelayMs = cfg.switchDelayMs;
    }

    public static Config recommendedDefaults() {
        return Config.recommendedDefaults();
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public int toggleKeyCode = DEFAULT_KEY;
        public boolean autoSwitch = RecommendedSettings.NINJA_AUTO_SWITCH;
        public int switchDelayMs = RecommendedSettings.NINJA_SWITCH_DELAY_MS;

        public static Config recommendedDefaults() {
            Config cfg = new Config();
            cfg.configVersion = CURRENT_CONFIG_VERSION;
            cfg.enabled = false;
            cfg.toggleKeyCode = DEFAULT_KEY;
            cfg.autoSwitch = RecommendedSettings.NINJA_AUTO_SWITCH;
            cfg.switchDelayMs = RecommendedSettings.NINJA_SWITCH_DELAY_MS;
            cfg.normalize(true, true);
            return cfg;
        }

        public Config copy() {
            Config result = new Config();
            result.configVersion = configVersion;
            result.enabled = enabled;
            result.toggleKeyCode = toggleKeyCode;
            result.autoSwitch = autoSwitch;
            result.switchDelayMs = switchDelayMs;
            result.normalize(true, true);
            return result;
        }

        public void norm() {
            normalize(true, true);
        }

        void normalize(boolean autoSwitchPresent, boolean switchDelayPresent) {
            if (configVersion < 6) toggleKeyCode = DEFAULT_KEY;
            if (!autoSwitchPresent) autoSwitch = RecommendedSettings.NINJA_AUTO_SWITCH;
            if (!switchDelayPresent) switchDelayMs = RecommendedSettings.NINJA_SWITCH_DELAY_MS;
            configVersion = CURRENT_CONFIG_VERSION;
            toggleKeyCode = normalizeKey(toggleKeyCode);
            switchDelayMs = Math.max(50, Math.min(500, switchDelayMs));
        }
    }

    private static int normalizeKey(int key) {
        if (key >= MOUSE_OFF) return key;
        return key <= 0 ? GLFW.GLFW_KEY_UNKNOWN : key;
    }

    private static boolean isRawPressed(MinecraftClient client, int key) {
        if (client == null || client.getWindow() == null) return false;
        long handle = client.getWindow().getHandle();
        if (key >= MOUSE_OFF) {
            return GLFW.glfwGetMouseButton(handle, key - MOUSE_OFF) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }
}
