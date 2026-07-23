package com.masteryj.tracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrackerClient implements ClientModInitializer {
   private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-Tracker");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("tracker.json");
   private static final int CURRENT_CONFIG_VERSION = 6;
   private static final long CONFIG_RELOAD_INTERVAL_MS = 5000L;
   private static final double WALL_HITBOX_EXPAND = 0.03;
   private static final int HITBOX_BUFFER_SIZE = 16384;
   private static final int MOUSE_KEY_OFFSET = 1000;
   private final VertexConsumerProvider.Immediate wallHitboxVertexConsumers = VertexConsumerProvider.immediate(new BufferAllocator(16384));
   public static TrackerClient.Config config;
   public static boolean enabled = true;
   public static int toggleKeyCode = -1;
   public static boolean ignoreOwnTeam = true;
   public static double range = 96.0;
   public static int hudOffsetX = 0;
   public static int hudY = 8;
   private long lastConfigCheckAtMs = 0L;
   private FileTime lastKnownConfigWriteTime;
   private Text hiddenEnemyHudText;
   private boolean toggleKeyWasDown = false;

   public TrackerClient() {
   }

   public void onInitializeClient() {
      config = this.loadConfig();
      applyRuntimeConfig(config);
      ClientTickEvents.END_CLIENT_TICK.register(this::tickTracker);
      HudRenderCallback.EVENT.register(this::renderHiddenEnemyHud);
      WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::renderEnemyHitboxes);
   }

   private void tickTracker(MinecraftClient client) {
      this.maybeReloadConfig();
      this.handleToggleKey(client);
      if (enabled && client.player != null && client.world != null) {
         PlayerEntity closestHiddenTarget = null;
         double closestHiddenDistanceSquared = Double.MAX_VALUE;
         int hiddenTargetCount = 0;

         for (PlayerEntity target : client.world.getPlayers()) {
            if (this.shouldTrackHiddenEnemy(client, target)) {
               hiddenTargetCount++;
               double distanceSquared = client.player.squaredDistanceTo(target);
               if (distanceSquared < closestHiddenDistanceSquared) {
                  closestHiddenDistanceSquared = distanceSquared;
                  closestHiddenTarget = target;
               }
            }
         }

         this.hiddenEnemyHudText = closestHiddenTarget == null
            ? null
            : this.createHiddenEnemyHudText(client, closestHiddenTarget, hiddenTargetCount, Math.sqrt(closestHiddenDistanceSquared));
      } else {
         this.hiddenEnemyHudText = null;
      }
   }

   private void renderEnemyHitboxes(WorldRenderContext context) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (enabled && client.player != null && context.world() != null) {
         MatrixStack matrices = context.matrixStack();
         if (matrices != null) {
            Vec3d cameraPos = context.camera().getPos();
            matrices.push();
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            VertexConsumer lines = this.wallHitboxVertexConsumers.getBuffer(RenderLayer.getLines());

            for (PlayerEntity target : context.world().getPlayers()) {
               if (this.shouldTrackHiddenEnemy(client, target)) {
                  VertexRendering.drawBox(matrices, lines, target.getBoundingBox().expand(0.03), 1.0F, 0.2F, 0.2F, 0.95F);
               }
            }

            matrices.pop();
            this.wallHitboxVertexConsumers.draw();
         }
      }
   }

   private void handleToggleKey(MinecraftClient client) {
      int keyCode = normalizeToggleKeyCode(toggleKeyCode);
      if (keyCode == -1) {
         this.toggleKeyWasDown = false;
      } else {
         boolean keyDown = isToggleBindingPressed(client, keyCode);
         if (keyDown && !this.toggleKeyWasDown) {
            enabled = !enabled;
            config.enabled = enabled;
            this.saveConfig(config);
            this.sendToggleMessage(client, enabled, "Tracker " + (enabled ? "enabled" : "disabled"));
         }

         this.toggleKeyWasDown = keyDown;
      }
   }

   private void sendToggleMessage(MinecraftClient client, boolean enabled, String message) {
      if (client.player != null) {
         Text text = Text.literal(message).formatted(enabled ? Formatting.GREEN : Formatting.RED);
         client.player.sendMessage(text, false);
         client.player.sendMessage(text, true);
      }
   }

   private void renderHiddenEnemyHud(DrawContext drawContext, RenderTickCounter tickCounter) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (enabled && client.player != null && !client.options.hudHidden && this.hiddenEnemyHudText != null) {
         TrackerClient.HiddenHudLayout layout = this.layoutHiddenHud(drawContext.getScaledWindowWidth(), this.hiddenEnemyHudText, client);
         drawContext.fill(layout.x() - 4, layout.y() - 2, layout.x() + layout.width() + 4, layout.y() + 10, -1879048192);
         drawContext.drawTextWithShadow(client.textRenderer, this.hiddenEnemyHudText, layout.x(), layout.y(), -1);
      }
   }

   private Text createHiddenEnemyHudText(MinecraftClient client, PlayerEntity target, int hiddenTargetCount, double distance) {
      double roundedDistance = Math.round(distance * 10.0) / 10.0;
      String direction = this.getHiddenTargetDirection(client, target);
      String suffix = hiddenTargetCount > 1 ? " +" + (hiddenTargetCount - 1) : "";
      MutableText text = Text.literal("Alert ");
      text.append(this.getColoredHudName(target));
      text.append(Text.literal(" " + roundedDistance + "m " + direction + suffix));
      return text;
   }

   private Text getColoredHudName(PlayerEntity target) {
      AbstractTeam team = target.getScoreboardTeam();
      return team != null
         ? team.decorateName(Text.literal(target.getGameProfile().getName())).copy()
         : Text.literal(target.getGameProfile().getName()).formatted(this.getHiddenHudNameFormatting(target));
   }

   private Formatting getHiddenHudNameFormatting(PlayerEntity target) {
      AbstractTeam team = target.getScoreboardTeam();
      if (team == null) {
         return Formatting.WHITE;
      } else {
         Formatting color = team.getColor();
         return color != null && color != Formatting.RESET ? color : Formatting.WHITE;
      }
   }

   private TrackerClient.HiddenHudLayout layoutHiddenHud(int scaledWidth, Text text, MinecraftClient client) {
      int textWidth = client.textRenderer.getWidth(text);
      int x = Math.max(4, hudOffsetX);
      int maxX = scaledWidth - textWidth - 4;
      if (x > maxX) {
         x = Math.max(4, maxX);
      }

      int y = Math.max(4, hudY);
      return new TrackerClient.HiddenHudLayout(x, y, textWidth);
   }

   private String getHiddenTargetDirection(MinecraftClient client, PlayerEntity target) {
      Vec3d delta = target.getPos().subtract(client.player.getPos());
      double yawRadians = Math.toRadians(client.player.getYaw());
      double forwardX = -Math.sin(yawRadians);
      double forwardZ = Math.cos(yawRadians);
      double rightX = -forwardZ;
      double forwardComponent = delta.x * forwardX + delta.z * forwardZ;
      double rightComponent = delta.x * rightX + delta.z * forwardX;
      double relativeAngle = Math.toDegrees(Math.atan2(rightComponent, forwardComponent));
      String horizontal;
      if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
         horizontal = "FRONT";
      } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
         horizontal = "FRONT-RIGHT";
      } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
         horizontal = "RIGHT";
      } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
         horizontal = "BACK-RIGHT";
      } else if (relativeAngle <= -22.5 && relativeAngle > -67.5) {
         horizontal = "FRONT-LEFT";
      } else if (relativeAngle <= -67.5 && relativeAngle > -112.5) {
         horizontal = "LEFT";
      } else if (relativeAngle <= -112.5 && relativeAngle > -157.5) {
         horizontal = "BACK-LEFT";
      } else {
         horizontal = "BACK";
      }

      String vertical = this.getHiddenTargetVerticalDirection(client, target);
      return vertical == null ? horizontal : horizontal + " " + vertical;
   }

   private String getHiddenTargetVerticalDirection(MinecraftClient client, PlayerEntity target) {
      double deltaY = target.getEyeY() - client.player.getEyeY();
      if (deltaY >= 1.25) {
         return "UP";
      } else {
         return deltaY <= -1.25 ? "DOWN" : null;
      }
   }

   private boolean shouldTrackHiddenEnemy(MinecraftClient client, PlayerEntity target) {
      if (target != null && client.player != null && target != client.player && target.isAlive() && !target.isSpectator()) {
         return client.player.squaredDistanceTo(target) > range * range ? false : !ignoreOwnTeam || !this.isSameTrackedTeam(client.player, target);
      } else {
         return false;
      }
   }

   private boolean isSameTrackedTeam(PlayerEntity player, PlayerEntity target) {
      if (player == null || target == null) {
         return false;
      } else if (!player.isTeammate(target) && !target.isTeammate(player)) {
         AbstractTeam playerTeam = player.getScoreboardTeam();
         AbstractTeam targetTeam = target.getScoreboardTeam();
         if (playerTeam == null || targetTeam == null) {
            return false;
         } else if (playerTeam == targetTeam) {
            return true;
         } else {
            String playerTeamName = playerTeam.getName();
            String targetTeamName = targetTeam.getName();
            return playerTeamName != null && targetTeamName != null && !playerTeamName.isBlank() && playerTeamName.equalsIgnoreCase(targetTeamName);
         }
      } else {
         return true;
      }
   }

   private void maybeReloadConfig() {
      long now = System.currentTimeMillis();
      if (now - this.lastConfigCheckAtMs >= 5000L) {
         this.lastConfigCheckAtMs = now;

         try {
            if (!Files.exists(CONFIG_PATH)) {
               return;
            }

            FileTime currentWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
            if (this.lastKnownConfigWriteTime != null && currentWriteTime.equals(this.lastKnownConfigWriteTime)) {
               return;
            }

            config = this.loadConfig();
            applyRuntimeConfig(config);
         } catch (IOException e) {
            LOGGER.warn("Tracker config reload failed: {}", e.getMessage());
         }
      }
   }

   private TrackerClient.Config loadConfig() {
      try {
         if (Files.exists(CONFIG_PATH)) {
            TrackerClient.Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), TrackerClient.Config.class);
            if (loaded != null) {
               // Graceful migration: never delete the user's file on an older version.
               // normalize() upgrades and clamps values in place so settings persist.
               loaded.normalize();
               this.lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
               return loaded;
            }
         }
      } catch (Exception e) {
         LOGGER.warn("Tracker config unreadable ({}); using safe defaults", e.toString());
      }

      TrackerClient.Config fresh = new TrackerClient.Config();
      fresh.normalize();
      this.saveConfig(fresh);
      return fresh;
   }

   private void saveConfig(TrackerClient.Config cfg) {
      try {
         cfg.normalize();
         applyRuntimeConfig(cfg);
         Files.createDirectories(CONFIG_PATH.getParent());
         Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
         this.lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
      } catch (IOException e) {
         LOGGER.warn("Tracker config save failed: {}", e.getMessage());
      }
   }

   public static void applyRuntimeConfig(TrackerClient.Config cfg) {
      if (cfg != null) {
         config = cfg;
         enabled = cfg.enabled;
         toggleKeyCode = cfg.toggleKeyCode;
         ignoreOwnTeam = cfg.ignoreOwnTeam;
         range = cfg.range;
         hudOffsetX = cfg.hudOffsetX;
         hudY = cfg.hudY;
      }
   }

   /** Typed save used by the GUI: validates, applies live, and writes the file. */
   public static void saveConfigStatic(TrackerClient.Config cfg) {
      if (cfg == null) return;
      cfg.normalize();
      applyRuntimeConfig(cfg);
      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
      } catch (IOException e) {
         LOGGER.warn("Tracker config save failed: {}", e.getMessage());
      }
   }

   private static int normalizeToggleKeyCode(int keyCode) {
      if (keyCode >= 1000) {
         return keyCode;
      } else {
         return keyCode <= 0 ? -1 : keyCode;
      }
   }

   private static boolean isToggleBindingPressed(MinecraftClient client, int keyCode) {
      if (client.currentScreen != null || !client.isWindowFocused()) {
         return false;
      } else {
         return keyCode >= 1000
            ? GLFW.glfwGetMouseButton(client.getWindow().getHandle(), keyCode - 1000) == 1
            : GLFW.glfwGetKey(client.getWindow().getHandle(), keyCode) == 1;
      }
   }

   public static final class Config {
      public int configVersion = 6;
      public boolean enabled = true;
      public int toggleKeyCode = -1;
      public boolean ignoreOwnTeam = true;
      public double range = 96.0;
      public int hudOffsetX = 0;
      public int hudY = 8;

      public Config() {
      }

      public void normalize() {
         if (this.configVersion < CURRENT_CONFIG_VERSION) {
            this.configVersion = CURRENT_CONFIG_VERSION;
         }

         // Clamp to safe ranges so a hand-edited or corrupt file can never crash rendering.
         if (Double.isNaN(this.range) || Double.isInfinite(this.range)) {
            this.range = 96.0;
         }
         this.range = Math.max(1.0, Math.min(256.0, this.range));
         this.hudOffsetX = Math.max(-10000, Math.min(10000, this.hudOffsetX));
         this.hudY = Math.max(-10000, Math.min(10000, this.hudY));
         this.toggleKeyCode = TrackerClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private record HiddenHudLayout(int x, int y, int width) {
   }
}
