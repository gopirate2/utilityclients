// language: Java, file: NoFall.java, target: Fabric 26.2
package com.baggute.cheatclient.module.movement;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fall-damage suppression by landing early, not by lying about the ground.
 *
 * <p>The old shape sent {@code onGround = true} from any airborne tick past two blocks
 * of fall. That packet is impossible: the server replays the player's own movement
 * between the last accepted position and the claim, finds no collision, and reads the
 * ground flag as a spoof. It also contradicts the position packets around it, so the
 * desync is visible without any fall tracking at all.
 *
 * <p>This version claims the ground only when the ground is genuinely reachable inside
 * one movement step. It walks the player's own vertical step for the tick and checks
 * whether a solid face sits inside it. When it does, the claim is the same one vanilla
 * would make on the landing tick - the server's own replay agrees with it, because it
 * is the truth. When it does not, nothing is sent and the fall continues; there is no
 * mid-air claim to catch.
 */
public class NoFall extends Module {
    private final DoubleSetting minFallDistance = new DoubleSetting("minFallDistance", "Minimum accumulated fall distance (blocks)", 2, 2, 20);

    /** Vanilla's own on-ground tolerance, from {@code Entity#move}. */
    private static final double GROUND_EPSILON = 0.03;

    /** Extra blocks of look-ahead, so a high-speed fall does not skip the landing tick. */
    private static final double STEP_SLACK = 0.05;

    public NoFall() {
        super("NoFall", "Prevents fall damage.", Category.MOVEMENT);
        addSetting(minFallDistance);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (mc.player.onGround()) return;
        if (mc.player.getAbilities().flying) return;
        if (mc.player.isFallFlying() || mc.player.isInWater() || mc.player.isInLava()) return;
        if (mc.player.getDeltaMovement().y >= 0) return;
        // Only once a real fall is building: one claim every airborne tick is a louder
        // signal than a few near the damage threshold.
        if (mc.player.fallDistance < minFallDistance.get()) return;

        double step = -mc.player.getDeltaMovement().y;
        double probe = Math.max(step, GROUND_EPSILON) + STEP_SLACK;

        if (!groundWithin(mc, probe)) return;

        // The ground is inside this tick's own step, so this is the landing tick. The
        // horizontal collision flag is reported as it is, not as a constant.
        mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(
                true, mc.player.horizontalCollision));
    }

    /**
     * Whether a solid top face sits within {@code probe} blocks below the player's feet.
     *
     * <p>Walks the actual ray rather than sampling one block position: a fall at speed
     * crosses a full block per tick, and a single {@code below()} lookup reads the
     * block the player has already left.
     */
    private boolean groundWithin(Minecraft mc, double probe) {
        if (mc.level == null || mc.player == null) return false;

        Vec3 from = mc.player.position();
        Vec3 to = from.add(0, -probe, 0);

        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;

        // Only a top face carries a player. A side hit means the ray clipped a block
        // the player is falling past, which is not ground.
        if (hit.getDirection() != net.minecraft.core.Direction.UP) return false;

        BlockPos pos = hit.getBlockPos();
        return mc.level.getBlockState(pos).isFaceSturdy(mc.level, pos, net.minecraft.core.Direction.UP);
    }

    @Override
    protected void onEnable() {
        UtilityClient.LOGGER.info("[NoFall] enabled (predicted landing)");
    }
    @Override
    protected void onDisable() {
        UtilityClient.LOGGER.info("[NoFall] disabled");
    }
}
