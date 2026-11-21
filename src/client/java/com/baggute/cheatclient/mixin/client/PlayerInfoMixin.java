// language: Java, file: PlayerInfoMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.misc.GamemodeDetector;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place the server states a player's gamemode.
 *
 * <p>{@code require = 0}: the detector also reconciles the tab list every tick, so
 * a mapping change that renames this method must not stop the client from booting.
 * A failed injection costs one tick of latency on the alert, not a launch crash.
 */
@Mixin(ClientPacketListener.class)
public class PlayerInfoMixin {

    @Inject(method = "handlePlayerInfoUpdate", at = @At("TAIL"), require = 0)
    private void utilityclient$playerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet,
                                                CallbackInfo ci) {
        GamemodeDetector.onPlayerInfoPacket();
    }
}
