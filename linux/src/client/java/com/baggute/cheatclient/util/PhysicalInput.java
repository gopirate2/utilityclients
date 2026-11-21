package com.baggute.cheatclient.util;

import com.mojang.blaze3d.platform.InputConstants;
import com.baggute.cheatclient.render.CaptureGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Reads the current binding, including remapped mouse buttons. */
public final class PhysicalInput {
    private PhysicalInput() {}

    public static boolean focused(Minecraft mc) {
        // Minecraft already owns the GLFW focus callback. Its cached state avoids
        // treating a failed native-handle lookup as every action key being up.
        return mc != null && mc.isWindowActive();
    }

    public static boolean down(Minecraft mc, KeyMapping mapping) {
        if (mapping == null || mapping.isUnbound() || !focused(mc)) return false;
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(CaptureGuard.glfwHandle(mc.getWindow()),
                    key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        }
        for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
            if (GLFW.glfwGetKeyScancode(code) == key.getValue())
                return InputConstants.isKeyDown(mc.getWindow(), code);
        }
        return false;
    }
}
