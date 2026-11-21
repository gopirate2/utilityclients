// language: Java, file: VelocityMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.combat.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales knockback that arrives from the server.
 *
 * <p>In 26.2 a hurt knockback on the local player never calls
 * {@code LivingEntity#knockback} on the client: the server sends
 * {@code ClientboundSetEntityMotionPacket}, {@code ClientPacketListener#handleSetEntityMotion}
 * looks the entity up and calls {@code Entity#lerpMotion(Vec3)}, which is a one-line
 * {@code setDeltaMovement}. This mixin is therefore the primary path and
 * {@link KnockbackMixin} covers the local calculation (explosions, single-player
 * self-damage).
 *
 * <p>Only the local player is touched: every other entity's motion packet goes
 * through untouched so the rest of the level keeps vanilla physics.
 */
@Mixin(Entity.class)
public class VelocityMixin {

    @ModifyVariable(method = "lerpMotion", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 utilityclient$scaleMotion(Vec3 motion) {
        return motion == null ? null : Velocity.apply((Entity) (Object) this, motion);
    }
}
