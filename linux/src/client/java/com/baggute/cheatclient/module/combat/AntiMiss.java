package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Rejects attack packets that cannot do anything: dead targets, yourself, and
 * (optionally) anything outside the range the server accepts.
 *
 * <p>This used to clear {@code keyAttack} on the client, which killed the input
 * until the next physical press and blinded every other click-gated module. The
 * filter now runs inside {@code MultiPlayerGameMode.attack} instead, so the input
 * state and the click itself are untouched.
 */
public final class AntiMiss extends Module {

    private final BoolSetting reachCheck =
            new BoolSetting("reachCheck", "Block attacks the server would reject", true);

    public AntiMiss() {
        super("AntiMiss", "Blocks left-click swings until the crosshair is on a player or mob.", Category.COMBAT);
        addSetting(reachCheck);
    }

    /** Called from the game mode mixin before an attack packet is built. */
    public static boolean shouldBlockClick(net.minecraft.client.Minecraft mc) {
        if (UtilityClient.moduleManager == null || mc == null || mc.player == null) return false;
        Module module = UtilityClient.moduleManager.getByName("AntiMiss");
        if (!(module instanceof AntiMiss anti) || !anti.isEnabled()) return false;
        if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity living)) return true;
        return shouldBlock(mc.player, living);
    }

    /** Entity packet validation also applies to module-generated attacks. */
    public static boolean shouldBlock(Player player, Entity target) {
        if (UtilityClient.moduleManager == null) return false;
        Module module = UtilityClient.moduleManager.getByName("AntiMiss");
        if (!(module instanceof AntiMiss anti) || !anti.isEnabled()) return false;

        if (target == null || target == player) return true;
        if (!target.isAlive() || !target.isAttackable()) return true;
        // Eye-to-box against the arbiter's limit, which tracks interaction range plus
        // the latency buffer. A flat 3.0 blocked hits the server would have accepted
        // and let through hits it drops on arrival.
        return anti.reachCheck.get() && !Stealth.inEntityReach(player, target);
    }
}
