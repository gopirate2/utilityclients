// language: Java, file: StepHeightMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.movement.StepUp;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class StepHeightMixin {

    @Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
    private void utilityclient$maxUpStep(CallbackInfoReturnable<Float> cir) {
        if (UtilityClient.moduleManager == null
                || (Object) this != net.minecraft.client.Minecraft.getInstance().player) return;
        StepUp mod = (StepUp) UtilityClient.moduleManager.getByName("StepUp");
        if (mod != null && mod.isEnabled()) {
            cir.setReturnValue((float) StepUp.stepHeight);
        }
    }
}