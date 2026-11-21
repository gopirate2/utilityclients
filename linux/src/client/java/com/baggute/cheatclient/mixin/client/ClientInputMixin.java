// language: Java, file: ClientInputMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.Freecam;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Freezes the player's movement while the camera is detached.
 *
 * <p>Targets {@link KeyboardInput}, not its {@code ClientInput} base: in 26.2
 * {@code ClientInput.tick()} is empty and {@code KeyboardInput} overrides it
 * without calling super, so the player's real input (a KeyboardInput) never ran
 * the old hook and WASD kept walking the body while freecam was on.
 */
@Mixin(KeyboardInput.class)
public class ClientInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void utilityclient$freecamFreeze(CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;
        Freecam fc = (Freecam) UtilityClient.moduleManager.getByName("Freecam");
        if (fc == null || !fc.isEnabled()) return;

        Object self = this;

        // Movement is carried in primitives, an Input record and a Vec2 moveVector
        // (which applyInput actually reads), so every shape is zeroed here. The
        // walk covers inherited fields as well, since the base class declares some.
        for (Class<?> type = self.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field f : type.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Class<?> fieldType = f.getType();
                    if (fieldType == float.class) {
                        f.setFloat(self, 0f);
                    } else if (fieldType == double.class) {
                        f.setDouble(self, 0.0);
                    } else if (fieldType == boolean.class) {
                        f.setBoolean(self, false);
                    } else if (fieldType == Vec2.class) {
                        f.set(self, Vec2.ZERO);
                    } else if (fieldType.getSimpleName().equals("Input")) {
                        f.set(self, new Input(false, false, false, false, false, false, false));
                    }
                } catch (Throwable ignored) {
                    // A field the JVM refuses to open must not break the input tick.
                }
            }
        }
    }
}
