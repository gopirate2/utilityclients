package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.player.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class HandRenderMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void utilityclient$hideHand(CameraRenderState cameraRenderState, float partialTick,
                                        Matrix4fc matrix, CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;
        Zoom zoom = (Zoom) UtilityClient.moduleManager.getByName("Zoom");
        if (zoom != null && zoom.shouldHideHand()) ci.cancel();
    }
}
