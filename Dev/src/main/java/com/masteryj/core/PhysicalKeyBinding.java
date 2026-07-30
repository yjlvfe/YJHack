package com.masteryj.core;

import com.masteryj.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Reads the physical state of whatever key or mouse button the player configured in Controls. */
public final class PhysicalKeyBinding {

    private PhysicalKeyBinding() {
    }

    public static boolean isPressed(MinecraftClient client, KeyBinding binding) {
        if (client == null || client.getWindow() == null || binding == null || binding.isUnbound()) {
            return false;
        }

        InputUtil.Key key = ((KeyBindingAccessor) binding).yjhack$getBoundKey();
        if (key == null || key.getCode() < 0) return false;

        long handle = client.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(handle, key.getCode());
    }
}
