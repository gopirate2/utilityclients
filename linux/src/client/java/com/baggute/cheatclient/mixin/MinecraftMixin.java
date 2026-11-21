package com.baggute.cheatclient.mixin;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public final class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void utilityclient$blockClick(CallbackInfoReturnable<Boolean> cir) {
        if (com.baggute.cheatclient.GameplayHooks.blockClick(this)) cir.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void utilityclient$blockMining(boolean held, CallbackInfo ci) {
        if (com.baggute.cheatclient.GameplayHooks.blockClick(this)) ci.cancel();
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void utilityclient$tick(CallbackInfo ci) {
        com.baggute.cheatclient.GameplayHooks.beforeTick(this);
        if (UtilityClient.eventBus != null) {
            UtilityClient.eventBus.post(new TickEvent());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void utilityclient$onClientTick(CallbackInfo ci) {
        UtilityClient.onClientTick((Minecraft) (Object) this);
    }
}
