package kaptainwutax.seedcrackerX.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "TAIL"))
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        kaptainwutax.seedcrackerX.SeedRuntime.chunk(packet);
    }

    @Inject(method = "handleLogin", at = @At(value = "TAIL"))
    public void onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        kaptainwutax.seedcrackerX.SeedRuntime.login(packet.commonPlayerSpawnInfo().seed());
    }

    @Inject(method = "handleRespawn", at = @At(value = "TAIL"))
    public void onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        kaptainwutax.seedcrackerX.SeedRuntime.dimension(packet.commonPlayerSpawnInfo().seed(), true);
    }

}
