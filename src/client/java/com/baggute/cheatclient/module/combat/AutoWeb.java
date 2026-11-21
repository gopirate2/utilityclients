package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Places a cobweb where the crosshair is pointing.
 *
 * <p>The old version used {@code gameMode.useItem}, which only runs an item's own
 * right-click action; cobweb is a BlockItem with only {@code useOn}, so nothing
 * was ever placed. This version goes through {@link Placement} and the crosshair
 * block, and checks the result before restoring the hotbar.
 */
public final class AutoWeb extends Module {

    /** A placement that has not resolved in two seconds is stale. */
    private static final int ARMED_LIFETIME_TICKS = 40;

    private final KeybindSetting web = new KeybindSetting("Web", "Place a web at the crosshair", -1, false);
    private final BoolSetting autoSwap = new BoolSetting("autoSwap", "Switch to cobweb", true);

    private boolean armed;
    private int stage;
    private int oldSlot = -1;
    private int ownedSlot = -1;
    private net.minecraft.client.player.LocalPlayer owner;
    /** Ticks left before an armed request gives up; a stalled placement is dropped. */
    private int armedTicks;

    public AutoWeb() {
        super("AutoWeb", "Swaps to a web and places it at the crosshair on command.", Category.COMBAT);
        addSetting(web);
        addSetting(autoSwap);
    }

    @Override
    public void onRawKey(int key, int action) {
        if (isEnabled() && action == 1 && key == web.getKey()) {
            armed = true;
            armedTicks = ARMED_LIFETIME_TICKS;
        }
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!armed) return;
        if (mc.player == null || mc.gameMode == null || mc.level == null) { reset(); return; }

        // A screen this tick must not eat the request silently: drop it and put the
        // hotbar back, instead of stalling half-swapped until the module is toggled.
        if (mc.gui.screen() != null || mc.isPaused() || --armedTicks <= 0) {
            reset();
            return;
        }

        if (stage > 0 && (owner != mc.player || mc.player.getInventory().getSelectedSlot() != ownedSlot)) {
            oldSlot = -1; reset(); return;
        }
        if (stage == 0) {
            int slot = ModuleUtils.findHotbarItem(mc, "cobweb");
            if (slot < 0) { reset(); return; }
            int previous = mc.player.getInventory().getSelectedSlot();
            if (autoSwap.get() && !com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot)) return;
            oldSlot = previous; ownedSlot = mc.player.getInventory().getSelectedSlot(); owner = mc.player;
            stage = 1;
            return;
        }

        if (!ModuleUtils.matches(mc.player.getMainHandItem(), "cobweb")) { reset(); return; }
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            reset();
            return;
        }

        Placement.Result result = Placement.useOnFace(mc, "cobweb", hit.getBlockPos(), hit.getDirection());
        if (result == Placement.Result.WAITING) return;
        reset();
    }

    @Override
    protected void onDisable() { reset(); }

    @Override
    protected void onEnable() { reset(); }
    @Override public void onInputSuspended() { reset(); }

    private void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (oldSlot >= 0 && owner == mc.player && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot && ownedSlot != oldSlot) {
            com.baggute.cheatclient.util.Stealth.restoreSlot(mc, oldSlot);
        }
        armed = false;
        stage = 0;
        oldSlot = ownedSlot = -1; owner = null;
        armedTicks = 0;
    }
}
