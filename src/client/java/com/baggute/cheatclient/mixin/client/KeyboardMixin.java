// language: Java, file: KeyboardMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void utilityclient$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (com.baggute.cheatclient.LateBridge.onKeyPress(event, action)) ci.cancel();
    }
}
