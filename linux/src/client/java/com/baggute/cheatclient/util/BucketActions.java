package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Validates the ray used by vanilla BucketItem.use before spending a bucket. */
public final class BucketActions {
    private BucketActions() {}

    public static boolean use(Minecraft mc, BlockPos fluidCell, boolean pickup) {
        if (!Stealth.canInteract(mc) || fluidCell == null || !Stealth.inBlockReach(mc.player, fluidCell)) return false;
        var hand = mc.player.getMainHandItem();
        if (pickup) {
            if (!hand.is(Items.BUCKET) || !mc.level.getFluidState(fluidCell).isSource()) return false;
        } else {
            if ((!hand.is(Items.WATER_BUCKET) && !hand.is(Items.LAVA_BUCKET))
                    || !mc.level.getBlockState(fluidCell).canBeReplaced()
                    || !mc.level.getFluidState(fluidCell).isEmpty()) return false;
        }
        if (pickup) return aimAndUse(mc, fluidCell, fluidCell, Vec3.atCenterOf(fluidCell), true);
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            BlockPos clicked = fluidCell.relative(face.getOpposite());
            Vec3 point = Vec3.atCenterOf(clicked).add(face.getStepX() * .499,
                    face.getStepY() * .499, face.getStepZ() * .499);
            if (aimAndUse(mc, fluidCell, clicked, point, false)) return true;
        }
        return false;
    }

    private static boolean aimAndUse(Minecraft mc, BlockPos fluidCell, BlockPos clicked, Vec3 point, boolean pickup) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 delta = point.subtract(eye);
        if (delta.lengthSqr() < 1e-8) return false;
        var hit = mc.level.clip(new ClipContext(eye, point.add(delta.normalize().scale(0.05)),
                ClipContext.Block.OUTLINE, pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(clicked)
                || !Stealth.inBlockReach(mc.player, clicked)
                || eye.distanceTo(hit.getLocation()) > Stealth.blockReachLimit(mc.player) + 0.5) return false;
        if (!pickup && !clicked.relative(hit.getDirection()).equals(fluidCell)) return false;
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        var result = Stealth.useItemDirected(mc, InteractionHand.MAIN_HAND, yaw, pitch);
        return result != null && result.consumesAction();
    }
}
