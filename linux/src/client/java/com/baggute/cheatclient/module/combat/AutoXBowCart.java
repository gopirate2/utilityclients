// language: Java, file: AutoXBowCart.java, target: Fabric 26.2
package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Rail cart primed by a Flame bow.
 *
 * <p>The previous version required a crossbow in the hotbar and fired it, which
 * cannot work: a crossbow shoots bolts, and a bolt is not on fire, so the cart was
 * never primed. A bow with Flame does light its arrow, so that is what this module
 * gates on now, with a Fire Aspect sword as the fallback.
 *
 * <p>Placement carries the same fixes as {@link AutoCart}: the rail cell must have
 * a solid top face under it, and a cell the server refuses is skipped for a second
 * instead of being retried in a loop.
 */
public class AutoXBowCart extends Module {

    private final KeybindSetting activate;
    private final DoubleSetting range;
    private final IntSetting chargeTicks;
    private final IntSetting placeDelay;
    private final BoolSetting lightFire;

    private Stage stage = Stage.IDLE;
    private int cooldown = 0;
    private int stageTicks = 0;
    private int charge = 0;
    private BlockPos railPos = null;
    private BlockPos badSpot = null;
    private int badTicks = 0;
    private boolean manualRequest = false;

    private enum Stage { IDLE, RAIL, CART, FIRE }

    public AutoXBowCart() {
        super("AutoXBowCart", "Places a rail and TNT minecart, then primes it with a Flame bow.", Category.COMBAT);

        this.activate = new KeybindSetting("fire", "Key to run one cart cycle", -1, false);
        this.range = new DoubleSetting("range", "Target range", 6.0, 2.0, 12.0);
        this.chargeTicks = new IntSetting("chargeTicks", "Bow draw ticks", 20, 5, 60);
        this.placeDelay = new IntSetting("placeDelay", "Ticks between cycles", 20, 5, 80);
        this.lightFire = new BoolSetting("lightFire", "Prime the cart with fire", true);

        addSetting(activate);
        addSetting(range);
        addSetting(chargeTicks);
        addSetting(placeDelay);
        addSetting(lightFire);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null || mc.isPaused()) { release(mc); return; }
        if (cooldown > 0) cooldown--;
        if (badTicks > 0) { badTicks--; if (badTicks == 0) badSpot = null; }
        if (stage == Stage.IDLE) return;
        manualRequest = false;
        if (++stageTicks > 160) { cancel(mc); return; }

        switch (stage) {
            case RAIL -> {
                if (railPos == null) { cancel(mc); return; }
                if (!mc.level.getBlockState(railPos).canBeReplaced()) { cancel(mc); return; }
                if (!Placement.supportIsSolid(mc, railPos)) { blacklist(railPos); cancel(mc); return; }
                Placement.Result result = Placement.useOn(mc, "rail", railPos.below());
                if (result == Placement.Result.WAITING) return;
                if (result != Placement.Result.USED) { blacklist(railPos); cancel(mc); return; }
                stage = Stage.CART;
                com.baggute.cheatclient.module.misc.Debug.stage("AutoXBowCart", stage.name());
                stageTicks = 0;
            }
            case CART -> {
                if (railPos == null) { cancel(mc); return; }
                Placement.Result result = Placement.useOn(mc, "tnt_minecart", railPos);
                if (result == Placement.Result.WAITING) return;
                if (result != Placement.Result.USED) { cancel(mc); return; }
                stage = lightFire.get() ? Stage.FIRE : Stage.IDLE;
                stageTicks = 0;
            }
            case FIRE -> {
                Entity cart = findCart(mc);
                if (cart == null) return; // wait for the add-entity packet

                int bow = Ignition.flameBowSlot(mc);
                if (bow >= 0) {
                    if (mc.player.getInventory().getSelectedSlot() != bow) {
                        ModuleUtils.select(mc, bow);
                        charge = 0;
                        return;
                    }
                    if (!Ignition.aimServerSide(mc, cart.position().add(0, cart.getBbHeight() * 0.5, 0))) return;
                    if (!mc.player.isUsingItem()) {
                        Stealth.useItem(mc, InteractionHand.MAIN_HAND);
                        charge = 0;
                        return;
                    }
                    if (++charge < chargeTicks.get()) return;
                    mc.gameMode.releaseUsingItem(mc.player);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    cancel(mc);
                    cooldown = placeDelay.get();
                    return;
                }

                int sword = Ignition.fireAspectSlot(mc);
                if (sword < 0) {
                    Notifications.push("cart: need a flame bow or fire aspect sword", false);
                    cancel(mc);
                    cooldown = placeDelay.get();
                    return;
                }
                if (mc.player.getInventory().getSelectedSlot() != sword) {
                    ModuleUtils.select(mc, sword);
                    return;
                }
                if (!Ignition.aimServerSide(mc, cart.position().add(0, cart.getBbHeight() * 0.5, 0))) return;
                if (!Stealth.attack(mc, cart)) return;
                cancel(mc);
                cooldown = placeDelay.get();
            }
            default -> cancel(mc);
        }
    }

    @Override
    public void onRenderFrame(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || stage != Stage.IDLE) return;
        if (cooldown > 0 && !manualRequest) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;
        if (ModuleUtils.findHotbarItem(mc, "rail") < 0
                || ModuleUtils.findHotbarItem(mc, "tnt_minecart") < 0) return;
        if (lightFire.get() && !Ignition.canPrime(mc)) return;

        Player target = ModuleUtils.nearestPlayer(mc, range.get());
        if (target == null) return;

        BlockPos candidate = target.blockPosition();
        if (candidate.distSqr(mc.player.blockPosition()) > range.get() * range.get()) return;

        if (!mc.level.getBlockState(candidate).canBeReplaced()
                || !Placement.supportIsSolid(mc, candidate)) {
            candidate = Placement.findSpot(mc, candidate, 2.0, badSpot);
            if (candidate == null) return;
        }

        railPos = candidate;
        stage = Stage.RAIL;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoXBowCart", stage.name());
        stageTicks = 0;
    }

    private void blacklist(BlockPos pos) {
        badSpot = pos.immutable();
        badTicks = 40;
    }

    private Entity findCart(Minecraft mc) {
        if (railPos == null || mc.level == null) return null;
        Vec3 center = Vec3.atCenterOf(railPos).add(0, 0.5, 0);
        for (Entity entity : mc.level.entitiesForRendering()) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
            if (!id.equals("tnt_minecart")) continue;
            if (entity.position().distanceToSqr(center) > 9.0) continue;
            return entity;
        }
        return null;
    }

    private void release(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
    }

    private void cancel(Minecraft mc) {
        release(mc);
        stage = Stage.IDLE;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoXBowCart", stage.name());
        stageTicks = 0;
        charge = 0;
        railPos = null;
    }

    @Override
    public void onRawKey(int keyCode, int action) {
        if (action != 1) return;
        if (activate.getKey() == -1 || keyCode != activate.getKey()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() != null || mc.isPaused()) return;

        // Force one cycle past the cooldown gate; cleared as soon as it starts.
        if (stage == Stage.IDLE) manualRequest = true;
    }

    @Override
    protected void onEnable() { cancel(Minecraft.getInstance()); cooldown = 0; badSpot = null; badTicks = 0; }

    @Override
    protected void onDisable() { cancel(Minecraft.getInstance()); cooldown = 0; badSpot = null; badTicks = 0; }
}
