// language: Java, file: EntityMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void utilityclient$freecamTurn(double xo, double yo, CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;

        Freecam fc = (Freecam) UtilityClient.moduleManager.getByName("Freecam");
        if (fc == null || !fc.isEnabled()) return;

        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player) return;

        // Apply the mouse delta to our virtual camera instead of the player body.
        // Vanilla turn() multiplies inputs by 0.15f.
        Freecam.camYaw += (float) xo * 0.15F;
        Freecam.camPitch += (float) yo * 0.15F;
        Freecam.camPitch = Mth.clamp(Freecam.camPitch, -90.0F, 90.0F);

        // Cancel so the player body does not turn.
        ci.cancel();
    }
}