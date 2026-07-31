package com.masteryj.aimassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.masteryj.config.RecommendedSettings;
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

/**
 * Deterministic, line-of-sight AimAssist. Target retention is capped at 3.5 blocks and does not
 * change Minecraft's attack reach. While a bed is actually being broken, the bed owns the aim.
 */
public final class AimAssistClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-AimAssist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("aimassist.json");
    private static final int CURRENT_CONFIG_VERSION = 10;

    private final Map<Integer, AimSample> tickSampleCache = new HashMap<>();

    private World lastWorld;
    private PlayerEntity target;
    private boolean toggleKeyWasDown;
    private boolean requireToggleRelease;
    private boolean requireAttackRelease;

    private boolean bedBreakLocked;
    private BlockPos bedBreakPos;

    public static Config config;
    public static boolean enabled;
    public static int toggleKeyCode = -1;
    public static float speed = RecommendedSettings.AIM_SPEED;
    public static float smoothness = RecommendedSettings.AIM_SMOOTHNESS;
    public static float fov = RecommendedSettings.AIM_FOV;
    public static double range = RecommendedSettings.AIM_MAX_RANGE;
    public static boolean stickyLock = RecommendedSettings.AIM_STICKY_LOCK;
    public static boolean lineOfSight = RecommendedSettings.AIM_LINE_OF_SIGHT;
    public static boolean bedLock = RecommendedSettings.AIM_BED_LOCK;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        applyRuntimeConfig(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tickAimAssist);
    }

    private void tickAimAssist(MinecraftClient client) {
        tickSampleCache.clear();
        handleToggleKey(client);

        boolean attackDown = isAttackDown(client);

        if (client == null || client.world != lastWorld) {
            lastWorld = client == null ? null : client.world;
            clearRuntimeState();
            requireAttackRelease = attackDown;
            return;
        }

        if (!enabled || !isInActiveGameplay(client)) {
            if (attackDown) requireAttackRelease = true;
            clearRuntimeState();
            return;
        }

        if (requireAttackRelease) {
            clearRuntimeState();
            if (!attackDown) requireAttackRelease = false;
            return;
        }

        if (updateBedBreakLock(client, attackDown)) {
            clearTargetState();
            return;
        }

        if (!isLatchedTargetValid(client, target)) clearTargetState();

        if (target == null) {
            if (!attackDown) return;

            if (client.crosshairTarget instanceof EntityHitResult entityHit
                    && entityHit.getEntity() instanceof PlayerEntity direct
                    && isTargetValid(client, direct, range * range, fov)) {
                target = direct;
            }
            if (target == null) target = findBestTarget(client);
        }

        if (target == null) return;

        AimSample sample = getAimSample(client, target);
        if (sample == null || !AimAssistRangePolicy.isWithinDistance(sample.distanceSquared(), range)) {
            clearTargetState();
            return;
        }
        if (!stickyLock && !insideFov(client, sample, fov)) {
            clearTargetState();
            return;
        }

        applyAimAssist(client, sample);
    }

    private boolean updateBedBreakLock(MinecraftClient client, boolean attackDown) {
        if (!bedLock) {
            clearBedLockState();
            return false;
        }

        boolean breaking = client.interactionManager != null
                && client.interactionManager.isBreakingBlock();

        if (!bedBreakLocked && attackDown && breaking
                && client.crosshairTarget instanceof BlockHitResult hit
                && isBedAt(client, hit.getBlockPos())) {
            bedBreakLocked = true;
            bedBreakPos = hit.getBlockPos().toImmutable();
            clearTargetState();
        }

        if (!bedBreakLocked) return false;

        boolean sameTarget = client.crosshairTarget instanceof BlockHitResult hit
                && bedBreakPos != null
                && bedBreakPos.equals(hit.getBlockPos());
        boolean bedStillPresent = isBedAt(client, bedBreakPos);
        if (!shouldHoldBedAimLock(true, attackDown, breaking, bedStillPresent, sameTarget)) {
            clearBedLockState();
            return false;
        }
        return true;
    }

    static boolean shouldHoldBedAimLock(boolean locked,
                                        boolean attackDown,
                                        boolean breaking,
                                        boolean bedStillPresent,
                                        boolean sameTarget) {
        return locked && attackDown && breaking && bedStillPresent && sameTarget;
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
        double maxDistanceSquared = range * range;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (!isTargetValid(client, candidate, maxDistanceSquared, fov)) continue;
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
        if (!isBasicTargetValid(client, candidate)
                || candidate.getWorld() != client.world
                || client.world.getEntityById(candidate.getId()) != candidate
                || !AimAssistRangePolicy.isWithinDistance(
                        client.player.squaredDistanceTo(candidate), range)) {
            return false;
        }
        AimSample sample = getAimSample(client, candidate);
        return sample != null && (stickyLock || insideFov(client, sample, fov));
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
        // lineOfSight is normalized to true; the field exists for migration and UI clarity only.
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

    private void applyAimAssist(MinecraftClient client, AimSample sample) {
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();
        float yawDelta = MathHelper.wrapDegrees(sample.angles().yaw() - currentYaw);
        float pitchDelta = sample.angles().pitch() - currentPitch;
        float baseLerp = MathHelper.clamp(speed * (1.25F - smoothness), 0.005F, 1.0F);
        float progress = MathHelper.clamp(1.0F - Math.abs(yawDelta) / Math.max(1.0F, fov), 0.0F, 1.0F);
        float sCurve = progress * progress * (3.0F - 2.0F * progress);
        float lerp = MathHelper.clamp(baseLerp * (0.6F + sCurve * 0.9F), 0.001F, 1.0F);
        float newYaw = currentYaw + yawDelta * lerp;
        float newPitch = currentPitch
                + pitchDelta * MathHelper.clamp(lerp * 0.82F, 0.001F, 1.0F);
        client.player.setYaw(newYaw);
        client.player.setPitch(MathHelper.clamp(newPitch, -90.0F, 90.0F));
        client.player.setHeadYaw(newYaw);
        client.player.setBodyYaw(newYaw);
    }

    static boolean isWithinLockDistance(double distanceSquared, double configuredRange) {
        return AimAssistRangePolicy.isWithinDistance(distanceSquared, configuredRange);
    }

    private void clearTargetState() {
        target = null;
    }

    private void clearBedLockState() {
        bedBreakLocked = false;
        bedBreakPos = null;
    }

    private void clearRuntimeState() {
        clearTargetState();
        clearBedLockState();
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
                clearRuntimeState();
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

    public static Config recommendedDefaults() {
        return Config.recommendedDefaults();
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
            LOGGER.warn("AimAssist config unreadable; using recommended defaults", e);
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
        range = cfg.range;
        stickyLock = cfg.stickyLock;
        lineOfSight = cfg.lineOfSight;
        bedLock = cfg.bedLock;
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
        public float speed = RecommendedSettings.AIM_SPEED;
        public float smoothness = RecommendedSettings.AIM_SMOOTHNESS;
        public float fov = RecommendedSettings.AIM_FOV;
        public double range = RecommendedSettings.AIM_MAX_RANGE;
        public boolean stickyLock = RecommendedSettings.AIM_STICKY_LOCK;
        public boolean lineOfSight = RecommendedSettings.AIM_LINE_OF_SIGHT;
        public boolean bedLock = RecommendedSettings.AIM_BED_LOCK;

        public static Config recommendedDefaults() {
            Config cfg = new Config();
            cfg.configVersion = CURRENT_CONFIG_VERSION;
            cfg.enabled = false;
            cfg.toggleKeyCode = -1;
            cfg.speed = RecommendedSettings.AIM_SPEED;
            cfg.smoothness = RecommendedSettings.AIM_SMOOTHNESS;
            cfg.fov = RecommendedSettings.AIM_FOV;
            cfg.range = RecommendedSettings.AIM_MAX_RANGE;
            cfg.stickyLock = RecommendedSettings.AIM_STICKY_LOCK;
            cfg.lineOfSight = true;
            cfg.bedLock = RecommendedSettings.AIM_BED_LOCK;
            cfg.normalize();
            return cfg;
        }

        public Config copy() {
            Config result = new Config();
            result.configVersion = configVersion;
            result.enabled = enabled;
            result.toggleKeyCode = toggleKeyCode;
            result.speed = speed;
            result.smoothness = smoothness;
            result.fov = fov;
            result.range = range;
            result.stickyLock = stickyLock;
            result.lineOfSight = lineOfSight;
            result.bedLock = bedLock;
            result.normalize();
            return result;
        }

        public void normalize() {
            configVersion = CURRENT_CONFIG_VERSION;
            speed = sanitize(speed, RecommendedSettings.AIM_SPEED, 0.01F, 1.0F);
            smoothness = sanitize(smoothness, RecommendedSettings.AIM_SMOOTHNESS, 0.0F, 1.0F);
            fov = sanitize(fov, RecommendedSettings.AIM_FOV, 10.0F, 180.0F);
            range = AimAssistRangePolicy.clampConfiguredDistance(range);
            lineOfSight = true; // hard legal rule: no tracking through solid blocks
            toggleKeyCode = normalizeToggleKeyCode(toggleKeyCode);
        }

        private static float sanitize(float value, float fallback, float min, float max) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return fallback;
            return Math.max(min, Math.min(max, value));
        }
    }
}
