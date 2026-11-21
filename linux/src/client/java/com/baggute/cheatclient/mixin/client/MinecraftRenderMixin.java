// language: Java, file: MinecraftRenderMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftRenderMixin {

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void utilityclient$onRenderFrame(boolean advanceGameTime, CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        UtilityClient.moduleManager.onRenderFrame(partialTick);
    }
}