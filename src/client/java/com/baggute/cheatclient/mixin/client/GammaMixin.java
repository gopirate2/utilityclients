package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.GameplayHooks;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fullbright changes lightmap reads; options UI and serialization retain vanilla values. */
@Mixin(LightmapRenderStateExtractor.class)
public class GammaMixin {
    @Redirect(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object utilityclient$lightmapOption(OptionInstance<?> option) {
        return GameplayHooks.lightmapOption(option);
    }
}
