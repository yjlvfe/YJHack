package com.masteryj.ninjabridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.DebugStats;
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
    private static final int CURRENT_CONFIG_VERSION = 7;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;
    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;
    private static final int MOUSE_OFF = 1000;
    /** Minimum gap between auto-switch hotbar changes — debounces any boundary oscillation.
     *  Monotonic ({@link System#nanoTime()}) so a wall-clock change can't defeat it. */
    private static final long SWITCH_COOLDOWN_NANOS = 120_000_000L;

    private final Map<Block, Boolean> blockCache = new IdentityHashMap<>();

    public static Config config;
    public static boolean enabled = true;
    public static int toggleKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    public static boolean active = false;
    public static boolean autoSwitch = true;

    private long lastCfgNanos = Long.MIN_VALUE;
    private FileTime lastWt;
    private boolean wasDown = false;
    private boolean prevAlive = true;
    private int lastSlot = -1;
    private boolean wasSneaking = false;
    private long lastSwitchAtNanos = Long.MIN_VALUE;

    /** Drive the vanilla sneak key directly; setPressed is sufficient on 1.21.5.
     *  Only ever called on a genuine state change, so each call is one transition. */
    private static void setSneakState(MinecraftClient c, boolean sneak) {
        c.options.sneakKey.setPressed(sneak);
        DebugStats.onSneakTransition();
    }

    /** True only when the desired sneak state differs and the player is grounded. */
    static boolean sneakShouldChange(boolean desiredSneak, boolean lastSneak, boolean onGround) {
        return desiredSneak != lastSneak && onGround;
    }

    /** True only when the target hotbar slot is not already selected. */
    static boolean needsSlotSwitch(int targetSlot, int currentSlot) {
        return targetSlot != currentSlot;
    }

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("NinjaBridge", this::tick));
    }

    private void tick(MinecraftClient c) {
        maybeReload();

        if (c.player == null || c.world == null) {
            prevAlive = true; unsneak(c); active = false; return;
        }
        if (!enabled) { unsneak(c); active = false; return; }

        // Pause while a screen is open or the window is unfocused — matches the gating
        // the other modules use. Keeps the toggle (active) intact so bridging resumes
        // on return, but never leaves the sneak key stuck down in a menu.
        if (c.currentScreen != null || !c.isWindowFocused()) {
            unsneak(c); wasDown = false; return;
        }

        boolean alive = c.player.isAlive() && c.player.getHealth() > 0.0F;
        if (prevAlive && !alive) { active = false; unsneak(c); msg(c, false, false); }
        prevAlive = alive;

        int k = normKey(toggleKeyCode);
        if (k == GLFW.GLFW_KEY_UNKNOWN) { unsneak(c); return; }
        boolean down = isPressed(c, k);

        if (down && !wasDown) {
            wasDown = true;
            active = !active;
            msg(c, active, true);
            if (!active) { unsneak(c); }
            return;
        }
        if (!down) {
            wasDown = false;
        }

        if (!active) {
            unsneak(c); return;
        }

        long now = System.nanoTime();
        if (autoSwitch) { doAutoSwitch(c, now); }

        BlockPos below = BlockPos.ofFloored(c.player.getX(), c.player.getY() - 1.0D, c.player.getZ());
        boolean isAir = c.world.getBlockState(below).isAir();

        // setSneakState only fires on a genuine state change (never re-sends the same state).
        if (sneakShouldChange(isAir, wasSneaking, c.player.isOnGround())) {
            setSneakState(c, isAir);
            wasSneaking = isAir;
        }
    }

    private void doAutoSwitch(MinecraftClient c, long now) {
        ItemStack held = c.player.getMainHandStack();
        if (isValidBlock(held)) { lastSlot = c.player.getInventory().getSelectedSlot(); return; }

        if (lastSlot >= 0 && lastSlot < 9) {
            ItemStack stack = c.player.getInventory().getStack(lastSlot);
            if (isValidBlock(stack)) {
                switchTo(c, lastSlot, now);
                return;
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = c.player.getInventory().getStack(i);
            if (isValidBlock(stack)) {
                switchTo(c, i, now);
                lastSlot = i; return;
            }
        }
        lastSlot = -1;
    }

    /** Switch the held hotbar slot only when it differs and the switch cooldown has elapsed,
     *  so a boundary case can never emit a burst of slot-change packets. */
    private void switchTo(MinecraftClient c, int slot, long now) {
        if (!needsSlotSwitch(slot, c.player.getInventory().getSelectedSlot())) { return; }
        if (lastSwitchAtNanos != Long.MIN_VALUE && now - lastSwitchAtNanos < SWITCH_COOLDOWN_NANOS) { return; }
        c.player.getInventory().setSelectedSlot(slot);
        lastSwitchAtNanos = now;
        DebugStats.onSlotChange();
    }

    private boolean isValidBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        Block block = bi.getBlock();
        Boolean cached = blockCache.get(block);
        if (cached != null) return cached;
        boolean valid = !isBadBlock(block);
        blockCache.put(block, valid);
        return valid;
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
                || block instanceof TorchBlock
                || block instanceof PaneBlock
                || block instanceof SandBlock || block instanceof SoulSandBlock
                || block instanceof SaplingBlock || block instanceof WallBlock
                || block instanceof RailBlock || block instanceof CraftingTableBlock
                || block instanceof BeaconBlock || block instanceof DaylightDetectorBlock
                || block instanceof NoteBlock || block instanceof MushroomPlantBlock
                || block instanceof LilyPadBlock
                || block instanceof VineBlock || block instanceof TrapdoorBlock
                || block instanceof PlantBlock || block instanceof LeavesBlock
                || block instanceof DoorBlock || block instanceof PressurePlateBlock
                || block instanceof ShulkerBoxBlock;
    }

    private void unsneak(MinecraftClient c) {
        if (wasSneaking) { setSneakState(c, false); wasSneaking = false; }
    }

    private void msg(MinecraftClient c, boolean on, boolean toggle) {
        if (c.player == null) return;
        String m = toggle ? "Ninja Bridge " + (on ? "enabled" : "disabled") : "Ninja Bridge disabled after death";
        Text t = Text.literal(m).formatted(on ? Formatting.GREEN : Formatting.RED);
        c.player.sendMessage(t, false);
        c.player.sendMessage(t, true);
    }

    private void maybeReload() {
        long now = System.nanoTime();
        if (lastCfgNanos != Long.MIN_VALUE && now - lastCfgNanos < CONFIG_RELOAD_INTERVAL_NANOS) return;
        lastCfgNanos = now;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            FileTime wt = Files.getLastModifiedTime(CONFIG_PATH);
            if (lastWt != null && wt.equals(lastWt)) return;
            config = loadConfig(); applyRuntimeConfig(config);
        } catch (IOException e) {
            LOGGER.error("Failed to reload config: {}", e.getMessage());
        }
    }

    private Config loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config l = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (l != null) { l.norm(); lastWt = Files.getLastModifiedTime(CONFIG_PATH); return l; }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config: {}", e.getMessage());
        }
        Config f = new Config(); f.norm(); saveConfig(f); return f;
    }

    public void saveConfig(Config cfg) {
        try {
            cfg.norm();
            applyRuntimeConfig(cfg);
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            lastWt = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static void saveConfigStatic(Config cfg) {
        try {
            cfg.norm(); applyRuntimeConfig(cfg);
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg; enabled = cfg.enabled; toggleKeyCode = cfg.toggleKeyCode;
        autoSwitch = cfg.autoSwitch;
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = true;
        public int toggleKeyCode = GLFW.GLFW_KEY_UNKNOWN;
        public boolean autoSwitch = true;

        public void norm() {
            if (configVersion < 7) {
                if (configVersion < 6) { toggleKeyCode = DEFAULT_KEY; }
                autoSwitch = true; configVersion = CURRENT_CONFIG_VERSION;
            }
            toggleKeyCode = normKey(toggleKeyCode);
        }
    }

    private static int normKey(int k) { if (k >= MOUSE_OFF) return k; return k <= 0 ? GLFW.GLFW_KEY_UNKNOWN : k; }

    private static boolean isPressed(MinecraftClient c, int k) {
        if (c.currentScreen != null || !c.isWindowFocused()) return false;
        if (k >= MOUSE_OFF) return GLFW.glfwGetMouseButton(c.getWindow().getHandle(), k - MOUSE_OFF) == GLFW.GLFW_PRESS;
        return GLFW.glfwGetKey(c.getWindow().getHandle(), k) == GLFW.GLFW_PRESS;
    }
}
