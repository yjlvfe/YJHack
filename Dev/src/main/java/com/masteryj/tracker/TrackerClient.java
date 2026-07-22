package com.masteryj.tracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public final class TrackerClient implements ClientModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("tracker.json");
    private static final int CURRENT_CONFIG_VERSION = 2;
    private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
    private static final double WALL_HITBOX_EXPAND = 0.03;
    private static final int HITBOX_BUFFER_SIZE = 16384;
    private static final int MOUSE_KEY_OFFSET = 1000;

    private final VertexConsumerProvider.Immediate wallHitboxVertexConsumers =
            VertexConsumerProvider.immediate(new BufferAllocator(HITBOX_BUFFER_SIZE));

    public static Config config;
    public static boolean enabled = true;
    public static int toggleKeyCode = -1;
    public static boolean ignoreOwnTeam = true;
    public static double range = 96.0;
    public static int hudOffsetX = 0;
    public static int hudY = 8;

    private long lastConfigCheckAtMs = 0L;
    private FileTime lastKnownConfigWriteTime = null;
    private Text hiddenEnemyHudText;
    private boolean toggleKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tickTracker);
        HudRenderCallback.EVENT.register(this::renderHiddenEnemyHud);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::renderEnemyHitboxes);
    }

    // --- Main tracker tick ---

    private void tickTracker(MinecraftClient client) {
        maybeReloadConfig();
        handleToggleKey(client);

        if (!enabled || client.player == null || client.world == null) {
            hiddenEnemyHudText = null;
            return;
        }

        PlayerEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        int hiddenCount = 0;

        for (PlayerEntity player : client.world.getPlayers()) {
            if (!shouldTrackHiddenEnemy(client, player)) continue;
            hiddenCount++;
            double d = client.player.squaredDistanceTo(player);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = player;
            }
        }

        if (nearest != null) {
            hiddenEnemyHudText = createHiddenEnemyHudText(client, nearest, hiddenCount, Math.sqrt(nearestDist));
        } else {
            hiddenEnemyHudText = null;
        }
    }

    // --- Hitbox rendering (FIXED for 1.21.5) ---

    private void renderEnemyHitboxes(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || ctx.world() == null) return;

        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;

        Vec3d camera = ctx.camera().getPos();
        boolean drewAny = false;

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        VertexConsumer consumer = wallHitboxVertexConsumers.getBuffer(RenderLayer.getLines());

        for (PlayerEntity player : ctx.world().getPlayers()) {
            if (!shouldTrackHiddenEnemy(client, player)) continue;
            drewAny = true;
            Box box = player.getBoundingBox().expand(WALL_HITBOX_EXPAND);
            drawBoxOutline(matrices, consumer, box, 1.0f, 0.2f, 0.2f, 0.95f);
        }

        matrices.pop();

        if (drewAny) {
            wallHitboxVertexConsumers.draw();
        }
    }

    private final Vector3f vec = new Vector3f();

    private void drawBoxOutline(MatrixStack matrices, VertexConsumer consumer, Box box, float r, float g, float b, float a) {
        MatrixStack.Entry entry = matrices.peek();
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        vec.set(minX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, minZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(maxX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, minY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
        vec.set(minX, maxY, maxZ); consumer.vertex(entry, vec).color(r, g, b, a);
    }

    // --- HUD rendering ---

    private void renderHiddenEnemyHud(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (hiddenEnemyHudText == null || !enabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        HiddenHudLayout layout = layoutHiddenHud(hiddenEnemyHudText, client);
        int screenWidth = client.getWindow().getScaledWidth();
        int x = screenWidth / 2 + hudOffsetX + layout.x();
        int y = hudY + layout.y();

        ctx.fill(x - 2, y - 2, x + layout.width() + 2, y + 10 + 2, 0x80000000);
        ctx.drawText(client.textRenderer, hiddenEnemyHudText, x, y, 0xFFFFFFFF, true);
    }

    // --- Target tracking logic ---

    private boolean shouldTrackHiddenEnemy(MinecraftClient client, PlayerEntity player) {
        if (player == client.player) return false;
        if (!player.isAlive() || player.isSpectator()) return false;
        if (client.player == null) return false;
        double d = client.player.squaredDistanceTo(player);
        if (d > range * range) return false;
        if (ignoreOwnTeam && isSameTrackedTeam(client.player, player)) return false;
        return true;
    }

    private boolean isSameTrackedTeam(PlayerEntity a, PlayerEntity b) {
        Team teamA = a.getScoreboardTeam();
        Team teamB = b.getScoreboardTeam();
        return teamA != null && teamA == teamB;
    }

    private Text createHiddenEnemyHudText(MinecraftClient client, PlayerEntity target, int count, double dist) {
        Text name = getColoredHudName(target);
        String dir = getHiddenTargetDirection(client, target);
        String vert = getHiddenTargetVerticalDirection(client, target);
        String distStr = String.format("%.1f", dist);
        MutableText text = Text.literal("")
                .append(name)
                .append(" ")
                .append(Text.literal(distStr + "m").formatted(Formatting.WHITE))
                .append(" ")
                .append(Text.literal(dir).formatted(Formatting.GRAY))
                .append(" ")
                .append(Text.literal(vert).formatted(Formatting.DARK_GRAY));
        if (count > 1) {
            text.append(" ")
                    .append(Text.literal("+" + (count - 1)).formatted(Formatting.YELLOW));
        }
        return text;
    }

    private Text getColoredHudName(PlayerEntity player) {
        Formatting color = getHiddenHudNameFormatting(player);
        return Text.literal(player.getGameProfile().getName()).formatted(color);
    }

    private Formatting getHiddenHudNameFormatting(PlayerEntity player) {
        if (player.getHealth() <= 6.0f) return Formatting.RED;
        if (player.getHealth() <= 12.0f) return Formatting.YELLOW;
        return Formatting.GREEN;
    }

    private HiddenHudLayout layoutHiddenHud(Text text, MinecraftClient client) {
        int width = client.textRenderer.getWidth(text);
        return new HiddenHudLayout(hudOffsetX > 0 ? -170 - hudOffsetX : 2, 0, width);
    }

    private String getHiddenTargetDirection(MinecraftClient client, PlayerEntity target) {
        if (client.player == null) return "?";
        Vec3d vec = target.getPos().subtract(client.player.getPos()).normalize();
        float angle = (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
        angle = (angle + 360) % 360;
        int index = (int) Math.round(angle / 45.0) % 8;
        return new String[]{"S", "SW", "W", "NW", "N", "NE", "E", "SE"}[index];
    }

    private String getHiddenTargetVerticalDirection(MinecraftClient client, PlayerEntity target) {
        if (client.player == null) return "";
        double dy = target.getY() - client.player.getY();
        if (dy > 2.0) return "above";
        if (dy < -2.0) return "below";
        return "same";
    }

    // --- Toggle ---

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
            sendToggleMessage(client, enabled, "Tracker");
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

    private void maybeReloadConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheckAtMs < CONFIG_RELOAD_INTERVAL_MS) return;
        lastConfigCheckAtMs = now;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            FileTime wt = Files.getLastModifiedTime(CONFIG_PATH);
            if (lastKnownConfigWriteTime != null && wt.equals(lastKnownConfigWriteTime)) return;
            config = loadConfig();
            applyRuntimeConfig(config);
        } catch (IOException ignored) {
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
        } catch (Exception ignored) {
        }
        Config cfg = new Config();
        cfg.normalize();
        saveConfig(cfg);
        return cfg;
    }

    private void saveConfig(Config cfg) {
        cfg.normalize();
        applyRuntimeConfig(cfg);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
        } catch (IOException ignored) {
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
        if (key >= MOUSE_KEY_OFFSET) return key;
        return key > 0 ? key : -1;
    }

    private static boolean isToggleBindingPressed(MinecraftClient client, int key) {
        if (client.currentScreen != null || !client.isWindowFocused()) return false;
        long handle = client.getWindow().getHandle();
        if (key >= MOUSE_KEY_OFFSET) {
            return GLFW.glfwGetMouseButton(handle, key - MOUSE_KEY_OFFSET) == 1;
        }
        return GLFW.glfwGetKey(handle, key) == 1;
    }

    // --- Inner classes ---

    public static class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = true;
        public int toggleKeyCode = -1;
        public boolean ignoreOwnTeam = true;
        public double range = 96.0;
        public int hudOffsetX = 0;
        public int hudY = 8;

        public void normalize() {
            if (configVersion < CURRENT_CONFIG_VERSION) {
                if (configVersion < 2) {
                    // migration from v1: use defaults for new fields
                    ignoreOwnTeam = true;
                    range = 96.0;
                    hudOffsetX = 0;
                    hudY = 8;
                }
                configVersion = CURRENT_CONFIG_VERSION;
            }
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }
    }

    private record HiddenHudLayout(int x, int y, int width) {
    }
}
