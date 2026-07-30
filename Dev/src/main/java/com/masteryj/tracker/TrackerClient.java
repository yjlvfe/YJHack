package com.masteryj.tracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.DebugStats;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.VertexRendering;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

public final class TrackerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Tracker");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("tracker.json");
    private static final int CURRENT_CONFIG_VERSION = 6;
    private static final long CONFIG_RELOAD_INTERVAL_NANOS = 5_000_000_000L;

    private final VertexConsumerProvider.Immediate wallHitboxConsumers =
            VertexConsumerProvider.immediate(new BufferAllocator(16384));
    private final List<PlayerEntity> trackedSnapshot = new ArrayList<>();

    public static Config config;
    public static boolean enabled = true;
    public static int toggleKeyCode = -1;
    public static boolean ignoreOwnTeam = true;
    public static double range = 96.0D;
    public static int hudOffsetX;
    public static int hudY = 8;

    private long lastConfigCheckAtNanos = Long.MIN_VALUE;
    private FileTime lastKnownConfigWriteTime;
    private Text hiddenEnemyHudText;
    private boolean toggleKeyWasDown;
    private boolean requireToggleRelease;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(DebugStats.timed("Tracker", this::tickTracker));
        HudRenderCallback.EVENT.register(this::renderHiddenEnemyHud);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::renderEnemyHitboxes);
    }

    private void tickTracker(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);
        trackedSnapshot.clear();
        hiddenEnemyHudText = null;

        if (!enabled || client.player == null || client.world == null
                || !client.player.isAlive() || client.player.isSpectator()) return;

        PlayerEntity closest = null;
        double closestDistanceSquared = Double.MAX_VALUE;
        int count = 0;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (!shouldTrackHiddenEnemy(client, candidate)) continue;
            trackedSnapshot.add(candidate);
            count++;
            double distanceSquared = client.player.squaredDistanceTo(candidate);
            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared;
                closest = candidate;
            }
        }
        if (closest != null) {
            hiddenEnemyHudText = createHiddenEnemyHudText(
                    client, closest, count, Math.sqrt(closestDistanceSquared));
        }
    }

    private void renderEnemyHitboxes(WorldRenderContext context) {
        if (!enabled || trackedSnapshot.isEmpty() || context.world() == null) return;
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) return;

        Vec3d camera = context.camera().getPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer lines = wallHitboxConsumers.getBuffer(RenderLayer.getLines());
        for (PlayerEntity candidate : trackedSnapshot) {
            if (candidate.isAlive() && candidate.getWorld() == context.world()) {
                VertexRendering.drawBox(matrices, lines,
                        candidate.getBoundingBox().expand(0.03D),
                        1.0F, 0.2F, 0.2F, 0.95F);
            }
        }
        matrices.pop();
        wallHitboxConsumers.draw();
    }

    private void renderHiddenEnemyHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || client.options.hudHidden
                || hiddenEnemyHudText == null) return;

        HiddenHudLayout layout = layoutHiddenHud(
                context.getScaledWindowWidth(), context.getScaledWindowHeight(),
                hiddenEnemyHudText, client);
        context.fill(layout.x() - 4, layout.y() - 2,
                layout.x() + layout.width() + 4, layout.y() + 10, -1879048192);
        context.drawTextWithShadow(client.textRenderer, hiddenEnemyHudText,
                layout.x(), layout.y(), -1);
    }

    private HiddenHudLayout layoutHiddenHud(int scaledWidth, int scaledHeight,
                                            Text text, MinecraftClient client) {
        int textWidth = client.textRenderer.getWidth(text);
        int maxX = Math.max(4, scaledWidth - textWidth - 4);
        int maxY = Math.max(4, scaledHeight - 12);
        int x = MathHelperClamp.clamp(hudOffsetX, 4, maxX);
        int y = MathHelperClamp.clamp(hudY, 4, maxY);
        return new HiddenHudLayout(x, y, textWidth);
    }

    private Text createHiddenEnemyHudText(MinecraftClient client, PlayerEntity candidate,
                                          int count, double distance) {
        double rounded = Math.round(distance * 10.0D) / 10.0D;
        MutableText text = Text.literal("Alert ");
        text.append(getColoredHudName(candidate));
        text.append(Text.literal(" " + rounded + "m " + getDirection(client, candidate)
                + (count > 1 ? " +" + (count - 1) : "")));
        return text;
    }

    private Text getColoredHudName(PlayerEntity candidate) {
        AbstractTeam team = candidate.getScoreboardTeam();
        if (team != null) {
            return team.decorateName(Text.literal(candidate.getGameProfile().getName())).copy();
        }
        return Text.literal(candidate.getGameProfile().getName()).formatted(Formatting.WHITE);
    }

    private String getDirection(MinecraftClient client, PlayerEntity candidate) {
        Vec3d delta = candidate.getPos().subtract(client.player.getPos());
        double yaw = Math.toRadians(client.player.getYaw());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double forward = delta.x * forwardX + delta.z * forwardZ;
        double right = delta.x * rightX + delta.z * rightZ;
        double angle = Math.toDegrees(Math.atan2(right, forward));

        String horizontal;
        if (angle >= -22.5D && angle < 22.5D) horizontal = "FRONT";
        else if (angle >= 22.5D && angle < 67.5D) horizontal = "FRONT-RIGHT";
        else if (angle >= 67.5D && angle < 112.5D) horizontal = "RIGHT";
        else if (angle >= 112.5D && angle < 157.5D) horizontal = "BACK-RIGHT";
        else if (angle <= -22.5D && angle > -67.5D) horizontal = "FRONT-LEFT";
        else if (angle <= -67.5D && angle > -112.5D) horizontal = "LEFT";
        else if (angle <= -112.5D && angle > -157.5D) horizontal = "BACK-LEFT";
        else horizontal = "BACK";

        double deltaY = candidate.getEyeY() - client.player.getEyeY();
        if (deltaY >= 1.25D) return horizontal + " UP";
        if (deltaY <= -1.25D) return horizontal + " DOWN";
        return horizontal;
    }

    private boolean shouldTrackHiddenEnemy(MinecraftClient client, PlayerEntity candidate) {
        if (candidate == null || candidate == client.player || !candidate.isAlive()
                || candidate.isSpectator()) return false;
        if (client.player.squaredDistanceTo(candidate) > range * range) return false;
        return !ignoreOwnTeam || !isSameTrackedTeam(client.player, candidate);
    }

    private boolean isSameTrackedTeam(PlayerEntity player, PlayerEntity candidate) {
        if (player.isTeammate(candidate) || candidate.isTeammate(player)) return true;
        AbstractTeam a = player.getScoreboardTeam();
        AbstractTeam b = candidate.getScoreboardTeam();
        if (a == null || b == null) return false;
        if (a == b) return true;
        String an = a.getName();
        String bn = b.getName();
        return an != null && bn != null && !an.isBlank() && an.equalsIgnoreCase(bn);
    }

    private void handleToggleKey(MinecraftClient client) {
        int key = normalizeToggleKeyCode(toggleKeyCode);
        boolean rawDown = key != -1 && isRawBindingPressed(client, key);
        if (client.currentScreen != null || !client.isWindowFocused()) {
            if (rawDown) requireToggleRelease = true;
            toggleKeyWasDown = rawDown;
            return;
        }
        if (requireToggleRelease) {
            if (!rawDown) {
                requireToggleRelease = false;
                toggleKeyWasDown = false;
            }
            return;
        }
        if (rawDown && !toggleKeyWasDown) {
            enabled = !enabled;
            config.enabled = enabled;
            saveConfig(config);
            sendToggleMessage(client, enabled);
        }
        toggleKeyWasDown = rawDown;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on) {
        if (client.player == null) return;
        Text text = Text.literal("Tracker " + (on ? "enabled" : "disabled"))
                .formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(text, true);
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
            LOGGER.warn("Tracker config reload failed", e);
        }
    }

    private Config loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (loaded != null) {
                    loaded.normalize();
                    lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
                    return loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Tracker config unreadable; using safe defaults", e);
        }
        Config fresh = new Config();
        fresh.normalize();
        saveConfig(fresh);
        return fresh;
    }

    private void saveConfig(Config cfg) {
        if (cfg == null) return;
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("Tracker config save failed", e);
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
            LOGGER.warn("Tracker config save failed", e);
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        toggleKeyCode = cfg.toggleKeyCode;
        ignoreOwnTeam = cfg.ignoreOwnTeam;
        range = cfg.range;
        hudOffsetX = cfg.hudOffsetX;
        hudY = cfg.hudY;
    }

    private static int normalizeToggleKeyCode(int key) {
        if (key >= 1000) return key;
        return key > 0 ? key : -1;
    }

    private static boolean isRawBindingPressed(MinecraftClient client, int key) {
        if (client == null || client.getWindow() == null) return false;
        long handle = client.getWindow().getHandle();
        if (key >= 1000) return GLFW.glfwGetMouseButton(handle, key - 1000) == GLFW.GLFW_PRESS;
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = true;
        public int toggleKeyCode = -1;
        public boolean ignoreOwnTeam = true;
        public double range = 96.0D;
        public int hudOffsetX;
        public int hudY = 8;

        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) configVersion = CURRENT_CONFIG_VERSION;
            if (Double.isNaN(range) || Double.isInfinite(range)) range = 96.0D;
            range = Math.max(1.0D, Math.min(256.0D, range));
            hudOffsetX = Math.max(-10000, Math.min(10000, hudOffsetX));
            hudY = Math.max(-10000, Math.min(10000, hudY));
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }
    }

    private record HiddenHudLayout(int x, int y, int width) {
    }

    /** Small local integer clamp to avoid pulling client math into config tests. */
    private static final class MathHelperClamp {
        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
