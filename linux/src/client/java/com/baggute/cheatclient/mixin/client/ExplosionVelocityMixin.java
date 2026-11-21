package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.combat.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.Optional;

/** Explosion knockback is an additive impulse, not an entity motion packet. */
@Mixin(ClientPacketListener.class)
public class ExplosionVelocityMixin {
    @Redirect(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
    private Optional<Vec3> utilityclient$explosionImpulse(ClientboundExplodePacket packet) {
        return packet.playerKnockback().map(v -> Velocity.apply(Minecraft.getInstance().player, v));
    }
}
