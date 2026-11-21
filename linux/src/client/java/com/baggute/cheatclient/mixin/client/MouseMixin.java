package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.player.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="turnPlayer",at=@At(value="INVOKE",target="Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object utilityclient$zoomSensitivity(net.minecraft.client.OptionInstance<?> option,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Object> original){
        return com.baggute.cheatclient.GameplayHooks.lightmapOption(option);
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void utilityclient$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (UtilityClient.moduleManager == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        Zoom zoom = (Zoom) UtilityClient.moduleManager.getByName("Zoom");
        // Only swallow the scroll while it is actually zooming; otherwise the
        // hotbar must keep scrolling normally.
        if (zoom != null && zoom.onScroll(yOffset)) ci.cancel();
    }
}
