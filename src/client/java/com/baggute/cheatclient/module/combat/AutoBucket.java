package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Steals water an opponent placed around themselves.
 *
 * <p>The bucket is put back once nothing is left to pick up. The old version
 * selected it and never returned, which left the player holding an empty bucket
 * for the rest of the fight.
 */
public final class AutoBucket extends Module {

    private final BoolSetting autoSwap = new BoolSetting("autoSwap", "Switch to an empty bucket", true);
    private final IntSetting delay = new IntSetting("delay", "Ticks between steals", 3, 1, 20);

    private int cooldown;
    private int oldSlot = -1;
    private int ownedSlot = -1;
    private net.minecraft.client.player.LocalPlayer owner;

    public AutoBucket() {
        super("AutoBucket", "Steals water placed in an opponent's immediate area.", Category.COMBAT);
        addSetting(autoSwap);
        addSetting(delay);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) { oldSlot = -1; return; }
        if (oldSlot >= 0 && (owner != mc.player
                || mc.player.getInventory().getSelectedSlot() != ownedSlot)) {
            oldSlot = ownedSlot = -1; owner = null;
            return;
        }
        if (cooldown > 0) cooldown--;

        var opponent = ModuleUtils.nearestPlayer(mc, 6.0);
        BlockPos water = opponent == null || opponent == mc.player
                || opponent.distanceToSqr(mc.player) < 4.0
                ? null : findOpponentWater(mc, opponent.blockPosition());

        if (water == null) {
            restore(mc);
            return;
        }
        if (cooldown > 0) return;

        // The server drops use-on-block packets outside block reach without notice.
        if (!mc.player.isWithinBlockInteractionRange(water, 0.5)) return;

        // A bucket pickup is a use-on-block: the server resolves it against the ray the
        // player reported. Centre-of-block is inside the fluid and the UP normal is a
        // guess, so the face comes off the ray instead.
        int slot = findEmptyBucket(mc);
        if (slot < 0) { restore(mc); return; }

        if (!isEmptyBucket(mc.player.getMainHandItem())) {
            if (!autoSwap.get()) return;
            int previous = mc.player.getInventory().getSelectedSlot();
            if (!com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot)) return;
            if (oldSlot < 0) oldSlot = previous;
            ownedSlot = slot; owner = mc.player;
            return; // the slot packet lands next tick
        }

        var eye = mc.player.getEyePosition();
        var point = net.minecraft.world.phys.Vec3.atCenterOf(water);
        var delta = point.subtract(eye);
        var picked = mc.level.clip(new net.minecraft.world.level.ClipContext(eye,
                point.add(delta.normalize().scale(0.5)), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, mc.player));
        if (!picked.getBlockPos().equals(water)) return;
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        // Buckets implement use(), not useOn(); the fluid pick must include sources.
        var result = com.baggute.cheatclient.util.Stealth.useItemDirected(mc, InteractionHand.MAIN_HAND, yaw, pitch);
        if (result != null && result.consumesAction()) cooldown = delay.get();
    }

    private void restore(Minecraft mc) {
        if (oldSlot >= 0 && owner == mc.player && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot
                && ownedSlot != oldSlot) {
            com.baggute.cheatclient.util.Stealth.restoreSlot(mc, oldSlot);
        }
        oldSlot = ownedSlot = -1; owner = null;
    }

    private BlockPos findOpponentWater(Minecraft mc, BlockPos center) {
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!mc.level.getBlockState(pos).is(Blocks.WATER)
                            || !mc.level.getFluidState(pos).isSource()) continue;
                    if (pos.distSqr(mc.player.blockPosition()) <= 4.0) continue;
                    if (!mc.player.isWithinBlockInteractionRange(pos, 0.5)) continue;
                    return pos;
                }
            }
        }
        return null;
    }

    private int findEmptyBucket(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isEmptyBucket(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    // Exact item only: milk/fish buckets also match the "bucket" substring and do
    // the wrong thing on water.
    private boolean isEmptyBucket(ItemStack stack) {
        return stack.is(Items.BUCKET);
    }

    @Override
    protected void onDisable() {
        restore(Minecraft.getInstance());
        cooldown = 0;
    }
}
