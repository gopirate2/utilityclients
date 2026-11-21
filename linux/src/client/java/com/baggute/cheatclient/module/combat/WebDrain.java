package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

/**
 * Buckets water onto the cobweb the player is stuck in.
 *
 * <p>The hotbar slot is remembered and put back once the web is gone. The old
 * version selected the bucket and never returned, so the player finished every
 * fight holding a water bucket instead of their sword.
 */
public final class WebDrain extends Module {

    private final BoolSetting autoSwap = new BoolSetting("autoSwap", "Switch to water bucket", true);
    private final IntSetting delay = new IntSetting("delay", "Ticks between drains", 4, 1, 20);

    private int cooldown;
    private int oldSlot = -1;
    private int ownedSlot = -1;
    private net.minecraft.client.player.LocalPlayer owner;
    private BlockPos pouredCell;
    private int confirmTicks;

    public WebDrain() {
        super("WebDrain", "Uses water to drain cobwebs trapping the player.", Category.COMBAT);
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
        if(mc.gui.screen()!=null||mc.isPaused()){restore(mc);pouredCell=null;return;}
        if(pouredCell!=null){
            confirmTicks++;
            if(!isInCobweb(mc)&&confirmTicks>=2){
                if(mc.player.getMainHandItem().is(net.minecraft.world.item.Items.BUCKET)
                        &&mc.level.getFluidState(pouredCell).isSource()){
                    if(!com.baggute.cheatclient.util.BucketActions.use(mc,pouredCell,true)&&confirmTicks<20)return;
                }
                restore(mc);pouredCell=null;return;
            }
            if(confirmTicks>20){restore(mc);pouredCell=null;cooldown=delay.get();}
            return;
        }

        if (!isInCobweb(mc)) {
            restore(mc);
            return;
        }
        if (cooldown > 0) return;
        // The pour direction is the reported view, so a rotation on this tick would
        // resolve against the previous one.
        if (Stealth.rotatedThisTick() || Stealth.swappedThisTick()) return;

        int slot = ModuleUtils.findHotbarItem(mc, "water_bucket");
        if (slot < 0) { restore(mc); return; }

        if (!ModuleUtils.matches(mc.player.getMainHandItem(), "water_bucket")) {
            if (!autoSwap.get()) return;
            int previous = mc.player.getInventory().getSelectedSlot();
            if (!com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot)) return;
            if (oldSlot < 0) oldSlot = previous;
            ownedSlot = slot; owner = mc.player;
            return; // the slot packet lands next tick
        }

        BlockPos feet=mc.player.blockPosition();
        for(int y=1;y>=0;y--)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
            BlockPos cell=feet.offset(x,y,z);
            if(com.baggute.cheatclient.util.BucketActions.use(mc,cell,false)){
                pouredCell=cell;confirmTicks=0;cooldown=delay.get();return;
            }
        }
    }

    private void restore(Minecraft mc) {
        if (oldSlot >= 0 && owner == mc.player && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot
                && ownedSlot != oldSlot) {
            com.baggute.cheatclient.util.Stealth.restoreSlot(mc, oldSlot);
        }
        oldSlot = ownedSlot = -1; owner = null;
    }

    /** Cobwebs are not solid, so isInWall()/isInWater() never fire while stuck in one. */
    private boolean isInCobweb(Minecraft mc) {
        var box=mc.player.getBoundingBox().deflate(.001);
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(mc.level.getBlockState(pos).is(Blocks.COBWEB))return true;
        return false;
    }

    @Override
    protected void onDisable() {
        restore(Minecraft.getInstance());
        cooldown = 0;
        pouredCell=null;confirmTicks=0;
    }
}
