package com.masteryj.aimassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.core.GameplayGate;
import com.masteryj.core.PhysicalKeyBinding;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BedBlock;
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
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class AimAssistClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AimAssist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aimassist.json");
    private static final int CURRENT_CONFIG_VERSION = 9;

    /** Incremented only when Minecraft's own doAttack() accepted an attack against a player. */
    private static long acceptedPlayerAttackSequence;

    private final Random random = new Random();
    private final Map<Integer, AimSample> tickSampleCache = new HashMap<>();

    private World lastWorld;
    private PlayerEntity target;
    private boolean toggleKeyWasDown;
    private boolean requireToggleRelease;
    private boolean attackWasDown;
    private boolean requireAttackRelease;

    private boolean bedBreakLocked;
    private BlockPos bedBreakPos;
    private long bedLockAttackSequence;

    private float targetOffsetX;
    private float targetOffsetY;
    private float offsetVelocityX;
    private float offsetVelocityY;
    private long lastOffsetUpdateNanos = Long.MIN_VALUE;

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = -1;
    public static float speed = 0.28F;
    public static float smoothness = 0.45F;
    public static float fov = 70.0F;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tickAimAssist);
    }

    /** Called by the MinecraftClient mixin after an accepted player attack. */
    public static void onAcceptedPlayerAttack() {
        acceptedPlayerAttackSequence++;
    }

    private void tickAimAssist(MinecraftClient client) {
        tickSampleCache.clear();
        handleToggleKey(client);

        boolean attackDown = isAttackDown(client);

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            clearAllLocks();
            requireAttackRelease = attackDown;
            attackWasDown = attackDown;
            return;
        }

        attackWasDown = attackDown;

        if (!enabled || !isInActiveGameplay(client)) {
            if (attackDown) requireAttackRelease = true;
            clearAllLocks();
            return;
        }

        if (requireAttackRelease) {
            clearAllLocks();
            if (!attackDown) {
                requireAttackRelease = false;
                attackWasDown = false;
            }
            return;
        }

        // A bed is the only block that owns the aim while it is actively being broken.
        // Other blocks do not cancel AimAssist. An accepted player attack explicitly breaks this lock.
        if (updateBedBreakLock(client, attackDown)) {
            clearTarget();
            return;
        }

        if (!isLatchedTargetValid(client, target)) clearTarget();

        if (target == null) {
            if (!attackDown) return;

            if (client.crosshairTarget instanceof EntityHitResult entityHit
                    && entityHit.getEntity() instanceof PlayerEntity direct
                    && isTargetValid(client, direct, AimAssistRangePolicy.MAX_DISTANCE_SQUARED, fov)) {
                target = direct;
            }
            if (target == null) target = findBestTarget(client);
        }

        if (target == null) return;

        AimSample sample = getAimSample(client, target);
        if (sample == null || !AimAssistRangePolicy.isWithinDistance(sample.distanceSquared())) {
            clearTarget();
            return;
        }

        applyAimAssist(client, sample, System.nanoTime());
    }

    private boolean updateBedBreakLock(MinecraftClient client, boolean attackDown) {
        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();

        if (!bedBreakLocked && attackDown && breaking
                && client.crosshairTarget instanceof BlockHitResult hit
                && isBedAt(client, hit.getBlockPos())) {
            bedBreakLocked = true;
            bedBreakPos = hit.getBlockPos().toImmutable();
            bedLockAttackSequence = acceptedPlayerAttackSequence;
            clearTarget();
        }

        if (!bedBreakLocked) return false;

        if (acceptedPlayerAttackSequence != bedLockAttackSequence) {
            clearBedBreakLock();
            return false;
        }

        if (!attackDown || !breaking || !isBedAt(client, bedBreakPos)) {
            clearBedBreakLock();
            return false;
        }

        return true;
    }

    static boolean shouldHoldBedAimLock(boolean locked,
                                        boolean attackDown,
                                        boolean breaking,
                                        boolean bedStillPresent,
                                        boolean acceptedPlayerAttack) {
        return locked && attackDown && breaking && bedStillPresent && !acceptedPlayerAttack;
    }

    private boolean isBedAt(MinecraftClient client, BlockPos pos) {
        return client != null && client.world != null && pos != null
                && client.world.getBlockState(pos).getBlock() instanceof BedBlock;
    }

    private boolean isInActiveGameplay(MinecraftClient client) {
        boolean hasPlayer = client != null && client.player != null;
        boolean hasWorld = client != null && client.world != null;
        return client != null && GameplayGate.active(hasPlayer, hasWorld,
                client.currentScreen != null, client.isWindowFocused(), client.mouse.isCursorLocked(),
                hasPlayer && client.player.isAlive(), hasPlayer && client.player.isSpectator());
    }

    private PlayerEntity findBestTarget(MinecraftClient client) {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (!isTargetValid(client, candidate, AimAssistRangePolicy.MAX_DISTANCE_SQUARED, fov)) continue;
            AimSample sample = getAimSample(client, candidate);
            if (sample == null) continue;

            float yaw = Math.abs(MathHelper.wrapDegrees(sample.angles().yaw() - client.player.getYaw()));
            float pitch = Math.abs(MathHelper.wrapDegrees(sample.angles().pitch() - client.player.getPitch()));
            double score = yaw * 1.65D + pitch * 1.10D + Math.sqrt(sample.distanceSquared()) * 3.8D;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isTargetValid(MinecraftClient client, PlayerEntity candidate,
                                  double maxDistanceSquared, float allowedFov) {
        if (!isBasicTargetValid(client, candidate)
                || client.player.squaredDistanceTo(candidate) > maxDistanceSquared) return false;
        AimSample sample = getAimSample(client, candidate);
        return sample != null && sample.distanceSquared() <= maxDistanceSquared
                && insideFov(client, sample, allowedFov);
    }

    private boolean isLatchedTargetValid(MinecraftClient client, PlayerEntity candidate) {
        return isBasicTargetValid(client, candidate)
                && candidate.getWorld() == client.world
                && client.world.getEntityById(candidate.getId()) == candidate
                && AimAssistRangePolicy.isWithinDistance(client.player.squaredDistanceTo(candidate));
    }

    private boolean isBasicTargetValid(MinecraftClient client, PlayerEntity candidate) {
        return client != null
                && client.player != null
                && client.world != null
                && candidate != null
                && candidate != client.player
                && candidate.isAlive()
                && !candidate.isSpectator()
                && !isFriendlyTarget(client, candidate);
    }

    private boolean insideFov(MinecraftClient client, AimSample sample, float allowedFov) {
        float yaw = Math.abs(MathHelper.wrapDegrees(sample.angles().yaw() - client.player.getYaw()));
        float pitch = Math.abs(MathHelper.wrapDegrees(sample.angles().pitch() - client.player.getPitch()));
        return Math.hypot(yaw, pitch) <= allowedFov;
    }

    private boolean isFriendlyTarget(MinecraftClient client, PlayerEntity candidate) {
        return client.player != null
                && (client.player.isTeammate(candidate) || candidate.isTeammate(client.player));
    }

    private AimSample getAimSample(MinecraftClient client, PlayerEntity candidate) {
        if (client == null || client.player == null || client.world == null || candidate == null) return null;
        int id = candidate.getId();
        if (tickSampleCache.containsKey(id)) return tickSampleCache.get(id);

        Vec3d start = client.player.getEyePos();
        Vec3d point = findBestVisibleAimPoint(client, start, candidate);
        AimSample sample = point == null
                ? null
                : new AimSample(point, getAimAngles(client, point), start.squaredDistanceTo(point));
        tickSampleCache.put(id, sample);
        return sample;
    }

    private Vec3d findBestVisibleAimPoint(MinecraftClient client, Vec3d start, PlayerEntity candidate) {
        Box box = candidate.getBoundingBox().expand(-0.03D);
        Vec3d center = box.getCenter();
        Vec3d chest = new Vec3d(center.x, box.minY + box.getLengthY() * 0.65D, center.z);
        Vec3d head = new Vec3d(center.x, box.minY + box.getLengthY() * 0.88D, center.z);
        for (Vec3d point : new Vec3d[]{chest, head, center}) {
            if (isVisiblePoint(client, start, point)) return point;
        }
        return null;
    }

    private boolean isVisiblePoint(MinecraftClient client, Vec3d start, Vec3d end) {
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private AimAngles getAimAngles(MinecraftClient client, Vec3d point) {
        Vec3d eye = client.player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 1.0E-4D) return new AimAngles(client.player.getYaw(), client.player.getPitch());
        return new AimAngles(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
    }

    private void applyAimAssist(MinecraftClient client, AimSample sample, long now) {
        updateDynamicOffset(now);
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();
        float targetYaw = sample.angles().yaw() + targetOffsetX;
        float targetPitch = sample.angles().pitch() + targetOffsetY;
        float yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        float baseLerp = MathHelper.clamp(speed * (1.25F - smoothness), 0.005F, 1.0F);
        float progress = MathHelper.clamp(1.0F - Math.abs(yawDelta) / Math.max(1.0F, fov), 0.0F, 1.0F);
        float sCurve = progress * progress * (3.0F - 2.0F * progress);
        float lerp = MathHelper.clamp(baseLerp * (0.6F + sCurve * 0.9F), 0.001F, 1.0F);
        float jitterX = (random.nextFloat() - 0.5F) * 0.01F;
        float jitterY = (random.nextFloat() - 0.5F) * 0.01F;
        float newYaw = currentYaw + yawDelta * lerp + jitterX;
        float newPitch = currentPitch
                + pitchDelta * MathHelper.clamp(lerp * 0.82F, 0.001F, 1.0F) + jitterY;
        client.player.setYaw(newYaw);
        client.player.setPitch(MathHelper.clamp(newPitch, -90.0F, 90.0F));
        client.player.setHeadYaw(newYaw);
        client.player.setBodyYaw(newYaw);
    }

    private void updateDynamicOffset(long now) {
        if (lastOffsetUpdateNanos != Long.MIN_VALUE
                && now - lastOffsetUpdateNanos < 50_000_000L) return;
        lastOffsetUpdateNanos = now;
        float accel = 0.06F;
        offsetVelocityX += (float) (random.nextGaussian() * accel * 0.5D);
        offsetVelocityY += (float) (random.nextGaussian() * accel * 0.5D);
        offsetVelocityX *= 0.82F;
        offsetVelocityY *= 0.82F;
        targetOffsetX = MathHelper.clamp(targetOffsetX + offsetVelocityX, -0.8F, 0.8F);
        targetOffsetY = MathHelper.clamp(targetOffsetY + offsetVelocityY, -0.56F, 0.56F);
    }

    static boolean isWithinLockDistance(double distanceSquared) {
        return AimAssistRangePolicy.isWithinDistance(distanceSquared);
    }

    private void clearTarget() {
        target = null;
        targetOffsetX = 0.0F;
        targetOffsetY = 0.0F;
        offsetVelocityX = 0.0F;
        offsetVelocityY = 0.0F;
        lastOffsetUpdateNanos = Long.MIN_VALUE;
    }

    private void clearBedBreakLock() {
        bedBreakLocked = false;
        bedBreakPos = null;
        bedLockAttackSequence = acceptedPlayerAttackSequence;
    }

    private void clearAllLocks() {
        clearTarget();
        clearBedBreakLock();
    }

    private boolean isAttackDown(MinecraftClient client) {
        return client != null && client.options != null
                && PhysicalKeyBinding.isPressed(client, client.options.attackKey);
    }

    private void handleToggleKey(MinecraftClient client) {
        int key = normalizeToggleKeyCode(toggleKeyCode);
        boolean rawDown = key != -1 && isRawBindingPressed(client, key);
        if (client == null || client.currentScreen != null || !client.isWindowFocused()) {
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
            if (config != null) {
                config.enabled = enabled;
                saveConfig(config);
            }
            if (!enabled) {
                boolean attackDown = isAttackDown(client);
                requireAttackRelease = attackDown;
                attackWasDown = attackDown;
                clearAllLocks();
            }
            sendToggleMessage(client, enabled);
        }
        toggleKeyWasDown = rawDown;
    }

    private void sendToggleMessage(MinecraftClient client, boolean on) {
        if (client == null || client.player == null) return;
        Text message = Text.literal("AimAssist " + (on ? "enabled" : "disabled"))
                .formatted(on ? Formatting.GREEN : Formatting.RED);
        client.player.sendMessage(message, true);
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
            LOGGER.warn("AimAssist config unreadable; using defaults", e);
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
            LOGGER.warn("AimAssist config save failed", e);
        }
    }

    public static void applyRuntimeConfig(Config cfg) {
        if (cfg == null) return;
        config = cfg;
        enabled = cfg.enabled;
        toggleKeyCode = cfg.toggleKeyCode;
        speed = cfg.speed;
        smoothness = cfg.smoothness;
        fov = cfg.fov;
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

    private record AimAngles(float yaw, float pitch) {
    }

    private record AimSample(Vec3d point, AimAngles angles, double distanceSquared) {
    }

    public static final class Config {
        public int configVersion = CURRENT_CONFIG_VERSION;
        public boolean enabled = false;
        public int toggleKeyCode = -1;
        public float speed = 0.28F;
        public float smoothness = 0.45F;
        public float fov = 70.0F;

        public void normalize() {
            configVersion = CURRENT_CONFIG_VERSION;
            speed = sanitize(speed, 0.28F, 0.01F, 1.0F);
            smoothness = sanitize(smoothness, 0.45F, 0.0F, 1.0F);
            fov = sanitize(fov, 70.0F, 10.0F, 180.0F);
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }

        private static float sanitize(float value, float fallback, float min, float max) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return fallback;
            return Math.max(min, Math.min(max, value));
        }
    }
}
