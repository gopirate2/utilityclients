// language: Java, file: AutoMine.java, target: Fabric 26.2
package com.baggute.cheatclient.module.automation;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.module.apex.ApexSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Keeps breaking the block under the crosshair until it is gone.
 *
 * <p>Mining is a three-step conversation with the game: start a block, continue it
 * every tick, and abort it when the block goes away. The old version only ever
 * continued, never aborted, so looking at a new block left the previous one
 * half-mined and its progress bar stuck on screen — and switching the module off
 * mid-swing left the game mining on its own.
 */
public final class AutoMine extends Module {

    private final BoolSetting holdAttack =
            new BoolSetting("holdAttack", "Mine without holding attack", true);
    private final StringSetting blockFilter = new StringSetting("blockFilter", "Comma-separated block IDs or fragments; empty mines all", "");
    private final IntSetting minDurability = new IntSetting("minDurability", "Stop at this remaining tool durability; 0 disables protection", 0, 0, 100);

    private BlockPos target;

    public AutoMine() {
        super("AutoMine", "Continues mining the block under the crosshair.", Category.AUTOMATION);
        addSetting(holdAttack);
        addSetting(blockFilter);
        addSetting(minDurability);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || mc.gui.screen() != null || mc.isPaused()) {
            abort(mc);
            return;
        }

        if (!(mc.hitResult instanceof BlockHitResult hit)
                || mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            abort(mc);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        var held = mc.player.getMainHandItem();
        if (!ApexSettings.matches(blockFilter.get(), mc.level.getBlockState(pos).getBlock().toString())
                || (minDurability.get() > 0 && held.isDamageableItem()
                && held.getMaxDamage() - held.getDamageValue() <= minDurability.get())) {
            abort(mc);
            return;
        }
        if (mc.level.getBlockState(pos).isAir()) {
            abort(mc);
            return;
        }

        if (!holdAttack.get() && !mc.options.keyAttack.isDown()) {
            abort(mc);
            return;
        }

        if (!pos.equals(target)) {
            // The previous block is still being tracked by the game's mining state.
            abort(mc);
            target = pos.immutable();
            mc.gameMode.startDestroyBlock(pos, hit.getDirection());
            return;
        }

        mc.gameMode.continueDestroyBlock(pos, hit.getDirection());
    }

    private void abort(Minecraft mc) {
        if (target == null) return;
        target = null;
        if (mc.gameMode != null && mc.player != null) mc.gameMode.stopDestroyBlock();
    }

    @Override
    protected void onDisable() {
        abort(Minecraft.getInstance());
    }
}
