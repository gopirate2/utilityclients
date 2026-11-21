// language: Java, file: AutoXbow.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.stealth.LogManager;
import com.baggute.cheatclient.util.ChatCommands;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class AutoXbow extends Module {

    private static final String ITEM_RAIL = "RAIL";
    private static final String ITEM_TNT_MINECART = "TNT_MINECART";
    private static final String ITEM_FLINT_AND_STEEL = "FLINT_AND_STEEL";
    private static final String ITEM_CROSSBOW = "CROSSBOW";

    private static final Item[] RAIL_ITEMS = {
            Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL
    };

    private static final int CART_WAIT_TICKS = 20;
    private static final int FIRE_WAIT_TICKS = 10;
    private static final int RAIL_WAIT_TICKS = 10;
    private static final int PRIME_WAIT_TICKS = 5;
    private static final int BAD_ANCHOR_TICKS = 40;
    private static final int CYCLE_TIMEOUT_TICKS = 200;

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final DoubleSetting missChance =
            new DoubleSetting("missChance", "Chance the shot misses (%)", 0.0, 0.0, 100.0);
    private final BoolSetting useOffhand =
            new BoolSetting("useOffhand", "Fallback to an offhand crossbow", true);
    private final BoolSetting chatFeedback =
            new BoolSetting("sendChatFeedback", "Send Chat Feedback: show automatic feedback in local chat", false);
    private final KeybindSetting activate =
            new KeybindSetting("Fire", "Start one cycle now", -1, false);

    private State state = State.IDLE;
    private int stateTicks;
    private int cycleTicks;
    private int cartId = -1;
    private int originalSlot = -1;
    private int crossbowSlot = -1;
    private BlockPos supportPos;
    private BlockPos railPos;
    private BlockPos firePos;
    private BlockPos badAnchor;
    private int badTicks;
    private String railItem;
    private String fireItem;
    private InteractionHand crossbowHand;
    private boolean chargingCrossbow;
    private boolean loadingReleased;
    private int loadAckTicks;
    private int expectedSlot = -1;
    private State afterCharge = State.PLACE_RAIL;
    private Boolean missShot;
    private float missYaw, missPitch;
    private LocalPlayer owner;
    private final java.util.Set<Integer> existingCarts = new java.util.HashSet<>();

    private enum State {
        IDLE,
        CHARGE,
        PLACE_RAIL,
        PLACE_CART,
        WAIT_CART,
        PLACE_FIRE,
        WAIT_FIRE,
        SHOOT
    }

    private enum Step {
        ACTED,
        ADVANCED,
        WAITING
    }

    private record CrossbowRef(int slot, InteractionHand hand) {
        private boolean isOffhand() {
            return hand == InteractionHand.OFF_HAND;
        }
    }

    private record Rotation(float yaw, float pitch) {}

    public AutoXbow() {
        super("AutoXbow",
                "Charges a crossbow, then places a rail, TNT minecart and ignition at the crosshair.",
                Category.COMBAT);
        addSetting(activate);
        addSetting(missChance);
        addSetting(useOffhand);
        addSetting(chatFeedback);
    }

    @Override
    public void onRawKey(int keyCode, int action) {
        if (action != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        if (activate.getKey() == -1 || keyCode != activate.getKey()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;

        trigger();
    }

    public boolean trigger() {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || state != State.IDLE) return false;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return false;

        String missing = missingItem(mc);
        if (!missing.isEmpty()) {
            abortMessage("Missing item: " + missing);
            return false;
        }

        BlockPos support = crosshairSupport(mc);
        String problem = anchorProblem(mc, support);
        if (problem != null) {
            abortMessage(problem);
            return false;
        }

        if (!begin(mc, support)) return false;
        return state == State.CHARGE;
    }

    @Override public void onInputSuspended() { reset(Minecraft.getInstance(), true); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || (owner != null && owner != player) || mc.level == null || mc.gameMode == null) {
            reset(mc, false);
            return;
        }
        if (mc.gui.screen() != null || mc.isPaused()) {
            reset(mc, true);
            return;
        }

        if (badTicks > 0 && --badTicks == 0) badAnchor = null;

        // Idle does nothing at all: a cycle exists only because trigger() armed one,
        // and nothing is held back between cycles.
        if (state == State.IDLE) return;
        if (expectedSlot >= 0 && player.getInventory().getSelectedSlot() != expectedSlot) {
            reset(mc, false); return;
        }

        cycleTicks++;
        stateTicks++;
        if (cycleTicks > CYCLE_TIMEOUT_TICKS) {
            abort(mc, "cycle timed out in " + state.name());
            return;
        }

        // One pass per stage that advanced without sending anything. The bound is
        // the state count, so a bug that loops between two states ends the tick
        // instead of spinning the client thread.
        for (int transitions = 0; transitions < State.values().length; transitions++) {
            Step step = advance(mc, player);
            expectedSlot = state == State.IDLE ? -1 : player.getInventory().getSelectedSlot();
            if (step != Step.ADVANCED) return;
        }
    }

    private Step advance(Minecraft mc, LocalPlayer player) {
        return switch (state) {
            case CHARGE -> tickCharge(mc, player);
            case PLACE_RAIL -> tickPlaceRail(mc);
            case PLACE_CART -> tickPlaceCart(mc);
            case WAIT_CART -> tickWaitCart(mc);
            case PLACE_FIRE -> tickPlaceFire(mc);
            case WAIT_FIRE -> tickWaitFire(mc);
            case SHOOT -> tickShoot(mc, player);
            case IDLE -> Step.WAITING;
        };
    }

    // ---- kit ----

    public String missingItem(Minecraft mc) {
        if (mc.player == null) return "any hotbar item";
        if (findRailItem(mc) == null) return ITEM_RAIL;
        if (hotbarSlotOf(mc, Items.TNT_MINECART) < 0) return ITEM_TNT_MINECART;
        if (findFireItem(mc) == null) return ITEM_FLINT_AND_STEEL;
        if (findCrossbow(mc) == null) return ITEM_CROSSBOW;
        return "";
    }

    private String findRailItem(Minecraft mc) {
        for (Item rail : RAIL_ITEMS) {
            int slot = hotbarSlotOf(mc, rail);
            if (slot >= 0) return ModuleUtils.path(mc.player.getInventory().getItem(slot));
        }
        return null;
    }

    private String findFireItem(Minecraft mc) {
        int slot = hotbarSlotOf(mc, Items.FLINT_AND_STEEL);
        return slot < 0 ? null : ModuleUtils.path(mc.player.getInventory().getItem(slot));
    }

    private static int hotbarSlotOf(Minecraft mc, Item... items) {
        return ModuleUtils.findHotbarItem(mc, stack -> {
            for (Item item : items) {
                if (stack.getItem() == item) return true;
            }
            return false;
        });
    }

    private CrossbowRef findCrossbow(Minecraft mc) {
        if (mc.player == null) return null;

        int charged = ModuleUtils.findHotbarItem(mc,
                stack -> stack.getItem() == Items.CROSSBOW && CrossbowItem.isCharged(stack));
        if (charged >= 0) return new CrossbowRef(charged, InteractionHand.MAIN_HAND);

        int any = ModuleUtils.findHotbarItem(mc, stack -> stack.getItem() == Items.CROSSBOW);
        if (any >= 0) return new CrossbowRef(any, InteractionHand.MAIN_HAND);

        // The offhand is not the hotbar, so it is the one lookup the scan cannot do.
        if (useOffhand.get() && isCrossbow(mc.player.getOffhandItem())) {
            return new CrossbowRef(-1, InteractionHand.OFF_HAND);
        }
        return null;
    }

    private boolean isCrossbow(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.CROSSBOW;
    }

    // ---- crosshair and anchor validation ----

    private BlockPos crosshairSupport(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
        return hit.getBlockPos().immutable();
    }

    private String anchorProblem(Minecraft mc, BlockPos support) {
        if (mc.level == null || mc.player == null) return "no world";
        if (support == null) return "crosshair is not on a block";
        if (badAnchor != null && badAnchor.equals(support)) return "that anchor just failed; try another";

        if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) {
            return "target block is not solid";
        }

        BlockPos cell = support.above();
        if (!mc.level.getBlockState(cell).isAir()) {
            return "space above the target block is not air";
        }
        if (!mc.level.getBlockState(cell.above()).isAir()) {
            return "no headroom for the minecart";
        }
        if (!Stealth.inBlockReach(mc.player, support)) {
            return "target block is out of reach";
        }
        if (!livingEntitiesClear(mc, cell)) {
            return "an entity is standing in the rail cell";
        }
        return null;
    }

    private boolean livingEntitiesClear(Minecraft mc, BlockPos cell) {
        AABB volume = blockBox(cell);
        for (Entity entity : mc.level.getEntities((Entity) null, volume)) {
            if (entity instanceof LivingEntity) return false;
        }
        return true;
    }

    private BlockPos pickFireCell(Minecraft mc, Entity cart) {
        if (railPos == null || mc.player == null) return null;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = shotPoint(cart);
        BlockPos best = null;
        double bestEntry = -1.0;

        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = railPos.relative(direction);
            if (!isFireCellUsable(mc, candidate)) continue;

            double entry = segmentEntry(eye, target, candidate);
            if (entry >= 0.0 && entry > bestEntry) {
                bestEntry = entry;
                best = candidate;
            }
        }
        return best == null ? null : best.immutable();
    }

    private boolean isFireCellUsable(Minecraft mc, BlockPos cell) {
        BlockPos support = cell.below();
        if (!mc.level.getBlockState(cell).canBeReplaced() && !isFire(mc, cell)) return false;
        if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) return false;
        if (!Stealth.inBlockReach(mc.player, support)) return false;

        for (Entity entity : mc.level.getEntities((Entity) null, blockBox(cell))) {
            if (entity instanceof LivingEntity) return false;
        }
        return true;
    }

    private double segmentEntry(Vec3 start, Vec3 end, BlockPos block) {
        double[] origin = {start.x, start.y, start.z};
        double[] delta = {end.x - start.x, end.y - start.y, end.z - start.z};
        double[] min = {block.getX() + 0.02, block.getY() + 0.02, block.getZ() + 0.02};
        double[] max = {block.getX() + 0.98, block.getY() + 0.98, block.getZ() + 0.98};
        double entry = 0.0;
        double exit = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1.0E-7) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return -1.0;
                continue;
            }
            double first = (min[axis] - origin[axis]) / delta[axis];
            double second = (max[axis] - origin[axis]) / delta[axis];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            entry = Math.max(entry, first);
            exit = Math.min(exit, second);
            if (entry > exit) return -1.0;
        }
        return entry >= 0.0 && entry <= 1.0 ? entry : -1.0;
    }

    private AABB blockBox(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    private Vec3 shotPoint(Entity cart) {
        return cart.position().add(0.0, Math.max(0.2, cart.getBbHeight() * 0.35), 0.0);
    }

    // ---- cycle ----

    private boolean begin(Minecraft mc, BlockPos support) {
        railItem = findRailItem(mc);
        fireItem = findFireItem(mc);
        if (railItem == null) {
            abortMessage("Missing item: " + ITEM_RAIL);
            return false;
        }
        if (fireItem == null) {
            abortMessage("Missing item: " + ITEM_FLINT_AND_STEEL);
            return false;
        }

        originalSlot = mc.player.getInventory().getSelectedSlot();
        owner = mc.player;
        existingCarts.clear();
        for (Entity entity : mc.level.entitiesForRendering()) if (isTntMinecart(entity)) existingCarts.add(entity.getId());
        supportPos = support;
        railPos = support.above();
        firePos = null;
        cartId = -1;
        crossbowSlot = -1;
        crossbowHand = null;
        chargingCrossbow = false;
        loadingReleased = false; loadAckTicks = 0; afterCharge = State.PLACE_RAIL;
        expectedSlot = originalSlot;
        cycleTicks = 0;
        setState(State.CHARGE);
        return true;
    }

    private Step tickCharge(Minecraft mc, LocalPlayer player) {
        CrossbowRef ref = findCrossbow(mc);
        if (ref == null) {
            abort(mc, "Missing item: " + ITEM_CROSSBOW);
            return Step.WAITING;
        }

        crossbowSlot = ref.slot();
        crossbowHand = ref.hand();

        if (!ref.isOffhand() && player.getInventory().getSelectedSlot() != ref.slot()) {
            // Carried-item packet this tick; the draw starts on the next one.
            ModuleUtils.select(mc, ref.slot());
            return Step.ACTED;
        }

        ItemStack crossbow = stackIn(player, ref.hand());
        if (!isCrossbow(crossbow)) {
            // The slot synced an item that is not a crossbow any more (death, drop,
            // container move). Re-scan next tick rather than acting on a stale ref.
            return Step.WAITING;
        }

        if (CrossbowItem.isCharged(crossbow)) {
            chargingCrossbow = false;
            loadingReleased = false;
            // No packet sent this tick, so the rail can go down immediately.
            setState(afterCharge);
            return Step.ADVANCED;
        }
        if (loadingReleased) {
            if (++loadAckTicks > 10) abort(mc, "crossbow loading was not confirmed; no placement attempted");
            return Step.WAITING;
        }

        if (player.isUsingItem()) {
            if (!chargingCrossbow || player.getUsedItemHand() != ref.hand()) {
                abort(mc, "hands became busy while charging the crossbow");
                return Step.WAITING;
            }

            int duration = CrossbowItem.getChargeDuration(crossbow, player);
            if (player.getTicksUsingItem() >= duration) {
                if (!Stealth.canInteract(mc)) return Step.WAITING;
                mc.gameMode.releaseUsingItem(player);
                Stealth.markInteraction();
                chargingCrossbow = false;
                loadingReleased = true; loadAckTicks = 0;
                // The item becomes charged on release; isCharged() is re-read next
                // tick, and the rail only starts once it reads true.
                return Step.ACTED;
            }
            return Step.WAITING;
        }

        if (player.getProjectile(crossbow).isEmpty() && !player.getAbilities().instabuild) {
            abort(mc, "crossbow has no usable ammunition; no placement attempted"); return Step.WAITING;
        }
        if (!Stealth.canInteract(mc)) return Step.WAITING;
        Stealth.useItem(mc, ref.hand());
        chargingCrossbow = player.isUsingItem();
        if (!chargingCrossbow) {
            abort(mc, "crossbow could not start charging - check ammunition");
            return Step.WAITING;
        }
        return Step.ACTED;
    }

    private Step tickPlaceRail(Minecraft mc) {
        String problem = anchorProblem(mc, supportPos);
        if (problem != null) {
            blacklist(supportPos);
            abort(mc, problem);
            return Step.WAITING;
        }

        Placement.Result result = Placement.useOnSilent(mc, railItem, supportPos);
        if (result == Placement.Result.WAITING) {
            // Placement selected the item and queued the carried-item packet.
            return Step.ACTED;
        }
        if (result == Placement.Result.MISSING) {
            abort(mc, "Missing item: " + ITEM_RAIL);
            return Step.WAITING;
        }
        if (result != Placement.Result.USED) {
            if (result != Placement.Result.OUT_OF_RANGE) blacklist(supportPos);
            abort(mc, "rail placement refused (" + result.name().toLowerCase() + ")");
            return Step.WAITING;
        }
        setState(State.PLACE_CART);
        return Step.ACTED;
    }

    private Step tickPlaceCart(Minecraft mc) {
        if (railPos == null || !isRail(mc, railPos)) {
            // The rail block is confirmed by the server, so it can be one or two
            // ticks behind the click. Wait for it, up to a timeout, instead of
            // deciding the placement failed.
            if (stateTicks <= RAIL_WAIT_TICKS) return Step.WAITING;
            blacklist(supportPos);
            abort(mc, "rail never appeared on the server");
            return Step.WAITING;
        }

        Placement.Result result = Placement.useOnSilent(mc, "tnt_minecart", railPos);
        if (result == Placement.Result.WAITING) return Step.ACTED;
        if (result == Placement.Result.MISSING) {
            abort(mc, "Missing item: " + ITEM_TNT_MINECART);
            return Step.WAITING;
        }
        if (result != Placement.Result.USED) {
            abort(mc, "minecart placement refused (" + result.name().toLowerCase() + ")");
            return Step.WAITING;
        }
        setState(State.WAIT_CART);
        return Step.ACTED;
    }

    private Step tickWaitCart(Minecraft mc) {
        Entity cart = findCart(mc);
        if (cart == null) {
            if (stateTicks > CART_WAIT_TICKS) {
                abort(mc, "TNT minecart entity never appeared");
            }
            return Step.WAITING;
        }

        cartId = cart.getId();
        setState(State.PLACE_FIRE);
        // No packet was sent, so the ignition can be attempted on this same tick.
        return Step.ADVANCED;
    }

    private Step tickPlaceFire(Minecraft mc) {
        Entity cart = findCartById(mc);
        if (mc.player == null || cart == null || !cart.isAlive()) {
            abort(mc, "TNT minecart vanished before fire placement");
            return Step.WAITING;
        }
        // Fire belongs between the player and the cart. Priming the cart with
        // flint instead changes this into a fuse cycle and spends the setup.
        if (firePos == null) firePos = pickFireCell(mc, cart);
        if (firePos == null) {
            abort(mc, "no supported fire cell between you and the cart");
            return Step.WAITING;
        }
        if (isFire(mc, firePos)) {
            setState(State.SHOOT);
            return Step.ADVANCED;
        }
        if (!isFireCellUsable(mc, firePos)) {
            abort(mc, "fire lane became occupied");
            return Step.WAITING;
        }
        Placement.Result result = Placement.useOnSilent(mc, fireItem, firePos.below());
        if (result == Placement.Result.WAITING) return Step.ACTED;
        if (result != Placement.Result.USED) {
            abort(mc, "fire placement refused (" + result + ")");
            return Step.WAITING;
        }
        setState(State.WAIT_FIRE);
        return Step.ACTED;
    }

    private Step tickWaitFire(Minecraft mc) {
        if (isFire(mc, firePos)) {
            setState(State.SHOOT);
            return Step.ADVANCED;
        }
        if (stateTicks > FIRE_WAIT_TICKS) abort(mc, "fire did not appear");
        return Step.WAITING;
    }

    private Step tickShoot(Minecraft mc, LocalPlayer player) {
        Entity cart = findCartById(mc);
        if (cart == null || !cart.isAlive()) {
            abort(mc, "cart vanished before the shot");
            return Step.WAITING;
        }
        if (firePos == null || !isFire(mc, firePos)) {
            abort(mc, "no ignition left in the lane");
            return Step.WAITING;
        }
        if (!player.hasLineOfSight(cart)) {
            abort(mc, "cart is not visible");
            return Step.WAITING;
        }

        InteractionHand hand = crossbowHand;
        if (hand == null) {
            abort(mc, "crossbow hand lost before the shot");
            return Step.WAITING;
        }
        if (hand == InteractionHand.MAIN_HAND
                && (crossbowSlot < 0 || player.getInventory().getSelectedSlot() != crossbowSlot)) {
            ModuleUtils.select(mc, crossbowSlot);
            return Step.ACTED;
        }

        ItemStack crossbow = stackIn(player, hand);
        if (!isCrossbow(crossbow) || !CrossbowItem.isCharged(crossbow)) {
            // The release misfired or the charge was lost; re-draw rather than
            // throwing a shot the server will resolve as an empty crossbow.
            afterCharge = State.SHOOT; loadingReleased = false; loadAckTicks = 0;
            setState(State.CHARGE);
            return Step.ADVANCED;
        }

        Vec3 target = shotPoint(cart);
        if (segmentEntry(player.getEyePosition(), target, firePos) < 0.0) {
            abort(mc, "player moved out of the fire-to-cart lane");
            return Step.WAITING;
        }

        if (!Stealth.canInteract(mc)) return Step.WAITING;
        Rotation shot = planShot(player.getEyePosition(), target);
        // 26.2's use-item packet includes the firing angles. Prediction uses the
        // same direction and restores the camera before this tick returns.
        var result = Stealth.useItemDirected(mc, hand, shot.yaw(), shot.pitch());
        if (result == null || !result.consumesAction()) return Step.WAITING;
        player.swing(hand);
        // The cycle is spent on the shot and nothing is held back afterwards, so
        // the very next trigger starts a fresh one.
        reset(mc, true);
        return Step.WAITING;
    }

    // ---- cart and blocks ----

    private Entity findCartById(Minecraft mc) {
        if (cartId >= 0 && mc.level != null) {
            Entity entity = mc.level.getEntity(cartId);
            if (entity != null && isTntMinecart(entity)) return entity;
        }
        return findCart(mc);
    }

    private Entity findCart(Minecraft mc) {
        if (railPos == null || mc.level == null) return null;
        Vec3 center = Vec3.atCenterOf(railPos).add(0.0, 0.5, 0.0);
        AABB volume = new AABB(
                center.x - 1.5, center.y - 1.5, center.z - 1.5,
                center.x + 1.5, center.y + 1.5, center.z + 1.5);
        for (Entity entity : mc.level.getEntities((Entity) null, volume)) {
            if (isTntMinecart(entity) && !existingCarts.contains(entity.getId())) return entity;
        }
        return null;
    }

    private boolean isTntMinecart(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && "tnt_minecart".equals(key.getPath());
    }

    private boolean isRail(Minecraft mc, BlockPos pos) {
        var key = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock());
        return key != null && key.getPath().contains("rail");
    }

    private boolean isFire(Minecraft mc, BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        var block = mc.level.getBlockState(pos).getBlock();
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE;
    }

    private ItemStack stackIn(LocalPlayer player, InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getMainHandItem();
    }

    // ---- shot rotation ----



    private Rotation rotationTo(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new Rotation(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private boolean rollMiss() {
        double chance = missChance.get();
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble(100.0) < chance;
    }

    private Rotation planShot(Vec3 origin, Vec3 target) {
        if (missShot == null) {
            missShot = rollMiss();
            if (missShot) {
                // A deliberate air shot has enough separation to miss the cart, even on retries.
                missYaw = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                        * (float) ThreadLocalRandom.current().nextDouble(105.0, 125.0);
                missPitch = (float) ThreadLocalRandom.current().nextDouble(-65.0, -45.0);
            }
        }
        Rotation shot = rotationTo(origin, target);
        return missShot ? new Rotation(shot.yaw() + missYaw, missPitch) : shot;
    }

    // ---- state and lifecycle ----

    private void setState(State next) {
        state = next;
        stateTicks = 0;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoXbow", next.name());
    }

    private void blacklist(BlockPos pos) {
        if (pos == null) return;
        badAnchor = pos.immutable();
        badTicks = BAD_ANCHOR_TICKS;
    }

    private void abort(Minecraft mc, String why) {
        abortMessage(why);
        reset(mc, true);
    }

    private void abortMessage(String why) {
        diagnose(why);
        if (chatFeedback.get()) {
            ChatCommands.say("[AutoXbow] " + why, ChatFormatting.RED);
        }
    }

    private void reset(Minecraft mc, boolean restoreSlot) {
        missShot = null;
        if (chargingCrossbow && owner == mc.player && mc.player != null && mc.gameMode != null
                && mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
            Stealth.markInteraction();
        }
        chargingCrossbow = false;
        loadingReleased = false; loadAckTicks = 0; expectedSlot = -1; afterCharge = State.PLACE_RAIL;

        if (restoreSlot && owner == mc.player && originalSlot >= 0 && mc.player != null) {
            Stealth.restoreSlot(mc, originalSlot);
        }

        state = State.IDLE;
        stateTicks = 0;
        cycleTicks = 0;
        cartId = -1;
        originalSlot = -1;
        crossbowSlot = -1;
        supportPos = null;
        railPos = null;
        firePos = null;
        railItem = null;
        fireItem = null;
        crossbowHand = null;
        owner = null;
        existingCarts.clear();
        com.baggute.cheatclient.module.misc.Debug.stage("AutoXbow", "IDLE");
    }

    private long lastDiagnostic;

    private void diagnose(String why) {
        long now = System.currentTimeMillis();
        if (now - lastDiagnostic < 2_000L) return;
        lastDiagnostic = now;
        LogManager.log("INFO", "AutoXbow: " + why, null);
    }

    @Override
    protected void onEnable() {
        reset(Minecraft.getInstance(), false);
        badAnchor = null;
        badTicks = 0;
        UtilityClient.LOGGER.info("[AutoXbow] enabled");
    }

    @Override
    protected void onDisable() {
        reset(Minecraft.getInstance(), true);
        badAnchor = null;
        badTicks = 0;
    }
}
