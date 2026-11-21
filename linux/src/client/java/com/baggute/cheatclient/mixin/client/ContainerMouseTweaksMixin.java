package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.ContainerMouseHooks;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerMouseTweaksMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void utilityclient$mouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMouseHooks.handle(this, event, 0)) cir.setReturnValue(true);
    }
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void utilityclient$mouseDragged(MouseButtonEvent event, double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMouseHooks.handle(this, event, 1)) cir.setReturnValue(true);
    }
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void utilityclient$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (ContainerMouseHooks.handle(this, event, 2)) cir.setReturnValue(true);
    }
}
