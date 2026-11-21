// language: Java, file: GameModeAttackMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.module.combat.AntiMiss;
import com.baggute.cheatclient.module.combat.HitSelect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Lets AntiMiss drop useless attack packets without touching the input mapping. */
@Mixin(MultiPlayerGameMode.class)
public class GameModeAttackMixin {
    @WrapMethod(method = "releaseUsingItem")
    private void utilityclient$bowRelease(Player player, Operation<Void> original) {
        Object token = com.baggute.cheatclient.GameplayHooks.beginBowRelease(player);
        try { original.call(player); }
        finally { com.baggute.cheatclient.GameplayHooks.endBowRelease(player, token); }
    }

    @WrapMethod(method = "useItem")
    private InteractionResult utilityclient$localThrow(Player player, InteractionHand hand, Operation<InteractionResult> original) {
        Object snapshot = com.baggute.cheatclient.LateBridge.captureItemUse(player, hand);
        InteractionResult result = original.call(player, hand);
        return (InteractionResult) com.baggute.cheatclient.LateBridge.completeItemUse(result, snapshot);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void utilityclient$antiMiss(Player player, Entity target, CallbackInfo ci) {
        if (AntiMiss.shouldBlock(player, target)) ci.cancel();
        if (HitSelect.shouldBlock(Minecraft.getInstance())) ci.cancel();
    }
    @Inject(method = "attack", at = @At("TAIL"))
    private void utilityclient$afterAttack(Player player, Entity target, CallbackInfo ci) {
        com.baggute.cheatclient.GameplayHooks.afterAttack(player, target);
    }
}
