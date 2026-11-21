// language: Java, file: Stealth.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tick-phase arbiter for every module that touches the wire.
 *
 * <p>Three checks that used to live in each module separately now live here, so a
 * module cannot be written that violates them:
 *
 * <ul>
 *   <li><b>Phase separation.</b> A rotation packet and an interaction packet in the
 *       same tick is the shape a combat check looks for: the server sees a view that
 *       snaps onto the target and a hit resolved against it inside one 50 ms slice.
 *       Real hands rotate, then click. {@link #canAttack} refuses while
 *       {@link #rotatedThisTick()} is set, so the attack lands on the next tick.</li>
 *   <li><b>Reach.</b> {@link #reachLimit} is derived from the player's own
 *       {@code entityInteractionRange()}, not from a slider. The buffer is capped at
 *       {@link #REACH_BUFFER} blocks, which is inside the server's own tolerance and
 *       absorbs the ping spike between the check and the packet.</li>
 *   <li><b>Charge.</b> {@link #strengthReady} is the 0.95 floor. Anything lower is a
 *       hit the server resolves with a reduced damage multiplier and flags as an
 *       early swing.</li>
 * </ul>
 *
 * <p>{@link #attack} additionally guarantees at most one attack packet per tick. A
 * second attempt in the same tick is turned into a swing animation, which is what a
 * player holding a high click rate actually produces.
 */
public final class Stealth {

    /** Blocks of slack on top of {@code entityInteractionRange()}. */
    public static final double REACH_BUFFER = 0.5;

    /** Blocks of slack on top of {@code blockInteractionRange()}. */
    public static final double BLOCK_REACH_BUFFER = 0.5;

    /** Minimum attack charge. Below this the server sees an early swing. */
    public static final float MIN_ATTACK_STRENGTH = 0.95f;

    private Stealth() {}

    // ---- tick phase ----

    private static int tick;
    private static boolean rotatedThisTick;
    private static boolean attackedThisTick;
    private static boolean swappedThisTick;
    private static boolean interactedThisTick;
    private static LocalPlayer sessionPlayer;
    private static int restoreSlot = -1;
    private static int restoreExpected = -1;
    private static int lastRotationTick = -1;
    private static int lastAttackTick = -1;
    private static int lastSwapTick = -1;
    private static Exchange exchange;
    private static boolean processingExchange;
    private static final class Exchange {
        final LocalPlayer player;
        final int source, destination;
        net.minecraft.world.item.ItemStack expectedSource, expectedDestination, expectedCursor;
        int phase, ticks;
        Exchange(LocalPlayer player, int source, int destination) {
            this.player = player;
            this.source = source;
            this.destination = destination;
            capture();
        }
        void capture() {
            expectedSource = player.inventoryMenu.getSlot(source).getItem().copy();
            expectedDestination = player.inventoryMenu.getSlot(destination).getItem().copy();
            expectedCursor = player.inventoryMenu.getCarried().copy();
        }
    }

    /** Advances the clock and clears the per-tick flags. Called once per client tick. */
    public static void beginTick() {
        LocalPlayer current = Minecraft.getInstance().player;
        if (current != sessionPlayer) {
            reset();
            sessionPlayer = current;
        }
        tick++;
        rotatedThisTick = false;
        attackedThisTick = false;
        swappedThisTick = false;
        interactedThisTick = false;
        advanceExchange(Minecraft.getInstance());
        if (restoreSlot >= 0 && current != null) {
            int slot = restoreSlot;
            if (current.getInventory().getSelectedSlot() != restoreExpected
                    || swapSlot(Minecraft.getInstance(), slot)) restoreSlot = -1;
        }
    }

    /** Monotonic client tick counter, starting at 1 on the first tick after load. */
    public static int tick() {
        return tick;
    }

    public static boolean rotatedThisTick() { return rotatedThisTick; }
    public static boolean attackedThisTick() { return attackedThisTick; }
    public static boolean swappedThisTick() { return swappedThisTick; }

    /** Ticks since the last rotation packet; {@link Integer#MAX_VALUE} if none yet. */
    public static int ticksSinceRotation() {
        return lastRotationTick < 0 ? Integer.MAX_VALUE : tick - lastRotationTick;
    }

    /** Ticks since the last attack packet; {@link Integer#MAX_VALUE} if none yet. */
    public static int ticksSinceAttack() {
        return lastAttackTick < 0 ? Integer.MAX_VALUE : tick - lastAttackTick;
    }

    public static int ticksSinceSwap() {
        return lastSwapTick < 0 ? Integer.MAX_VALUE : tick - lastSwapTick;
    }

    /** Marks that this tick's rotation went out. Blocks any attack in the same tick. */
    public static void markRotation() {
        rotatedThisTick = true;
        lastRotationTick = tick;
        com.baggute.cheatclient.module.misc.ActionTiming.consumed();
    }

    private static void markAttack() {
        attackedThisTick = true;
        lastAttackTick = tick;
        com.baggute.cheatclient.module.misc.ActionTiming.consumed();
    }

    private static void markSwap() {
        swappedThisTick = true;
        lastSwapTick = tick;
        com.baggute.cheatclient.module.misc.ActionTiming.consumed();
    }

    /** Clears the clock. Called when the client disconnects so ticks do not leak. */
    public static void reset() {
        tick = 0;
        rotatedThisTick = false;
        attackedThisTick = false;
        swappedThisTick = false;
        lastRotationTick = -1;
        lastAttackTick = -1;
        lastSwapTick = -1;
        restoreSlot = -1;
        restoreExpected = -1;
        exchange = null;
        processingExchange = false;
        sessionPlayer = null;
        interactedThisTick = false;
        com.baggute.cheatclient.module.combat.AimPipeline.reset();
        com.baggute.cheatclient.module.misc.ActionTiming.reset();
    }

    // ---- reach ----

    /** Longest distance at which an entity attack is still accepted by the server. */
    public static double reachLimit(Player player) {
        return player == null ? 3.0 : player.entityInteractionRange() + REACH_BUFFER;
    }

    /** Longest distance at which a use-on-block is still accepted by the server. */
    public static double blockReachLimit(Player player) {
        return player == null ? 4.5 : player.blockInteractionRange();
    }

    /**
     * Distance from the eye to the closest point on an entity's hitbox.
     *
     * <p>The eye-to-box measure, not {@code distanceTo}: centre distance understates
     * the real gap on a wide entity and overstates it on a tall one, and the server
     * measures the box.
     */
    public static double eyeToHitbox(Vec3 eye, AABB box) {
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean inEntityReach(Player player, Entity target) {
        if (player == null || target == null) return false;
        return eyeToHitbox(player.getEyePosition(), target.getBoundingBox()) <= reachLimit(player);
    }

    public static boolean inBlockReach(Player player, BlockPos pos) {
        if (player == null || pos == null) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return eye.distanceTo(centre) <= blockReachLimit(player) + BLOCK_REACH_BUFFER;
    }

    // ---- charge ----

    /** Attack charge sampled at vanilla's 0.5 partial tick. */
    public static float charge(Player player) {
        return player == null ? 0.0f : player.getAttackStrengthScale(0.5f);
    }

    /** True when the held item is charged enough to swing without a damage penalty. */
    public static boolean strengthReady(Player player) {
        return player != null && charge(player) >= MIN_ATTACK_STRENGTH;
    }

    // ---- arbitration ----

    /**
     * Whether an attack can go out on this tick.
     *
     * <p>False means "not yet", never "never": the caller leaves its target selected
     * and asks again next tick. Every refusal reason is a one-tick deferral except
     * reach, which is a real reject.
     */
    public static boolean canAttack(Minecraft mc, Entity target) {
        if (mc == null || mc.player == null || mc.gameMode == null) return false;
        if (!validTarget(mc.player, target) || !canInteract(mc)) return false;
        if (com.baggute.cheatclient.module.combat.AntiMiss.shouldBlock(mc.player, target)
                || com.baggute.cheatclient.module.combat.HitSelect.shouldBlock(mc)) return false;
        if (!strengthReady(mc.player)) return false;
        return inEntityReach(mc.player, target);
    }

    /**
     * Sends one attack packet, or a swing when this tick's packet is already spent.
     *
     * @return true when a real attack went out
     */
    public static boolean attack(Minecraft mc, Entity target) {
        return attack(mc, target, true);
    }

    /**
     * Same as {@link #attack(Minecraft, Entity)} with the animation suppressed.
     *
     * @param swingAnimation false to send the interaction without the arm swing
     */
    public static boolean attack(Minecraft mc, Entity target, boolean swingAnimation) {
        return fire(mc, target, swingAnimation, MIN_ATTACK_STRENGTH);
    }

    /** Sends a burst attack while retaining target, reach and per-tick arbitration. */
    public static boolean attackUncharged(Minecraft mc, Entity target, boolean swingAnimation) {
        return fire(mc, target, swingAnimation, 0.0f);
    }

    public static boolean attack(Minecraft mc, Entity target, boolean swingAnimation, float minimumCharge) {
        if (!Float.isFinite(minimumCharge)) return false;
        return fire(mc, target, swingAnimation, Math.max(0.0f, Math.min(1.0f, minimumCharge)));
    }

    /** Server rotation, attack and view restoration in wire order; the local camera stays untouched. */
    public static boolean attackDirected(Minecraft mc, Entity target, float yaw, float pitch,
                                         boolean swingAnimation, float minimumCharge) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(minimumCharge)
                || mc == null || mc.player == null || mc.gameMode == null
                || !validTarget(mc.player, target) || !canInteract(mc)
                || charge(mc.player) < Math.max(0, Math.min(1, minimumCharge))
                || com.baggute.cheatclient.module.combat.AntiMiss.shouldBlock(mc.player, target)
                || com.baggute.cheatclient.module.combat.HitSelect.shouldBlock(mc)) return false;
        float visibleYaw = mc.player.getYRot(), visiblePitch = mc.player.getXRot();
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                yaw, Math.max(-90, Math.min(90, pitch)), mc.player.onGround(), mc.player.horizontalCollision));
        try {
            return attack(mc, target, swingAnimation, minimumCharge);
        } finally {
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                    visibleYaw, visiblePitch, mc.player.onGround(), mc.player.horizontalCollision));
        }
    }

    private static boolean fire(Minecraft mc, Entity target, boolean swingAnimation, float minimumCharge) {
        if (mc == null || mc.player == null || mc.gameMode == null) return false;
        if (!validTarget(mc.player, target) || !canInteract(mc)) return false;
        if (com.baggute.cheatclient.module.combat.AntiMiss.shouldBlock(mc.player, target)
                || com.baggute.cheatclient.module.combat.HitSelect.shouldBlock(mc)) return false;
        if (charge(mc.player) < minimumCharge) return false;
        if (!inEntityReach(mc.player, target)) return false;

        PiercingWeapon piercing = mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (piercing != null) return attack(mc, piercing, target);
        mc.gameMode.attack(mc.player, target);
        if (swingAnimation) mc.player.swing(InteractionHand.MAIN_HAND);
        markAttack();
        return true;
    }

    /**
     * Sends one piercing attack (a spear STAB), under the same arbitration as a
     * plain attack: no rotation or swap on this tick, one interaction per tick, and
     * the target inside interaction range.
     *
     * @return true when the STAB went out
     */
    public static boolean piercingAttack(Minecraft mc, PiercingWeapon weapon, Entity target) {
        return attack(mc, weapon, target);
    }

    public static boolean attack(Minecraft mc, PiercingWeapon weapon, Entity target) {
        if (!canAttack(mc, weapon, target)) return false;
        mc.gameMode.piercingAttack(weapon);
        mc.player.swing(InteractionHand.MAIN_HAND);
        markAttack();
        return true;
    }

    /**
     * Lunge attribute swap: sample the ready fast-hand charge, then equip and
     * stab before the next equipment update. This atomic action deliberately
     * keeps the swap and stab together; an intervening tick loses the attributes.
     */
    public static boolean attack(Minecraft mc, int spearSlot, Entity target) {
        return lungeAttack(mc, spearSlot, target, false);
    }

    /** An intentional miss makes an empty swing; a null target alone would still find spear victims. */
    public static boolean lungeAttack(Minecraft mc, int spearSlot, Entity target, boolean intentionalMiss) {
        if (!canInteract(mc) || !strengthReady(mc.player) || spearSlot < 0 || spearSlot > 8) return false;
        if (target != null && (!validTarget(mc.player, target) || !inEntityReach(mc.player, target)
                || com.baggute.cheatclient.module.combat.AntiMiss.shouldBlock(mc.player, target)
                || com.baggute.cheatclient.module.combat.HitSelect.shouldBlock(mc))) return false;
        PiercingWeapon weapon = mc.player.getInventory().getItem(spearSlot)
                .get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (weapon == null || mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(spearSlot))) return false;
        // Sample charge while the fast-hand attributes still apply, then select
        // and stab in wire order before the next equipment update.
        if (mc.player.getInventory().getSelectedSlot() != spearSlot) {
            mc.player.getInventory().setSelectedSlot(spearSlot);
            sendCarriedItem(mc, spearSlot);
            markSwap();
        }
        if (!intentionalMiss) mc.gameMode.piercingAttack(weapon);
        mc.player.swing(InteractionHand.MAIN_HAND);
        markAttack();
        return true;
    }

    /** An air STAB has no entity reach to test, but retains phase and charge gates. */
    public static boolean canAttack(Minecraft mc, PiercingWeapon weapon, Entity target) {
        if (weapon == null || mc == null || mc.player == null || !canInteract(mc)
                || !strengthReady(mc.player)
                || !weapon.equals(mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON))) return false;
        return target == null || canAttack(mc, target);
    }

    /**
     * Animation only. Sends the swing packet and nothing the server can resolve as a
     * hit, which is what a click that landed on air produces.
     */
    public static void swing(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static java.lang.reflect.Method sprintSender;
    private static java.lang.reflect.Field serverSprint;
    public static boolean serverSprinting(LocalPlayer player) {
        try {
            if (serverSprint == null) { serverSprint = LocalPlayer.class.getDeclaredField("wasSprinting"); serverSprint.setAccessible(true); }
            return player != null && serverSprint.getBoolean(player);
        } catch (ReflectiveOperationException ignored) { return false; }
    }
    /** Uses vanilla's command sender so its cached sprint state cannot emit duplicate commands. */
    public static void setSprinting(Minecraft mc, boolean sprinting) {
        if (mc == null || mc.player == null || mc.player.connection == null) return;
        mc.player.setSprinting(sprinting);
        try {
            if (sprintSender == null) {
                sprintSender = net.minecraft.client.player.LocalPlayer.class.getDeclaredMethod("sendIsSprintingIfNeeded");
                sprintSender.setAccessible(true);
            }
            sprintSender.invoke(mc.player);
        } catch (ReflectiveOperationException failure) {
            com.baggute.cheatclient.UtilityClient.LOGGER.error("Sprint reset sender failed", failure);
        }
    }

    /**
     * Sends a carried-item packet and marks the swap phase.
     *
     * <p>A swap on the same tick as an attack makes the server resolve the hit against
     * an item the player was not holding when the attack packet was authored. The
     * gate refuses in that direction too: a swap is deferred to the tick after the
     * attack, and the attack is deferred to the tick after the swap.
     *
     * @return true when the packet was sent
     */
    public static boolean swapSlot(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || mc.player.connection == null) return false;
        if (slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() == slot) return true;
        if (swapBlocked()) return false;

        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.player.connection != null) {
            sendCarriedItem(mc, slot);
        }
        markSwap();
        return true;
    }

    private static java.lang.reflect.Field carriedIndex;
    /** Keep vanilla's carried-item cache aligned with the explicit selection packet. */
    private static void sendCarriedItem(Minecraft mc, int slot) {
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        if (mc.gameMode == null) return;
        try {
            if (carriedIndex == null) { carriedIndex = net.minecraft.client.multiplayer.MultiPlayerGameMode.class.getDeclaredField("carriedIndex"); carriedIndex.setAccessible(true); }
            carriedIndex.setInt(mc.gameMode, slot);
        } catch (ReflectiveOperationException failure) {
            com.baggute.cheatclient.UtilityClient.LOGGER.error("Carried-item cache sync failed", failure);
        }
    }

    /** True when an interaction this tick would collide with a swap on the wire. */
    public static boolean swapBlocked() {
        return swappedThisTick || rotatedThisTick || attackedThisTick || interactedThisTick
                || ticksSinceRotation() == 1
                || (exchange != null && !processingExchange)
                || !com.baggute.cheatclient.module.misc.ActionTiming.ready();
    }

    /** Lifecycle cleanup retries on the next tick without bypassing arbitration. */
    public static void restoreSlot(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || slot < 0 || slot > 8) return;
        if (!swapSlot(mc, slot)) {
            restoreSlot = slot;
            restoreExpected = mc.player.getInventory().getSelectedSlot();
        }
    }

    public static boolean canRotate(Minecraft mc) {
        return mc != null && mc.player != null && mc.level != null && mc.gui.screen() == null
                && !mc.isPaused() && PhysicalInput.focused(mc) && !rotatedThisTick
                && !attackedThisTick && !swappedThisTick && !interactedThisTick && exchange == null
                && com.baggute.cheatclient.module.misc.ActionTiming.ready();
    }

    public static boolean canInteract(Minecraft mc) {
        return mc != null && mc.player != null && mc.gameMode != null
                && mc.player.connection != null && mc.level != null
                && mc.gui.screen() == null && !mc.isPaused() && PhysicalInput.focused(mc) && mc.player.isAlive()
                && !mc.player.isSpectator() && !swapBlocked();
    }

    public static void markInteraction() {
        interactedThisTick = true;
        com.baggute.cheatclient.module.misc.ActionTiming.consumed();
    }

    public static net.minecraft.world.InteractionResult useItem(Minecraft mc, InteractionHand hand) {
        if (!canInteract(mc)) return net.minecraft.world.InteractionResult.FAIL;
        net.minecraft.world.InteractionResult result = mc.gameMode.useItem(mc.player, hand);
        markInteraction();
        return result;
    }

    /** Select and use one hotbar item as one ordered interaction, without an equip-only tick. */
    public static net.minecraft.world.InteractionResult useItemSelected(Minecraft mc, InteractionHand hand,
            int slot, String expectedItem, float yaw, float pitch) {
        if (!canInteract(mc) || hand == null || slot < 0 || slot > 8 || expectedItem == null
                || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            return net.minecraft.world.InteractionResult.FAIL;
        LocalPlayer player = mc.player;
        var stack = hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getInventory().getItem(slot);
        if (!ModuleUtils.matches(stack, expectedItem) || player.getCooldowns().isOnCooldown(stack))
            return net.minecraft.world.InteractionResult.FAIL;
        // Only this selection can share its use. Earlier swaps/rotations still fail canInteract.
        if (hand == InteractionHand.MAIN_HAND && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
            markSwap();
            sendCarriedItem(mc, slot);
        }
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90.0f, 90.0f));
            return mc.gameMode.useItem(player, hand);
        } finally {
            markInteraction();
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /** The use-item packet carries its own direction; retain the visible view. */
    public static net.minecraft.world.InteractionResult useItemDirected(Minecraft mc, InteractionHand hand,
            float yaw, float pitch) {
        if (!canInteract(mc) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            return net.minecraft.world.InteractionResult.FAIL;
        LocalPlayer player = mc.player;
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90.0f, 90.0f));
            return useItem(mc, hand);
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    public static net.minecraft.world.InteractionResult useItemOn(Minecraft mc, InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!canInteract(mc) || hit == null || !inBlockReach(mc.player, hit.getBlockPos())
                || mc.player.getEyePosition().distanceTo(hit.getLocation()) > blockReachLimit(mc.player) + BLOCK_REACH_BUFFER)
            return net.minecraft.world.InteractionResult.FAIL;
        net.minecraft.world.InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        markInteraction();
        return result;
    }

    /**
     * Right-click on an entity, under the same interaction phase as a block use.
     *
     * <p>A module that calls {@code gameMode.interact} directly can put an entity
     * interaction on the same tick as a rotation or a swap, which is the two-packets-
     * one-slice shape the arbiter exists to prevent.
     */
    public static net.minecraft.world.InteractionResult interact(Minecraft mc, Entity entity,
            net.minecraft.world.phys.EntityHitResult hit, InteractionHand hand) {
        if (!canInteract(mc) || entity == null || hit == null || hit.getEntity() != entity
                || !entity.isAlive() || !inEntityReach(mc.player, entity)) return net.minecraft.world.InteractionResult.FAIL;
        net.minecraft.world.InteractionResult result = mc.gameMode.interact(mc.player, entity, hit, hand);
        markInteraction();
        return result;
    }

    /** Inventory mutation overload; menu IDs are always taken from the current menu. */
    public static boolean swapSlot(Minecraft mc, int menuSlot, int button,
            net.minecraft.world.inventory.ContainerInput input) {
        if (mc == null || mc.player == null || mc.gameMode == null || swapBlocked()) return false;
        var menu = mc.player.containerMenu;
        if (menu == null || menuSlot < 0 || menuSlot >= menu.slots.size()) return false;
        if (input == net.minecraft.world.inventory.ContainerInput.SWAP && (button < 0 || (button > 8 && button != 40))) return false;
        mc.gameMode.handleContainerInput(menu.containerId, menuSlot, button, input, mc.player);
        markSwap();
        return true;
    }

    /** Queues a player-menu exchange. Non-hotbar clicks advance once per tick. */
    public static boolean swapSlot(Minecraft mc, int sourceMenu, int destinationMenu) {
        if (mc == null || mc.player == null || mc.gameMode == null || swapBlocked()
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()
                || sourceMenu < 5 || sourceMenu > 45 || destinationMenu < 5 || destinationMenu > 45) return false;
        if (sourceMenu == destinationMenu) return true;
        if (destinationMenu == 45)
            return swapSlot(mc, sourceMenu, 40, net.minecraft.world.inventory.ContainerInput.SWAP);
        if (destinationMenu >= 36 && destinationMenu <= 44)
            return swapSlot(mc, sourceMenu, destinationMenu - 36, net.minecraft.world.inventory.ContainerInput.SWAP);
        var source = mc.player.inventoryMenu.getSlot(sourceMenu);
        var destination = mc.player.inventoryMenu.getSlot(destinationMenu);
        if (!source.mayPickup(mc.player) || !destination.mayPickup(mc.player)
                || (!source.getItem().isEmpty() && !destination.mayPlace(source.getItem()))
                || (!destination.getItem().isEmpty() && !source.mayPlace(destination.getItem()))) return false;
        exchange = new Exchange(mc.player, sourceMenu, destinationMenu);
        advanceExchange(mc);
        return true;
    }

    private static boolean exact(net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b) {
        return (a.isEmpty() && b.isEmpty()) || (!a.isEmpty() && !b.isEmpty()
                && a.getCount() == b.getCount()
                && net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b));
    }

    private static void advanceExchange(Minecraft mc) {
        Exchange job = exchange;
        if (job == null) return;
        if (mc.player != job.player || mc.gameMode == null) { exchange = null; return; }
        if (mc.player.containerMenu != mc.player.inventoryMenu || mc.isPaused()) return;
        if (++job.ticks > 120 || !exact(job.expectedSource, mc.player.inventoryMenu.getSlot(job.source).getItem())
                || !exact(job.expectedDestination, mc.player.inventoryMenu.getSlot(job.destination).getItem())
                || !exact(job.expectedCursor, mc.player.inventoryMenu.getCarried())) job.phase = 3;
        processingExchange = true;
        try {
            if (job.phase < 3) {
                int slot = job.phase == 1 ? job.destination : job.source;
                if (!swapSlot(mc, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP)) return;
                job.phase++;
                job.capture();
            } else if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
                // Cancelled exchanges retain their cursor; return it without dropping items.
                var carried = mc.player.inventoryMenu.getCarried();
                for (int slot = 9; slot <= 44; slot++) {
                    var destination = mc.player.inventoryMenu.getSlot(slot);
                    var item = destination.getItem();
                    if (destination.mayPlace(carried) && (item.isEmpty()
                            || (net.minecraft.world.item.ItemStack.isSameItemSameComponents(item, carried)
                            && item.getCount() + carried.getCount() <= destination.getMaxStackSize(carried)))) {
                        swapSlot(mc, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
                        break;
                    }
                }
            }
            if (job.phase >= 3 && mc.player.inventoryMenu.getCarried().isEmpty()) exchange = null;
        } finally { processingExchange = false; }
    }

    /**
     * Convenience for the common "is this entity still a legal target" test used by
     * every combat module before it spends a tick on aiming.
     */
    public static boolean validTarget(LocalPlayer player, Entity target) {
        if (player == null || target == null) return false;
        if (target == player || !target.isAlive() || !target.isAttackable()
                || com.baggute.cheatclient.friends.FriendsManager.isFriend(target)) return false;
        return inEntityReach(player, target);
    }
}
