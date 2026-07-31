package com.masteryj.core;

import com.masteryj.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Utilities for the physical and queued state of Minecraft's configurable key bindings. */
public final class PhysicalKeyBinding {

    private PhysicalKeyBinding() {
    }

    /** Read the real keyboard/mouse state without trusting the mutable KeyBinding pressed flag. */
    public static boolean isPressed(MinecraftClient client, KeyBinding binding) {
        InputUtil.Key key = boundKey(client, binding);
        if (key == null) return false;

        long handle = client.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(handle, key.getCode());
    }

    /**
     * Queue one press on the player's configured binding. START_CLIENT_TICK dispatch ensures
     * vanilla consumes it during the same tick and owns all attack/use cooldown and packet logic.
     */
    public static boolean queuePress(MinecraftClient client, KeyBinding binding) {
        InputUtil.Key key = boundKey(client, binding);
        if (key == null) return false;
        KeyBinding.onKeyPressed(key);
        return true;
    }

    private static InputUtil.Key boundKey(MinecraftClient client, KeyBinding binding) {
        if (client == null || client.getWindow() == null || binding == null || binding.isUnbound()) {
            return null;
        }
        InputUtil.Key key = ((KeyBindingAccessor) binding).yjhack$getBoundKey();
        return key == null || key.getCode() < 0 ? null : key;
    }
}
