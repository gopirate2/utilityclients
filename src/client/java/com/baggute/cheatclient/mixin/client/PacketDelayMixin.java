package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.combat.BacktrackBuffer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class PacketDelayMixin {
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void utilityclient$delay(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (BacktrackBuffer.intercept(packet)) ci.cancel();
    }
    @Inject(method = {"handleEntityPositionSync", "handleTeleportEntity", "handleRemoveEntities"}, at = @At("HEAD"))
    private void utilityclient$flush(CallbackInfo ci) { BacktrackBuffer.flush(); }
}
