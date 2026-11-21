// language: Java, file: WaterWalk.java, target: Fabric 26.2
package com.baggute.cheatclient.module.movement;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the player on the surface of water (and optionally lava).
 *
 * <p>The position is pinned to the fluid surface and vertical velocity is zeroed,
 * with {@code onGround} forced so vanilla does not apply gravity on the next tick.
 * The pin is skipped when the destination is occupied: the old version wrote the
 * surface Y unconditionally, so drifting under an overhang teleported the player
 * into a solid block and the server rubbed them back out — the classic "stuck and
 * shaking" water-walk bug.
 */
public class WaterWalk extends Module {

    /** Height of a full source block; flowing fluid is lower, but the pin only
     *  applies above a source, which is what a player can stand on. */
    private static final double SOURCE_SURFACE = 0.8888;
    /** A jump larger than this is a teleport or a dimension change, not a bob. */
    private static final double MAX_SNAP = 1.5;

    private final DoubleSetting hoverHeight;
    private final BoolSetting lavaToo;
    private final BoolSetting forceSprint;

    public WaterWalk() {
        super("WaterWalk", "Walk on top of water.", Category.MOVEMENT);
        this.hoverHeight = new DoubleSetting("hoverHeight", "Height above surface", 0.0, 0.0, 0.4);
        this.lavaToo     = new BoolSetting("lavaToo", "Also walk on lava", false);
        this.forceSprint = new BoolSetting("forceSprint", "Force sprint on water", true);
        addSetting(hoverHeight);
        addSetting(lavaToo);
        addSetting(forceSprint);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // A passenger's position belongs to the vehicle and creative flight has its
        // own physics; writing either one fights the game and desyncs the server.
        if (mc.player.isPassenger() || mc.player.isSpectator()) return;
        if (mc.player.getAbilities().flying) return;

        BlockPos fluid = fluidBlockUnder(mc);
        if (fluid == null) return;

        double surfaceY = fluid.getY() + mc.level.getFluidState(fluid).getHeight(mc.level, fluid) + hoverHeight.get();
        Vec3 pos = mc.player.position();
        if (Math.abs(pos.y - surfaceY) > MAX_SNAP) return; // teleport, not a bob

        // Never pin into a block: an overhang above the water is solid at head
        // height and the pin would put us inside it.
        BlockPos surfaceCell = BlockPos.containing(pos.x, surfaceY + 0.05, pos.z);
        BlockState surfaceState = mc.level.getBlockState(surfaceCell);
        if (!surfaceState.getCollisionShape(mc.level, surfaceCell).isEmpty()) return;

        if (pos.y != surfaceY) {
            mc.player.setPos(pos.x, surfaceY, pos.z);
        }

        Vec3 vel = mc.player.getDeltaMovement();
        if (vel.y != 0.0) {
            mc.player.setDeltaMovement(vel.x, 0.0, vel.z);
        }

        mc.player.setOnGround(true);
        mc.player.fallDistance = 0.0f;

        if (forceSprint.get() && !mc.player.isSprinting() && !mc.player.isShiftKeyDown()) {
            mc.player.setSprinting(true);
        }
    }

    /** Source block directly under the feet, or null when there is no fluid to stand on. */
    private BlockPos fluidBlockUnder(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        if (isSource(mc, feet)) return feet;
        BlockPos below = feet.below();
        return isSource(mc, below) ? below : null;
    }

    private boolean isSource(Minecraft mc, BlockPos pos) {
        var fluidState = mc.level.getBlockState(pos).getFluidState();
        if (fluidState.is(FluidTags.WATER)) return true;
        return lavaToo.get() && fluidState.is(FluidTags.LAVA);
    }

    @Override
    protected void onEnable() {
        UtilityClient.LOGGER.info("[WaterWalk] enabled");
    }

    @Override
    protected void onDisable() {
        UtilityClient.LOGGER.info("[WaterWalk] disabled");
    }
}
