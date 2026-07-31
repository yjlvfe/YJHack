package com.masteryj.core;

import com.masteryj.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Reads the real state of Minecraft's configured keyboard or mouse binding. */
public final class PhysicalKeyBinding {

    private PhysicalKeyBinding() {
    }

    public static boolean isPressed(MinecraftClient client, KeyBinding binding) {
        InputUtil.Key key = boundKey(client, binding);
        if (key == null) return false;

        long handle = client.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(handle, key.getCode());
    }

    private static InputUtil.Key boundKey(MinecraftClient client, KeyBinding binding) {
        if (client == null || client.getWindow() == null || binding == null || binding.isUnbound()) {
            return null;
        }
        InputUtil.Key key = ((KeyBindingAccessor) binding).yjhack$getBoundKey();
        return key == null || key.getCode() < 0 ? null : key;
    }
}
