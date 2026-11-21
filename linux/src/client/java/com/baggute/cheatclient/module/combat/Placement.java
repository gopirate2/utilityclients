// language: Java, file: Placement.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One placement path for every automation module.
 *
 * <p>The result is deliberately multi-state. "I selected the item, try again next
 * tick" is NOT the same as "placed" — collapsing the two into a boolean made the
 * callers advance their stage without ever placing anything.
 *
 * <p>The server validates every use-on-block packet with
 * {@code isWithinBlockInteractionRange(pos, 1.0)} and drops it silently when the
 * block is too far, so placements are range-checked here first and the
 * interaction result is actually inspected.
 */
public final class Placement {

    private Placement() {}

    public enum Result {
        /** The item was used on the block this tick. */
        USED,
        /** The right item was selected but is not in hand yet; retry next tick. */
        WAITING,
        /** The item is not in the hotbar at all. */
        MISSING,
        /** The block is beyond interaction range; the server would drop the packet. */
        OUT_OF_RANGE,
        /** The click went out but the game refused it (wrong face, occupied block, ...). */
        FAILED
    }

    /**
     * Nearest block position around {@code center} that can take a block with solid
     * ground beneath it and is actually reachable from the player's eyes.
     */
    public static BlockPos findSpot(Minecraft mc, BlockPos center, double range) {
        return findSpot(mc, center, range, null);
    }

    /**
     * Same as {@link #findSpot(Minecraft, BlockPos, double)}, skipping {@code avoid}.
     * Callers use it to stop re-picking a cell the server already refused, which is
     * how a failed placement used to become an infinite retry loop.
     */
    public static BlockPos findSpot(Minecraft mc, BlockPos center, double range, BlockPos avoid) {
        if (mc.level == null || mc.player == null) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (avoid != null && p.equals(avoid)) continue;
                    if (!mc.level.getBlockState(p).canBeReplaced()) continue;
                    if (!mc.level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(p),
                            entity -> !entity.isSpectator() && entity.blocksBuilding).isEmpty()) continue;
                    if (!supportIsSolid(mc, p)) continue;
                    if (!inRange(mc, p)) continue;
                    double dist = p.distSqr(center);
                    if (dist > range * range) continue;
                    if (dist < bestDist) { bestDist = dist; best = p; }
                }
            }
        }
        return best;
    }

    /**
     * Whether the block under {@code cell} can carry a placed block.
     *
     * <p>{@code canBeReplaced()} is not enough: farmland, soul sand, ice and
     * half-slabs all accept being replaced-from and none of them supports a rail,
     * so the placement failed server-side every tick while the module kept asking.
     */
    public static boolean supportIsSolid(Minecraft mc, BlockPos cell) {
        if (mc.level == null) return false;
        BlockPos support = cell.below();
        return mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP);
    }

    /** Server-side reach test, so a stage never advances on a packet the server drops. */
    private static boolean inRange(Minecraft mc, BlockPos pos) {
        // The server allows blockInteractionRange + 1.0; keep a small margin so a
        // ping spike between the check and the packet cannot reject the click.
        return Stealth.inBlockReach(mc.player, pos);
    }

    /**
     * Nearest cell whose block above is a legal crystal volume: replaceable, with
     * solid ground below, inside reach, and with no entity standing in the
     * two-block space the crystal needs. EndCrystalItem refuses to spawn when an
     * entity (the target) overlaps that volume, which is why placing the base in
     * the target's own feet block never produced a crystal.
     */
    public static BlockPos findCrystalBase(Minecraft mc, BlockPos center, double range) {
        if (mc.level == null || mc.player == null) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!mc.level.getBlockState(p).canBeReplaced()) continue;
                    if (mc.level.getBlockState(p.below()).canBeReplaced()) continue;
                    if (!inRange(mc, p)) continue;
                    net.minecraft.world.phys.AABB volume = new net.minecraft.world.phys.AABB(
                            p.getX(), p.getY() + 1, p.getZ(),
                            p.getX() + 1, p.getY() + 3, p.getZ() + 1);
                    if (!mc.level.getEntities((Entity) null, volume).isEmpty()) continue;
                    double dist = p.distSqr(center);
                    if (dist > range * range) continue;
                    if (dist < bestDist) { bestDist = dist; best = p; }
                }
            }
        }
        return best;
    }

    /** Existing respawn anchor near {@code center}, or null. */
    public static BlockPos findAnchor(Minecraft mc, BlockPos center, int radius) {
        if (mc.level == null) return null;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -1, -radius), center.offset(radius, 1, radius))) {
            if (mc.level.getBlockState(pos).getBlock()
                    instanceof net.minecraft.world.level.block.RespawnAnchorBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** Uses {@code itemName} on the top face of {@code support}. */
    public static Result useOn(Minecraft mc, String itemName, BlockPos support) {
        return useOnFace(mc, itemName, support, Direction.UP);
    }

    /** Uses the block hit carried by the interaction without steering the camera. */
    public static Result useOnSilent(Minecraft mc, String itemName, BlockPos support) {
        return useOnFace(mc, itemName, support, Direction.UP, true);
    }

    /**
     * Uses {@code itemName} on a specific face of {@code support}.
     *
     * <p>The hit vector is the centre of that face and the face normal travels with
     * the packet, because the server validates the click against the block's own
     * geometry: a {@code BlockHitResult} whose location sits inside the block, or whose
     * face disagrees with where the ray entered, is rejected as a reach violation and
     * the placement silently does nothing.
     *
     * <p>Strict by construction: the destination cell must be replaceable, the support
     * must be sturdy, the click must be inside interaction range, and the item must
     * actually be in hand - the swap returns {@link Result#WAITING} so the caller spends
     * a tick on the carried-item packet instead of sending a use packet for an item the
     * server does not think is held.
     */
    public static Result useOnFace(Minecraft mc, String itemName, BlockPos support, Direction face) {
        return useOnFace(mc, itemName, support, face, false);
    }

    private static Result useOnFace(Minecraft mc, String itemName, BlockPos support, Direction face, boolean silent) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || support == null || face == null) {
            return Result.MISSING;
        }
        if (!inRange(mc, support)) return Result.OUT_OF_RANGE;

        BlockPos cell = support.relative(face);
        boolean cart = itemName.endsWith("minecart");
        if (cart) {
            if (!(mc.level.getBlockState(support).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock)) return Result.FAILED;
        } else if (!(mc.level.getBlockState(support).getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                    && "glowstone".equals(itemName)) && ModuleUtils.findHotbarItem(mc, itemName) >= 0
                && mc.player.getInventory().getItem(ModuleUtils.findHotbarItem(mc, itemName)).getItem()
                instanceof net.minecraft.world.item.BlockItem) {
            if (!mc.level.getBlockState(cell).canBeReplaced()) return Result.FAILED;
            // Rails require a sturdy top; ordinary blocks use vanilla's own
            // placement rules and can attach to many other block shapes.
            if (itemName.contains("rail") && !mc.level.getBlockState(support).isFaceSturdy(mc.level, support, face)) return Result.FAILED;
        }

        if (!ModuleUtils.matches(mc.player.getMainHandItem(), itemName)) {
            int slot = ModuleUtils.findHotbarItem(mc, itemName);
            if (slot < 0) return Result.MISSING;
            ModuleUtils.select(mc, slot);
            return Result.WAITING;
        }

        Vec3 hit = faceCentre(support, face);

        // The server resolves a use-on-block against the player's own look ray: the
        // block it would have hit, and the face that ray entered. A packet whose hit
        // vector and face disagree with the reported rotation is rejected, so the
        // rotation is checked before the packet rather than assumed. When the ray
        // misses, the module spends this tick turning and tries again next tick -
        // which is why the answer is WAITING and not FAILED.
        if (!silent && !rayIntersects(mc, support, face)) {
            AimPipeline.rotateTo(mc, hit, 0.5, 1.0, true, 0.3);
            return Result.WAITING;
        }

        if (!Stealth.canInteract(mc)) return Result.WAITING;
        InteractionResult result = com.baggute.cheatclient.util.Stealth.useItemOn(mc, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, support, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
        return result != null && result.consumesAction() ? Result.USED : Result.FAILED;
    }

    /**
     * Whether the player's view ray, extended to block interaction range, enters
     * {@code support} through {@code face}.
     *
     * <p>Vanilla's own pick is not reused: it is computed once per frame against the
     * camera the renderer had, and a module that rotates on the tick clock would be
     * testing the previous frame's ray. This walks the ray the server will validate
     * against.
     */
    public static boolean rayIntersects(Minecraft mc, BlockPos support, Direction face) {
        if (mc.player == null || mc.level == null) return false;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getViewVector(1.0f);
        double reach = Stealth.blockReachLimit(mc.player) + Stealth.BLOCK_REACH_BUFFER;

        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        return hit.getBlockPos().equals(support) && hit.getDirection() == face;
    }

    /**
     * Centre of one face of a block, in world space.
     *
     * <p>A face centre rather than the block centre: for the top face they are the same
     * point, for any other face the block centre is <em>inside</em> the block, which is
     * exactly the geometry the server rejects.
     */
    public static Vec3 faceCentre(BlockPos pos, Direction face) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        double push = 0.5 - 1.0E-4;
        return new Vec3(
                x + face.getStepX() * push,
                y + face.getStepY() * push,
                z + face.getStepZ() * push);
    }

    /**
     * Face of {@code pos} that the player's own look ray enters, or null when the ray
     * does not land on that block.
     *
     * <p>Vanilla's pick is a frame-old sample; this walks the ray the server will
     * validate the use packet against, so a module that rotates on the tick clock
     * gets the right answer for the tick it is on.
     */
    public static Direction facing(Minecraft mc, BlockPos pos) {
        if (mc.player == null || mc.level == null || pos == null) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getViewVector(1.0f);
        double reach = Stealth.blockReachLimit(mc.player) + Stealth.BLOCK_REACH_BUFFER;

        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        return hit.getBlockPos().equals(pos) ? hit.getDirection() : null;
    }

    /** True when the hotbar holds an item whose registry path matches {@code itemName}. */
    public static boolean has(Minecraft mc, String itemName) {
        return ModuleUtils.findHotbarItem(mc, itemName) >= 0;
    }

    /**
     * Uses the held item directly on an entity (flint on a TNT minecart, a bucket on
     * a cow, ...). Entity packets are resolved by id instead of by look ray, so no
     * rotation packet has to win a race with this one.
     */
    public static boolean useOnEntity(Minecraft mc, Entity entity) {
        if (mc.player == null || mc.gameMode == null || entity == null) return false;
        // Same limit the arbiter enforces for an attack: the player's own interaction
        // range plus half a block of latency slack. The old 2.5 was a flat margin on
        // top of the range rather than inside it.
        if (!Stealth.inEntityReach(mc.player, entity)) return false;
        // A carried-item packet this tick means the server's held item is still the
        // previous one; the interaction would resolve against the wrong stack.
        if (!Stealth.canInteract(mc)) return false;

        Vec3 point = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
        InteractionResult result = Stealth.interact(mc, entity, new EntityHitResult(entity, point),
                InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return result != null && result.consumesAction();
    }
}
