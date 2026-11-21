// language: Java, file: FreecamMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.render.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FreecamMixin {

    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At("TAIL"))
    private void utilityclient$freecam(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;

        Freecam fc = (Freecam) UtilityClient.moduleManager.getByName("Freecam");
        if (fc == null || !fc.isEnabled()) return;

        Vec3 p = Freecam.camPos;
        setPosition(p.x, p.y, p.z);

        // Use our virtual camera's rotation
        setRotation(Freecam.camYaw, Freecam.camPitch);
    }
}