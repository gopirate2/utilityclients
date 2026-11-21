package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.combat.Velocity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Scale the actual vanilla impulse, after resistance, independently by axis. */
@Mixin(LivingEntity.class)
public class KnockbackMixin {
    @ModifyArgs(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"))
    private void utilityclient$knockback(Args args) {
        Entity self = (Entity) (Object) this;
        Vec3 base = self.getDeltaMovement();
        Vec3 result = new Vec3(args.<Double>get(0), args.<Double>get(1), args.<Double>get(2));
        Vec3 scaled = base.add(Velocity.apply(self, result.subtract(base)));
        args.set(0, scaled.x);
        args.set(1, scaled.y);
        args.set(2, scaled.z);
    }
}
