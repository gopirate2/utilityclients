// language: Java, file: AutoCrystal.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Placed-base crystal cycle: obsidian under the target, a crystal on it, then a
 * detonation by attacking the crystal entity.
 *
 * <pre>
 *   BASE     useItemOn obsidian on the top face of the support block
 *   CRYSTAL  useItemOn end_crystal on the top face of the obsidian
 *   BOOM     gameMode.attack on the crystal entity
 * </pre>
 *
 * <p>One action per tick. The carried-item packet has to be acknowledged before the
 * use packet, and {@code Placement.Result.WAITING} is what buys that tick - the old
 * code collapsed "swapped the item" and "placed the block" into one boolean and
 * advanced the stage without a block ever existing.
 *
 * <p>Every stage re-validates the world before it sends anything. A stage that trusts
 * its predecessor is a stage that sends a use packet into a cell the server already
 * refused, which is the other half of why this module appeared to do nothing: the
 * packets went out, the server dropped them, and the state machine marched on.
 */
public class AutoCrystal extends Module {

    private static final String ITEM_OBSIDIAN = "obsidian";
    private static final String ITEM_CRYSTAL = "end_crystal";
    private static final String ENTITY_CRYSTAL = "end_crystal";

    /** Ticks a single stage may spend waiting before the cycle gives up. */
    private static final int STAGE_TIMEOUT = 40;
    /** Ticks to wait for the server to spawn the crystal entity after the placement. */
    private static final int SPAWN_TIMEOUT = 20;

    private final DoubleSetting range;
    private final IntSetting placeDelay;
    private final BoolSetting detonate;
    private final BoolSetting onlyWhileClicking;

    private Stage stage = Stage.IDLE;
    private int cooldown = 0;
    private int stageTicks = 0;
    private BlockPos basePos = null;

    private enum Stage { IDLE, BASE, CRYSTAL, BOOM }

    public AutoCrystal() {
        super("AutoCrystal", "Places obsidian, spawns a crystal and detonates it.", Category.COMBAT);

        this.range = new DoubleSetting("range", "Target range", 4.5, 2.0, 6.0);
        this.placeDelay = new IntSetting("placeDelay", "Ticks between cycles", 4, 1, 20);
        this.detonate = new BoolSetting("detonate", "Detonate the crystal", true);
        this.onlyWhileClicking = new BoolSetting("onlyWhileClicking", "Only run while left-click held", false);

        addSetting(range);
        addSetting(placeDelay);
        addSetting(detonate);
        addSetting(onlyWhileClicking);
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
            case BASE -> tickBase(mc);
            case CRYSTAL -> tickCrystal(mc);
            case BOOM -> tickBoom(mc);
            default -> { }
        }
    }

    /** Places the obsidian the crystal will stand on. */
    private void tickBase(Minecraft mc) {
        if (basePos == null) { reset(); return; }

        // Strict: the cell must still be empty and the ground under it still solid, or
        // the use packet goes to a cell the server refuses and the stage lies about it.
        if (!mc.level.getBlockState(basePos).canBeReplaced()) { reset(); return; }
        if (!Placement.supportIsSolid(mc, basePos)) { reset(); return; }
        if (!Placement.has(mc, ITEM_OBSIDIAN)) { reset(); return; }

        Placement.Result result = Placement.useOn(mc, ITEM_OBSIDIAN, basePos.below());
        if (result == Placement.Result.WAITING) return;
        if (result != Placement.Result.USED) { reset(); return; }

        // The block has to exist before the crystal stage can click it.
        if (!mc.level.getBlockState(basePos).is(Blocks.OBSIDIAN)) { reset(); return; }
        advance(Stage.CRYSTAL);
    }

    /** Spawns the crystal on the obsidian. */
    private void tickCrystal(Minecraft mc) {
        if (basePos == null) { reset(); return; }

        // Strict: EndCrystalItem refuses anything that is not obsidian or bedrock, and
        // it refuses a volume that already holds an entity. Both are checked here so a
        // failed cycle does not keep clicking for forty ticks.
        if (!mc.level.getBlockState(basePos).is(Blocks.OBSIDIAN)) { reset(); return; }
        if (!mc.level.getBlockState(basePos.above()).canBeReplaced()) { reset(); return; }
        if (!Placement.has(mc, ITEM_CRYSTAL)) { reset(); return; }

        Placement.Result result = Placement.useOn(mc, ITEM_CRYSTAL, basePos);
        if (result == Placement.Result.WAITING) return;
        if (result != Placement.Result.USED) { reset(); return; }

        cooldown = placeDelay.get();
        if (!detonate.get()) { reset(); return; }
        advance(Stage.BOOM);
    }

    /** Detonates: the crystal entity is attacked, which is the vanilla trigger. */
    private void tickBoom(Minecraft mc) {
        Entity crystal = findCrystal(mc);
        if (crystal == null) {
            // The entity only exists after the server's add-entity packet. Wait for it,
            // but not forever: a placement the server refused never produces one.
            if (stageTicks > SPAWN_TIMEOUT) reset();
            return;
        }
        // Eye-to-box against the arbiter's limit: the same measure the server resolves.
        if (!Stealth.inEntityReach(mc.player, crystal)) { reset(); return; }

        if (!Stealth.attack(mc, crystal)) return;
        reset();
        cooldown = placeDelay.get();
    }

    /** Runs from IDLE: picks the base spot and starts the cycle. */
    private void begin(Minecraft mc) {
        if (cooldown > 0 || stage != Stage.IDLE) return;
        if (onlyWhileClicking.get() && !mc.options.keyAttack.isDown()) return;

        // Item validation first: no cycle is armed without both halves of the kit.
        if (!Placement.has(mc, ITEM_OBSIDIAN)) return;
        if (!Placement.has(mc, ITEM_CRYSTAL)) return;

        // The cycle ends by attacking the crystal, so only start inside entity reach.
        double reach = Math.min(range.get(), mc.player.entityInteractionRange() + 2.5);
        Player target = ModuleUtils.nearestPlayer(mc, reach);
        if (target == null) return;

        // The block the target stands in is not usable: the crystal volume above the
        // obsidian would contain the target and EndCrystalItem refuses to spawn.
        BlockPos base = Placement.findCrystalBase(mc, target.blockPosition(), 2.0);
        if (base == null) return;

        basePos = base;
        advance(Stage.BASE);
    }

    /**
     * The crystal standing on {@code basePos}, or null.
     *
     * <p>Matched by entity type and by distance to the cell the crystal was placed in,
     * not by display name: a dead crystal stays in the render list for a tick and an
     * attack against it is dropped, which used to stall the cycle on a ghost.
     */
    private Entity findCrystal(Minecraft mc) {
        if (basePos == null || mc.level == null) return null;
        Vec3 centre = new Vec3(basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5);

        Entity best = null;
        double bestDistance = 4.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.isAlive()) continue;
            if (!entity.getType().getDescriptionId().toLowerCase().contains(ENTITY_CRYSTAL)) continue;
            double distance = entity.position().distanceToSqr(centre);
            if (distance > bestDistance) continue;
            bestDistance = distance;
            best = entity;
        }
        return best;
    }

    private void advance(Stage next) {
        stage = next;
        stageTicks = 0;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoCrystal", next.name());
    }

    private void reset() {
        stage = Stage.IDLE;
        stageTicks = 0;
        basePos = null;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoCrystal", "IDLE");
    }

    @Override
    protected void onEnable() { reset(); cooldown = 0; }

    @Override
    protected void onDisable() { reset(); cooldown = 0; }
}
