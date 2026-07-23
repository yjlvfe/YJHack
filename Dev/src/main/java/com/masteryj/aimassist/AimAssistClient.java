package com.masteryj.aimassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AimAssistClient implements ClientModInitializer {
   private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AimAssist");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aimassist.json");
   private static final int CURRENT_CONFIG_VERSION = 6;
   private final Random random = new Random();
   private final Map<Integer, Vec3d> lastVisiblePointCache = new HashMap<>();
   public static AimAssistClient.Config config;
   public static boolean enabled = false;
   public static int toggleKeyCode = -1;
   public static float speed = 0.24F;
   public static float smoothness = 0.35F;
   public static float fov = 70.0F;
   private long lastConfigCheckAtMs = 0L;
   private FileTime lastKnownConfigWriteTime;
   private PlayerEntity target;
   private BlockPos blockBreakFocusPos;
   private long blockBreakFocusStartedAtMs = 0L;
   private boolean toggleKeyWasDown = false;
   private float targetOffsetX = 0.0F;
   private float targetOffsetY = 0.0F;
   private float offsetVelocityX = 0.0F;
   private float offsetVelocityY = 0.0F;
   private long lastOffsetUpdateMs = 0L;

   public void onInitializeClient() {
      config = this.loadConfig();
      applyRuntimeConfig(config);
      ClientTickEvents.END_CLIENT_TICK.register(this::tickAimAssist);
   }

   private void tickAimAssist(MinecraftClient client) {
      this.maybeReloadConfig();
      this.handleToggleKey(client);
      if (enabled && client.player != null && client.world != null && client.currentScreen == null) {
         long now = System.currentTimeMillis();
         boolean leftDown = this.isMouseDown(client, 0);
         if (!client.player.isAlive() || client.player.isSpectator()) {
            this.clearTarget();
            this.clearBlockBreakFocus();
         } else if (this.isActualBlockBreaking(client, leftDown, now)) {
            this.clearTarget();
         } else {
            if (!this.isTargetValid(client, this.target, true, now)) {
               this.target = null;
            }

            if (this.target == null) {
               if (!leftDown) {
                  return;
               }

               if (client.crosshairTarget instanceof EntityHitResult entityHitResult
                  && entityHitResult.getEntity() instanceof PlayerEntity playerTarget
                  && this.isTargetValid(client, playerTarget, false, now)) {
                  this.target = playerTarget;
               }

               if (this.target == null) {
                  this.target = this.findBestTarget(client, now);
               }
            }

            if (this.target != null) {
               AimAssistClient.AimSample sample = this.getAimSample(client, this.target);
               if (sample != null && !(sample.distanceSquared() > 12.25)) {
                  this.applyAimAssist(client, sample);
               } else {
                  this.clearTarget();
               }
            }
         }
      } else {
         this.clearTarget();
      }
   }

   private PlayerEntity findBestTarget(MinecraftClient client, long now) {
      PlayerEntity bestTarget = null;
      double bestScore = Double.MAX_VALUE;

      for (PlayerEntity candidate : client.world.getPlayers()) {
         if (this.isTargetValid(client, candidate, false, now)) {
            AimAssistClient.AimSample sample = this.getAimSample(client, candidate);
            if (sample != null && !(sample.distanceSquared() > 20.25)) {
               float yawDifference = Math.abs(MathHelper.wrapDegrees(sample.angles().yaw() - client.player.getYaw()));
               float pitchDifference = Math.abs(MathHelper.wrapDegrees(sample.angles().pitch() - client.player.getPitch()));
               if (!(yawDifference > fov) && !(pitchDifference > fov)) {
                  double score = yawDifference * 1.65 + pitchDifference * 1.1 + Math.sqrt(sample.distanceSquared()) * 3.8;
                  if (score < bestScore) {
                     bestScore = score;
                     bestTarget = candidate;
                  }
               }
            }
         }
      }

      return bestTarget;
   }

   private boolean isTargetValid(MinecraftClient client, PlayerEntity player, boolean keepCurrentTarget, long now) {
      if (client.player != null
         && player != null
         && player != client.player
         && !this.isFriendlyTarget(client, player)
         && player.isAlive()
         && !player.isSpectator()) {
         if (client.player.squaredDistanceTo(player) > 23.04) {
            return false;
         } else {
            AimAssistClient.AimSample sample = this.getAimSample(client, player);
            if (sample == null || sample.distanceSquared() > 17.64) {
               return false;
            } else if (keepCurrentTarget) {
               return true;
            } else {
               float yawDifference = Math.abs(MathHelper.wrapDegrees(sample.angles().yaw() - client.player.getYaw()));
               float pitchDifference = Math.abs(MathHelper.wrapDegrees(sample.angles().pitch() - client.player.getPitch()));
               return yawDifference <= fov && pitchDifference <= fov;
            }
         }
      } else {
         return false;
      }
   }

   private boolean isFriendlyTarget(MinecraftClient client, PlayerEntity target) {
      return client.player != null && target != null && (client.player.isTeammate(target) || target.isTeammate(client.player));
   }

   private void applyAimAssist(MinecraftClient client, AimAssistClient.AimSample sample) {
      float currentYaw = client.player.getYaw();
      float currentPitch = client.player.getPitch();
      this.updateDynamicOffset();
      float targetYaw = sample.angles().yaw() + this.targetOffsetX;
      float targetPitch = sample.angles().pitch() + this.targetOffsetY;
      float yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
      float pitchDelta = targetPitch - currentPitch;
      float absYawDelta = Math.abs(yawDelta);
      float baseLerp = MathHelper.clamp(speed * (1.25F - smoothness), 0.005F, 1.0F);
      float progress = MathHelper.clamp(1.0F - absYawDelta / fov, 0.0F, 1.0F);
      float sCurveFactor = progress * progress * (3.0F - 2.0F * progress);
      float stickyLerp = baseLerp * (0.6F + sCurveFactor * 0.9F);
      float jitterX = (this.random.nextFloat() - 0.5F) * 0.01F;
      float jitterY = (this.random.nextFloat() - 0.5F) * 0.01F;
      float newYaw = currentYaw + yawDelta * MathHelper.clamp(stickyLerp, 0.001F, 1.0F) + jitterX;
      float newPitch = currentPitch + pitchDelta * MathHelper.clamp(stickyLerp * 0.82F, 0.001F, 1.0F) + jitterY;
      client.player.setYaw(newYaw);
      client.player.setPitch(MathHelper.clamp(newPitch, -90.0F, 90.0F));
      client.player.setHeadYaw(newYaw);
      client.player.setBodyYaw(newYaw);
   }

   private void updateDynamicOffset() {
      long now = System.currentTimeMillis();
      if (now - this.lastOffsetUpdateMs >= 50L) {
         this.lastOffsetUpdateMs = now;
         float maxDrift = 0.8F;
         float accel = 0.06F;
         this.offsetVelocityX = this.offsetVelocityX + (float)(this.random.nextGaussian() * accel * 0.5);
         this.offsetVelocityY = this.offsetVelocityY + (float)(this.random.nextGaussian() * accel * 0.5);
         this.offsetVelocityX *= 0.82F;
         this.offsetVelocityY *= 0.82F;
         this.targetOffsetX = MathHelper.clamp(this.targetOffsetX + this.offsetVelocityX, -maxDrift, maxDrift);
         this.targetOffsetY = MathHelper.clamp(this.targetOffsetY + this.offsetVelocityY, -maxDrift * 0.7F, maxDrift * 0.7F);
      }
   }

   private AimAssistClient.AimSample getAimSample(MinecraftClient client, PlayerEntity target) {
      if (client.player != null && client.world != null) {
         Vec3d start = client.player.getEyePos();
         Vec3d bestPoint = this.findBestVisibleAimPoint(client, start, target);
         return bestPoint == null ? null : new AimAssistClient.AimSample(bestPoint, this.getAimAngles(client, bestPoint), start.squaredDistanceTo(bestPoint));
      } else {
         return null;
      }
   }

   private Vec3d findBestVisibleAimPoint(MinecraftClient client, Vec3d start, PlayerEntity target) {
      Box hitBox = target.getBoundingBox().expand(-0.03);
      Vec3d center = hitBox.getCenter();
      Vec3d headPoint = new Vec3d(center.x, hitBox.minY + hitBox.getLengthY() * 0.88, center.z);
      Vec3d chestPoint = new Vec3d(center.x, hitBox.minY + hitBox.getLengthY() * 0.65, center.z);
      Vec3d cached = this.lastVisiblePointCache.get(target.getId());
      if (cached != null && cached.squaredDistanceTo(chestPoint) < 0.25) {
         return cached;
      } else {
         Vec3d[] priorityPoints = new Vec3d[]{chestPoint, headPoint, center};

         for (Vec3d point : priorityPoints) {
            if (this.isVisiblePoint(client, start, point)) {
               this.lastVisiblePointCache.put(target.getId(), point);
               return point;
            }
         }

         this.lastVisiblePointCache.remove(target.getId());
         return null;
      }
   }

   private AimAssistClient.AimAngles getAimAngles(MinecraftClient client, Vec3d targetPoint) {
      Vec3d eyePos = client.player.getEyePos();
      double dx = targetPoint.x - eyePos.x;
      double dy = targetPoint.y - eyePos.y;
      double dz = targetPoint.z - eyePos.z;
      double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
      if (horizontalDistance <= 1.0E-4) {
         return new AimAssistClient.AimAngles(client.player.getYaw(), client.player.getPitch());
      } else {
         float targetYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
         float targetPitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDistance)));
         return new AimAssistClient.AimAngles(targetYaw, targetPitch);
      }
   }

   private boolean isVisiblePoint(MinecraftClient client, Vec3d start, Vec3d end) {
      BlockHitResult hitResult = client.world
         .raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
      return hitResult.getType() == HitResult.Type.MISS;
   }

   private boolean isActualBlockBreaking(MinecraftClient client, boolean leftDown, long now) {
      if (leftDown && client.crosshairTarget instanceof BlockHitResult blockHitResult) {
         BlockPos currentPos = blockHitResult.getBlockPos().toImmutable();
         if (!currentPos.equals(this.blockBreakFocusPos)) {
            this.blockBreakFocusPos = currentPos;
            this.blockBreakFocusStartedAtMs = now;
            return false;
         } else {
            return now - this.blockBreakFocusStartedAtMs >= 500L;
         }
      } else {
         this.clearBlockBreakFocus();
         return false;
      }
   }

   private void clearBlockBreakFocus() {
      this.blockBreakFocusPos = null;
      this.blockBreakFocusStartedAtMs = 0L;
   }

   private boolean isMouseDown(MinecraftClient client, int button) {
      return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == 1;
   }

   private void clearTarget() {
      this.target = null;
      this.targetOffsetX = 0.0F;
      this.targetOffsetY = 0.0F;
      this.offsetVelocityX = 0.0F;
      this.offsetVelocityY = 0.0F;
      this.lastVisiblePointCache.clear();
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
            if (!enabled) {
               this.clearTarget();
               this.clearBlockBreakFocus();
            }

            this.sendToggleMessage(client, enabled, "AimAssist " + (enabled ? "enabled" : "disabled"));
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
            LOGGER.warn("AimAssist config reload failed: {}", e.getMessage());
         }
      }
   }

   private AimAssistClient.Config loadConfig() {
      try {
         if (Files.exists(CONFIG_PATH)) {
            AimAssistClient.Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), AimAssistClient.Config.class);
            if (loaded != null) {
               // Graceful migration: never delete the user's file on an older version.
               loaded.normalize();
               this.lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
               return loaded;
            }
         }
      } catch (Exception e) {
         LOGGER.warn("AimAssist config unreadable ({}); using safe defaults", e.toString());
      }

      AimAssistClient.Config fresh = new AimAssistClient.Config();
      fresh.normalize();
      this.saveConfig(fresh);
      return fresh;
   }

   private void saveConfig(AimAssistClient.Config cfg) {
      try {
         cfg.normalize();
         applyRuntimeConfig(cfg);
         Files.createDirectories(CONFIG_PATH.getParent());
         Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
         this.lastKnownConfigWriteTime = Files.getLastModifiedTime(CONFIG_PATH);
      } catch (IOException e) {
         LOGGER.warn("AimAssist config save failed: {}", e.getMessage());
      }
   }

   public static void applyRuntimeConfig(AimAssistClient.Config cfg) {
      if (cfg != null) {
         config = cfg;
         enabled = cfg.enabled;
         toggleKeyCode = cfg.toggleKeyCode;
         speed = cfg.speed;
         smoothness = cfg.smoothness;
         fov = cfg.fov;
      }
   }

   /** Typed save used by the GUI: validates, applies live, and writes the file. */
   public static void saveConfigStatic(AimAssistClient.Config cfg) {
      if (cfg == null) return;
      cfg.normalize();
      applyRuntimeConfig(cfg);
      try {
         Files.createDirectories(CONFIG_PATH.getParent());
         Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
      } catch (IOException e) {
         LOGGER.warn("AimAssist config save failed: {}", e.getMessage());
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

   private record AimAngles(float yaw, float pitch) {
   }

   private record AimSample(Vec3d point, AimAssistClient.AimAngles angles, double distanceSquared) {
   }

   public static final class Config {
      public int configVersion = 6;
      public boolean enabled = false;
      public int toggleKeyCode = -1;
      public float speed = 0.24F;
      public float smoothness = 0.35F;
      public float fov = 70.0F;

      public void normalize() {
         if (this.configVersion < CURRENT_CONFIG_VERSION) {
            this.configVersion = CURRENT_CONFIG_VERSION;
         }

         // Clamp to the same bounds the GUI enforces so a corrupt/hand-edited
         // file can never produce NaN rotations or runaway turn speeds.
         this.speed = sanitize(this.speed, 0.24F, 0.01F, 1.0F);
         this.smoothness = sanitize(this.smoothness, 0.35F, 0.0F, 1.0F);
         this.fov = sanitize(this.fov, 70.0F, 10.0F, 180.0F);
         this.toggleKeyCode = AimAssistClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }

      private static float sanitize(float value, float fallback, float min, float max) {
         if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
         }
         return Math.max(min, Math.min(max, value));
      }
   }
}
