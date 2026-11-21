// language: Java, file: AutoAnchor.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Anchor cycle: place a respawn anchor at the target's feet, charge it with
 * glowstone, then detonate it by using the block again.
 *
 * <pre>
 *   PLACE   useItemOn respawn_anchor on the top face of the support
 *   CHARGE  useItemOn glowstone on the anchor's own top face
 *   BOOM    useItemOn the charged anchor with a non-glowstone item
 * </pre>
 *
 * <p>Each stage reads the block back before it acts. The old version advanced on the
 * strength of the previous stage having been <em>attempted</em>, so a refused
 * placement left an empty cell that the charge stage then filled with a loose
 * glowstone block - the anchor never existed and the explosion never happened.
 *
 * <p>Detonation is a block use, not an attack: a charged anchor explodes from
 * {@code useWithoutItem} in every dimension except the Nether, where it only sets the
 * respawn point. That dimension check is vanilla behaviour and is reported rather than
 * retried.
 */
public class AutoAnchor extends Module {

    private static final String ITEM_ANCHOR = "respawn_anchor";
    private static final String ITEM_GLOWSTONE = "glowstone";

    /** Ticks a single stage may spend waiting before the cycle gives up. */
    private static final int STAGE_TIMEOUT = 60;

    private final DoubleSetting range;
    private final IntSetting placeDelay;
    private final BoolSetting charge;
    private final BoolSetting explode;

    private Stage stage = Stage.IDLE;
    private int cooldown = 0;
    private int stageTicks = 0;
    private BlockPos anchorPos = null;

    private enum Stage { IDLE, PLACE, CHARGE, BOOM }

    public AutoAnchor() {
        super("AutoAnchor", "Places, charges and detonates respawn anchors.", Category.COMBAT);

        this.range = new DoubleSetting("range", "Target range", 4.0, 2.0, 6.0);
        this.placeDelay = new IntSetting("placeDelay", "Ticks between cycles", 6, 1, 30);
        this.charge = new BoolSetting("charge", "Charge with glowstone", true);
        this.explode = new BoolSetting("explode", "Detonate after charging", true);

        addSetting(range);
        addSetting(placeDelay);
        addSetting(charge);
        addSetting(explode);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;

        if (cooldown > 0) cooldown--;

        if (stage == Stage.IDLE) {
            begin(mc);
            return;
        }

        if (++stageTicks > STAGE_TIMEOUT) { reset(); return; }

        switch (stage) {
            case PLACE -> tickPlace(mc);
            case CHARGE -> tickCharge(mc);
            case BOOM -> tickBoom(mc);
            default -> { }
        }
    }

    /** Places the anchor itself. */
    private void tickPlace(Minecraft mc) {
        if (anchorPos == null) { reset(); return; }

        // Strict: empty cell, solid ground under it, anchor in the hotbar. A respawn
        // anchor placed over air is refused server-side, and the charge stage would then
        // be clicking a cell that holds nothing.
        if (!mc.level.getBlockState(anchorPos).canBeReplaced()) { reset(); return; }
        if (!Placement.supportIsSolid(mc, anchorPos)) { reset(); return; }
        if (!Placement.has(mc, ITEM_ANCHOR)) { reset(); return; }

        Placement.Result result = Placement.useOn(mc, ITEM_ANCHOR, anchorPos.below());
        if (result == Placement.Result.WAITING) return;
        if (result != Placement.Result.USED) { reset(); return; }

        if (!isAnchor(mc, anchorPos)) { reset(); return; }
        advance(charge.get() ? Stage.CHARGE : Stage.IDLE);
        if (!charge.get()) cooldown = placeDelay.get();
    }

    /** Charges the anchor with glowstone. */
    private void tickCharge(Minecraft mc) {
        if (anchorPos == null) { reset(); return; }
        if (!isAnchor(mc, anchorPos)) { reset(); return; }
        if (chargeLevel(mc, anchorPos) >= RespawnAnchorBlock.MAX_CHARGES) {
            advance(explode.get() ? Stage.BOOM : Stage.IDLE);
            return;
        }
        if (!Placement.has(mc, ITEM_GLOWSTONE)) { reset(); return; }
        // Charging uses the anchor itself, including anchors with a ceiling.
        Placement.Result result = Placement.useOn(mc, ITEM_GLOWSTONE, anchorPos);
        if (result == Placement.Result.WAITING) return;
        if (result != Placement.Result.USED) { reset(); return; }

        if (chargeLevel(mc, anchorPos) < 1) { reset(); return; }
        cooldown = placeDelay.get();
        advance(explode.get() ? Stage.BOOM : Stage.IDLE);
    }

    /** Detonates the charged anchor by using it. */
    private void tickBoom(Minecraft mc) {
        if (anchorPos == null) { reset(); return; }
        if (!isAnchor(mc, anchorPos)) { reset(); return; }
        if (chargeLevel(mc, anchorPos) < 1) { reset(); return; }

        // The anchor only detonates outside the Nether; there it sets the spawn point.
        if (mc.level.dimension() == Level.NETHER) { reset(); return; }

        // Glowstone in hand recharges instead of detonating, so any other slot will do.
        if (ModuleUtils.matches(mc.player.getMainHandItem(), ITEM_GLOWSTONE)) {
            int slot = nonGlowstoneSlot(mc);
            if (slot < 0) { reset(); return; }
            ModuleUtils.select(mc, slot);
            return;
        }

        if (!mc.player.isWithinBlockInteractionRange(anchorPos, 0.5)) { reset(); return; }

        // The click has to arrive on a ray that actually enters the anchor. A packet
        // built from a face the server cannot see is dropped, and the module used to
        // reset its stage as if the anchor had gone off.
        Direction face = Placement.facing(mc, anchorPos);
        if (face == null) {
            AimPipeline.rotateTo(mc, Placement.faceCentre(anchorPos, Direction.UP), 0.5, 1.0, true, 0.3);
            return;
        }

        var result = com.baggute.cheatclient.util.Stealth.useItemOn(mc, InteractionHand.MAIN_HAND,
                new BlockHitResult(Placement.faceCentre(anchorPos, face),
                        face, anchorPos, false));
        if (result == null || !result.consumesAction()) return;
        mc.player.swing(InteractionHand.MAIN_HAND);

        reset();
        cooldown = placeDelay.get();
    }

    /** Runs from IDLE: picks a cell at the target's feet and starts the cycle. */
    private void begin(Minecraft mc) {
        if (cooldown > 0 || stage != Stage.IDLE) return;
        if (!Placement.has(mc, ITEM_ANCHOR)) return;
        if (charge.get() && !Placement.has(mc, ITEM_GLOWSTONE)) return;

        // The anchor is placed at the target's feet, so the cycle needs block reach for
        // placement and the same reach for the detonation click.
        double reach = Math.min(range.get(), mc.player.blockInteractionRange() + 0.5);
        Player target = ModuleUtils.nearestPlayer(mc, reach);
        if (target == null) return;

        BlockPos candidate = target.blockPosition();
        if (candidate.distSqr(mc.player.blockPosition()) > 30.0) return;

        if (!mc.level.getBlockState(candidate).canBeReplaced()) {
            candidate = Placement.findSpot(mc, candidate, 2.0);
            if (candidate == null) return;
        }
        // A respawn anchor needs solid ground under it; the target's own feet block
        // passes the replaceable test but not this one.
        if (!Placement.supportIsSolid(mc, candidate)) {
            candidate = Placement.findSpot(mc, candidate, 2.0);
            if (candidate == null) return;
        }

        anchorPos = candidate;
        advance(Stage.PLACE);
    }

    private boolean isAnchor(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getBlock() instanceof RespawnAnchorBlock;
    }

    private int chargeLevel(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof RespawnAnchorBlock)) return 0;
        return state.getValue(RespawnAnchorBlock.CHARGE);
    }

    /** Any hotbar slot that is not glowstone, so BOOM does not recharge the anchor. */
    private int nonGlowstoneSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (!ModuleUtils.matches(mc.player.getInventory().getItem(i), ITEM_GLOWSTONE)) return i;
        }
        return -1;
    }

    private void advance(Stage next) {
        stage = next;
        stageTicks = 0;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoAnchor", next.name());
    }

    private void reset() {
        stage = Stage.IDLE;
        stageTicks = 0;
        anchorPos = null;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoAnchor", "IDLE");
    }

    @Override
    protected void onEnable() { reset(); cooldown = 0; }

    @Override
    protected void onDisable() { reset(); cooldown = 0; }
}
