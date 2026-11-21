package com.baggute.cheatclient.mixin.client;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerReachMixin {
    @Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
    private void utilityclient$reach(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(com.baggute.cheatclient.GameplayHooks.entityRange(cir.getReturnValue(), this));
    }
}
