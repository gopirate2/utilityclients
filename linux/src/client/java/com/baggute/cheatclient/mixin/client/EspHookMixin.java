// language: Java, file: EspHookMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.render.BlockEspExtractor;
import com.baggute.cheatclient.render.EspExtractor;
import com.baggute.cheatclient.render.StorageEspExtractor;
import com.baggute.cheatclient.render.IndicatorExtractor;
import com.baggute.cheatclient.render.NametagRenderer;
import com.baggute.cheatclient.render.VanishExtractor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class EspHookMixin {

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void utilityclient$onSubmitFeatures(LevelRenderState levelRenderState,
                                  SubmitNodeCollector submitNodeCollector,
                                  boolean renderOutline,
                                  CallbackInfo ci) {
        // In-world ESP is the loudest thing on a shared screen; when capture
        // exclusion is unavailable StreamProof suppresses it entirely.
        if (com.baggute.cheatclient.module.misc.StreamProof.hidingFromCapture()) return;
        if (com.baggute.cheatclient.stealth.ScreenBypass.worldHidden()) return;
        EspExtractor.emitGizmos(levelRenderState);
        BlockEspExtractor.emitGizmos();
        StorageEspExtractor.emitGizmos();
        IndicatorExtractor.emit(levelRenderState);
        NametagRenderer.emit(levelRenderState);
        VanishExtractor.emitGizmos(levelRenderState);
        com.baggute.cheatclient.render.ApexRender.emit(levelRenderState);
    }
}
