// language: Java, file: HitSelect.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.util.Stealth;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Withholds your clicks until the attack bar is actually full, then lands the
 * swing. Holding attack produces hits at exactly 1.0 charge instead of a spray of
 * 0.7-charge taps, which is what makes the knockback and the damage predictable.
 */
public class HitSelect extends Module {

    private final DoubleSetting minCharge;
    private final BoolSetting playersOnly;
    private final DoubleSetting range;
    private final BoolSetting requireMiss;

    public HitSelect() {
        super("HitSelect", "Only swings when the attack bar is full.", Category.COMBAT);

        this.minCharge = new DoubleSetting("minCharge", "Minimum attack charge to swing", 0.98, 0.5, 1.0);
        this.playersOnly = new BoolSetting("playersOnly", "Ignore mobs", true);
        this.range = new DoubleSetting("range", "Reach to check for a target", 3.0, 1.0, 6.0);
        this.requireMiss = new BoolSetting("requireMiss", "Skip the swing if it would miss anyway", true);

        addSetting(minCharge);
        addSetting(playersOnly);
        addSetting(range);
        addSetting(requireMiss);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (!mc.options.keyAttack.isDown()) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;

        if (mc.player.getAttackStrengthScale(0.95f) < minCharge.get()) return;

        LivingEntity target = pickTarget(mc);
        if (target == null) {
            // "requireMiss" off means swing anyway; before this branch the setting
            // changed nothing, because the null case simply did nothing.
            if (requireMiss.get()) return;
            mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }

        Stealth.attack(mc, target);
    }

    /**
     * Called from the attack mixin. Withholding the click is the whole module: the
     * vanilla input loop still swings on every tick the button is down, so without
     * this the module only added a second, better-timed hit on top of the spray.
     */
    public static boolean shouldBlock(Minecraft mc) {
        if (UtilityClient.moduleManager == null || mc.player == null) return false;
        Module module = UtilityClient.moduleManager.getByName("HitSelect");
        if (!(module instanceof HitSelect select) || !select.isEnabled()) return false;
        return mc.player.getAttackStrengthScale(0.95f) < select.minCharge.get();
    }

    private LivingEntity pickTarget(Minecraft mc) {
        double best = range.get() * range.get();
        LivingEntity found = null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (com.baggute.cheatclient.friends.FriendsManager.isFriend(entity)) continue;
            if (!(entity instanceof LivingEntity living) || living == mc.player) continue;
            if (playersOnly.get() && !(living instanceof Player)) continue;
            if (living.isDeadOrDying() || living.getHealth() <= 0) continue;
            double d = living.distanceToSqr(mc.player);
            if (d < best) { best = d; found = living; }
        }
        return found;
    }
}
