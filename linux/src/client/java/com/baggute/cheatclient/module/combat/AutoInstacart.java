// language: Java, file: AutoInstacart.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.ChatCommands;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Bow shot, then a rail and a TNT minecart at the crosshair.
 *
 * <p>Placement is anchored to the block you are actually looking at. {@code
 * Placement.useOn} fills {@code support.above()}, so passing {@code target.below()}
 * â€” as an old version did â€” tried to place the rail inside the floor block and
 * the server refused it every time.
 *
 * <p><b>No slot settings.</b> The module used to carry two integer settings asking
 * the player to pin which hotbar slot held the rail and which held the cart, with
 * zero meaning "find it yourself". That is a second inventory to keep in sync with
 * the first, and it was always the copy that was wrong: the setting said slot 2,
 * the player had reorganised thirty seconds earlier, and the macro placed the
 * wrong item or nothing at all. The hotbar is scanned on every cycle instead, and
 * the slots found are the ones the state machine swaps to.
 *
 * <p><b>Feedback.</b> A cycle that cannot start because the kit is incomplete says
 * which item is missing, once, in chat. A macro that silently refuses is
 * indistinguishable from a broken keybind, which is how this module's failures
 * were being reported.
 *
 * <p>One stage per tick. The swap and the placement never share a tick, because the
 * carried-item packet has to land before the interaction or the server resolves
 * the placement against whatever was held on its copy.
 */
public final class AutoInstacart extends Module {

    /** Registry-style labels used in chat and in the SelfTest line. */
    private static final String ITEM_RAIL = "rail";
    private static final String ITEM_CART = "tnt_minecart";

    /** Every block a minecart can be placed on, in the order the scan prefers them. */
    private static final Item[] RAIL_ITEMS = {
            Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL
    };

    /** Stage names, so the state machine reads as phases and not as magic numbers. */
    private static final int IDLE = 0;
    private static final int SWAP_RAIL = 1;
    private static final int PLACE_RAIL = 2;
    private static final int SWAP_CART = 3;
    private static final int PLACE_CART = 4;

    private final BoolSetting autoSwap =
            new BoolSetting("autoSwap", "Switch to the rail and the cart automatically", true);
    private final BoolSetting chatFeedback =
            new BoolSetting("sendChatFeedback", "Send Chat Feedback: show automatic feedback in local chat", false);

    private boolean drawing;
    private net.minecraft.client.player.LocalPlayer owner;
    private int selectedSlot = -1;
    private int stage = IDLE;
    private int oldSlot = -1;
    /** Hotbar slot of the rail found at trigger time, -1 when the scan found none. */
    private int railSlot = -1;
    /** Hotbar slot of the TNT minecart found at trigger time, -1 when none. */
    private int cartSlot = -1;
    private BlockPos railPos;
    private BlockPos supportPos;
    private int cycleTicks;

    public AutoInstacart() {
        super("AutoInstacart",
                "Bow shot then a rail and a TNT minecart at the crosshair.",
                Category.COMBAT);
        addSetting(autoSwap);
        addSetting(chatFeedback);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.level == null
                || (owner != null && owner != mc.player)) {
            reset(mc);
            return;
        }
        if (mc.gui.screen() != null || mc.isPaused()) {
            // A screen owns the input while it is open; dropping the cycle is the
            // only honest answer, and the reset puts the previous slot back.
            if (stage != IDLE) abort(mc, "screen opened mid-cycle");
            else reset(mc);
            return;
        }

        owner = mc.player;
        boolean use = mc.options.keyUse.isDown();
        if (stage != IDLE && mc.player.getInventory().getSelectedSlot() != selectedSlot) {
            abort(mc, "selected slot changed mid-cycle"); return;
        }

        if (stage == IDLE) {
            if (use && mc.player.isUsingItem() && ModuleUtils.isPlainBow(mc.player.getUseItem())) {
                drawing = true;
            } else if (!use && drawing) {
                // The release is the trigger. Everything the cycle will spend is
                // checked here, before the first packet, so an incomplete kit costs
                // nothing but one chat line.
                drawing = false;
                begin(mc);
            } else if (!ModuleUtils.isPlainBow(mc.player.getMainHandItem()) || !mc.player.isUsingItem()) {
                drawing = false;
            }
            return;
        }

        if (++cycleTicks > 100) { abort(mc, "cycle timed out"); return; }
        if (stage == SWAP_RAIL) {
            // Re-scanned every cycle rather than trusted from the trigger: the slot
            // can be stale after a drop, a break, or a container move inside the
            // window between the release and this tick.
            int slot = findRailSlot(mc);
            if (slot < 0) {
                abort(mc, "Missing item: " + ITEM_RAIL);
                return;
            }
            railSlot = slot;
            if (autoSwap.get()) {
                if (!com.baggute.cheatclient.util.Stealth.swapSlot(mc, railSlot)) return;
                selectedSlot = mc.player.getInventory().getSelectedSlot();
            }
            if (!select(mc, railSlot, ITEM_RAIL)) {
                abort(mc, "no rail in the selected slot after the swap");
                return;
            }
            stage = PLACE_RAIL;
            return;
        }

        if (stage == PLACE_RAIL) {
            if (!ModuleUtils.matches(mc.player.getMainHandItem(), ITEM_RAIL)) {
                // The hand moved (screen, manual slot change). Redo the swap rather
                // than clicking with whatever is held now.
                stage = SWAP_RAIL;
                return;
            }
            BlockPos target = supportPos;
            if (target == null) {
                abort(mc, "crosshair is not on a block");
                return;
            }
            Placement.Result result = Placement.useOn(mc, ITEM_RAIL, target);
            if (result == Placement.Result.WAITING) return;
            if (result == Placement.Result.MISSING) {
                abort(mc, "Missing item: " + ITEM_RAIL);
                return;
            }
            if (result != Placement.Result.USED) {
                abort(mc, "rail placement refused (" + result.name().toLowerCase() + ")");
                return;
            }
            // useOn clicks the top face, so the rail landed one block up.
            railPos = target.above();
            stage = SWAP_CART;
            return;
        }

        if (stage == SWAP_CART) {
            int slot = findCartSlot(mc);
            if (slot < 0) {
                abort(mc, "Missing item: " + ITEM_CART);
                return;
            }
            cartSlot = slot;
            if (autoSwap.get()) {
                if (!com.baggute.cheatclient.util.Stealth.swapSlot(mc, cartSlot)) return;
                selectedSlot = mc.player.getInventory().getSelectedSlot();
            }
            if (!select(mc, cartSlot, ITEM_CART)) {
                abort(mc, "no minecart in the selected slot after the swap");
                return;
            }
            stage = PLACE_CART;
            return;
        }

        if (stage == PLACE_CART) {
            if (!ModuleUtils.matches(mc.player.getMainHandItem(), ITEM_CART)) {
                stage = SWAP_CART;
                return;
            }
            if (railPos == null) {
                abort(mc, "rail position lost before the cart");
                return;
            }
            Placement.Result result = Placement.useOn(mc, ITEM_CART, railPos);
            if (result == Placement.Result.WAITING) return;
            if (result == Placement.Result.MISSING) {
                abort(mc, "Missing item: " + ITEM_CART);
                return;
            }
            if (result != Placement.Result.USED) {
                abort(mc, "minecart placement refused (" + result.name().toLowerCase() + ")");
                return;
            }
            reset(mc);
        }
    }

    // ---- kit ----

    /**
     * Scans the hotbar and arms the cycle, or prints what is missing and stays idle.
     *
     * <p>The two slots are resolved here and kept for the whole cycle, so the state
     * machine swaps to a slot it looked up rather than a number a player typed.
     *
     * @return true when the cycle was armed
     */
    private boolean begin(Minecraft mc) {
        if (mc.player == null) return false;

        String missing = missingItem(mc);
        if (!missing.isEmpty()) {
            abortMessage("Missing item: " + missing);
            reset(mc);
            return false;
        }

        railSlot = findRailSlot(mc);
        cartSlot = findCartSlot(mc);
        oldSlot = selectedSlot = mc.player.getInventory().getSelectedSlot();
        supportPos = crosshairBlock(mc);
        if (supportPos == null) { reset(mc); return false; }
        supportPos = supportPos.immutable();
        cycleTicks = 0;
        railPos = null;
        stage = SWAP_RAIL;
        return true;
    }

    /**
     * First required item that is not in the hotbar, or {@code ""} when the kit is
     * complete.
     *
     * <p>One at a time, in stage order, because the message has to name the item to
     * fetch. "rail tnt_minecart" would leave the player working out which of the two
     * they had actually lost.
     *
     * @return the label of the missing item, or an empty string
     */
    public String missingItem(Minecraft mc) {
        if (mc.player == null) return "any hotbar item";
        if (findRailSlot(mc) < 0) return ITEM_RAIL;
        if (findCartSlot(mc) < 0) return ITEM_CART;
        return "";
    }

    /**
     * First hotbar slot holding any rail, or -1.
     *
     * <p>Scans slots 0-8 for the four block types a minecart accepts. Plain
     * {@code rail} wins only by list order; a hotbar with nothing but powered rail
     * still qualifies, which is what a player who crafts from gold expects.
     */
    private static int findRailSlot(Minecraft mc) {
        return ModuleUtils.findHotbarItem(mc, stack -> {
            for (Item rail : RAIL_ITEMS) {
                if (stack.getItem() == rail) return true;
            }
            return false;
        });
    }

    /** First hotbar slot holding a TNT minecart, or -1. */
    private static int findCartSlot(Minecraft mc) {
        return ModuleUtils.findHotbarItem(mc, stack -> stack.getItem() == Items.TNT_MINECART);
    }

    // ---- placement helpers ----

    private BlockPos crosshairBlock(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }

    /**
     * Swaps to {@code slot} and confirms the hand now holds the item.
     *
     * <p>With {@code autoSwap} off the swap is skipped, so this is also the check
     * that the player is holding the right item themselves.
     */
    private boolean select(Minecraft mc, int slot, String name) {
        if (slot < 0 || slot >= 9) return false;
        if (autoSwap.get()) ModuleUtils.select(mc, slot);
        return ModuleUtils.matches(mc.player.getMainHandItem(), name);
    }

    // ---- state and lifecycle ----

    private void reset(Minecraft mc) {
        if (oldSlot >= 0 && mc.player == owner && owner != null
                && mc.player.getInventory().getSelectedSlot() == selectedSlot) {
            com.baggute.cheatclient.util.Stealth.restoreSlot(mc, oldSlot);
        }
        owner = null;
        selectedSlot = -1;
        drawing = false;
        supportPos = null;
        cycleTicks = 0;
        stage = IDLE;
        oldSlot = -1;
        railSlot = -1;
        cartSlot = -1;
        railPos = null;
    }

    /** Ends the cycle with a reason the player can read, then resets. */
    private void abort(Minecraft mc, String why) {
        abortMessage(why);
        reset(mc);
    }

    private void abortMessage(String why) {
        UtilityClient.LOGGER.info("[AutoInstacart] {}", why);
        if (chatFeedback.get()) {
            ChatCommands.say("[AutoInstacart] " + why, ChatFormatting.RED);
        }
    }

    @Override
    protected void onEnable() {
        reset(Minecraft.getInstance());
    }

    @Override
    protected void onDisable() {
        reset(Minecraft.getInstance());
    }
}
