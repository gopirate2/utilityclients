package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.*;
import com.baggute.cheatclient.util.ModuleUtils;
import com.baggute.cheatclient.util.Stealth;
import com.baggute.cheatclient.util.WindChargeIntercept;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;

/** Throw a pearl, then intercept its flight with the earliest available wind charge. */
public final class AutoPearlCatch extends Module {
    private enum Phase { IDLE, PEARL, CHARGE, RESTORE }

    private final KeybindSetting activate = new KeybindSetting("PearlCatch", "Throw one pearl-catch sequence", -1, false);
    private final BoolSetting autoTrigger = new BoolSetting("autoTrigger", "Follow your own newly thrown pearls", true);
    private final BoolSetting requireWindCharge = new BoolSetting("requireWindCharge", "Require a wind charge in either hand or the hotbar", true);
    private final IntSetting pearlDelay = new IntSetting("pearlDelay", "Ticks between pearl and wind charge (2 ticks = about 100 ms)", 2, 2, 10);
    private final IntSetting restoreDelay = new IntSetting("restoreDelay", "Ticks before returning to the previous slot", 1, 1, 20);
    private final BoolSetting autoAim = new BoolSetting("autoAim", "Predict the pearl's flight and aim the charge at its intercept", true);
    private final BoolSetting aimDown = new BoolSetting("aimDown", "Use a fixed pitch when autoAim is off", false);
    private final DoubleSetting aimPitch = new DoubleSetting("aimPitch", "Fixed charge pitch when autoAim is off", 65, 10, 90);

    private final Set<UUID> seenPearls = new HashSet<>();
    private final Deque<Prediction> predictions = new ArrayDeque<>();
    public record LocalThrow(LocalPlayer player, ClientLevel level, int slot, Vec3 position, Vec3 velocity) {}
    private record Choice(InteractionHand hand, int slot) {}
    private record Prediction(LocalThrow shot, long tick) {}
    private Prediction activePrediction;
    private boolean primed;
    private Phase phase = Phase.IDLE;
    private long clock, pearlTick, deadline, restoreAt;
    private LocalPlayer owner;
    private ClientLevel world;
    private int previousSlot = -1, ownedSlot = -1;
    private ThrownEnderpearl pearl;
    private Vec3 projectedPosition, projectedVelocity;

    public AutoPearlCatch() {
        super("AutoPearlCatch", "Throws a pearl and predicts a wind-charge intercept from its flight angle.", Category.COMBAT);
        for (Setting setting : new Setting[]{activate, autoTrigger, requireWindCharge, pearlDelay,
                restoreDelay, autoAim, aimDown, aimPitch}) addSetting(setting);
    }

    @Override public void onRawKey(int key, int action) {
        if (action != GLFW.GLFW_PRESS || key < 0 || key != activate.getKey() || !isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!available(mc) || phase != Phase.IDLE) return;
        if (!has(mc, "ender_pearl") || (requireWindCharge.get() && !has(mc, "wind_charge"))) {
            message("Put an ender pearl and a wind charge in your hands or hotbar.");
            return;
        }
        prime(mc);
        own(mc);
        phase(Phase.PEARL);
        throwPearl(mc);
    }

    @Override public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            clear(); seenPearls.clear(); predictions.clear(); primed = false; return;
        }
        if (owner != null && (owner != mc.player || world != mc.level)) {
            clear(); seenPearls.clear(); predictions.clear(); primed = false;
        }
        if (!available(mc)) { abort(mc); return; }
        clock++;
        if (phase != Phase.IDLE && mc.player.getInventory().getSelectedSlot() != ownedSlot) {
            // Manual scrolling transfers slot ownership back to the player.
            clear(); return;
        }
        observe(mc);
        if (phase == Phase.IDLE) return;
        if (clock > deadline) { message("Sequence timed out before an interaction became available."); abort(mc); return; }
        switch (phase) {
            case PEARL -> throwPearl(mc);
            case CHARGE -> throwCharge(mc);
            case RESTORE -> restore(mc);
            default -> { }
        }
    }

    private void throwPearl(Minecraft mc) {
        Choice choice = select(mc, "ender_pearl");
        if (choice == null) return;
        LocalThrow shot = snapshot(mc);
        var result = use(mc, choice, "ender_pearl", mc.player.getYRot(), mc.player.getXRot());
        if (result == null || !result.consumesAction()) return;
        mc.player.swing(choice.hand());
        predict(shot);
        if (has(mc, "wind_charge")) phase(Phase.CHARGE);
        else { restoreAt = clock + restoreDelay.get(); phase(Phase.RESTORE); }
    }

    private void throwCharge(Minecraft mc) {
        if (clock - pearlTick < Math.max(2, pearlDelay.get())) return;
        if (pearl != null && pearl.isRemoved()) { abort(mc); return; }
        Choice choice = select(mc, "wind_charge");
        if (choice == null || !Stealth.canInteract(mc)) return;
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        if (autoAim.get()) {
            var motion = target();
            if (motion == null) { abort(mc); return; }
            double drag = pearl != null && pearl.isInWater() ? (double) 0.8f : WindChargeIntercept.PEARL_DRAG;
            double gravity = pearl != null && pearl.isNoGravity() ? 0 : WindChargeIntercept.PEARL_GRAVITY;
            Vec3 origin = mc.player.getEyePosition();
            var launch = WindChargeIntercept.solve(origin,
                    WindChargeIntercept.inherited(mc.player.getDeltaMovement(), mc.player.onGround()),
                    motion.position(), motion.velocity(), drag, gravity);
            if (launch == null || !clearFlight(mc, origin, motion, launch, drag, gravity)) {
                message("No clear, reachable wind-charge intercept for this pearl.");
                abort(mc); return;
            }
            yaw = launch.yaw(); pitch = launch.pitch();
        } else if (aimDown.get()) pitch = (float) aimPitch.get();
        // useItem carries yaw/pitch itself; no extra camera-steering or restore ticks.
        var result = use(mc, choice, "wind_charge", yaw, pitch);
        if (result == null || !result.consumesAction()) return;
        mc.player.swing(choice.hand());
        restoreAt = clock + restoreDelay.get(); deadline = restoreAt + 20;
        phase(Phase.RESTORE);
    }

    private WindChargeIntercept.Motion target() {
        if (pearl != null) return new WindChargeIntercept.Motion(pearl.getBoundingBox().getCenter(), pearl.getDeltaMovement());
        if (projectedPosition == null || projectedVelocity == null) return null;
        return WindChargeIntercept.advance(projectedPosition, projectedVelocity,
                WindChargeIntercept.PEARL_DRAG, WindChargeIntercept.PEARL_GRAVITY, (int) (clock - pearlTick));
    }

    private boolean clearFlight(Minecraft mc, Vec3 origin, WindChargeIntercept.Motion motion,
                                WindChargeIntercept.Launch launch, double drag, double gravity) {
        Vec3 windEnd = origin.add(launch.direction().scale(WindChargeIntercept.WIND_SPEED)
                .add(WindChargeIntercept.inherited(mc.player.getDeltaMovement(), mc.player.onGround()))
                .scale(launch.ticks()));
        if (!clearSegment(mc, origin, windEnd)) return false;
        Vec3 position = motion.position(), velocity = motion.velocity();
        for (double remaining = launch.ticks(); remaining > 0; remaining -= 1) {
            velocity = velocity.add(0, -gravity, 0).scale(drag);
            Vec3 next = position.add(velocity.scale(Math.min(1, remaining)));
            if (!clearSegment(mc, position, next)) return false;
            position = next;
        }
        return true;
    }

    private boolean clearSegment(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private Choice select(Minecraft mc, String item) {
        int current = mc.player.getInventory().getSelectedSlot();
        if (ModuleUtils.matches(mc.player.getOffhandItem(), item)) return new Choice(InteractionHand.OFF_HAND, current);
        if (ModuleUtils.matches(mc.player.getMainHandItem(), item)) return new Choice(InteractionHand.MAIN_HAND, current);
        int slot = ModuleUtils.findHotbarItem(mc, item);
        if (slot < 0) { abort(mc); return null; }
        return new Choice(InteractionHand.MAIN_HAND, slot);
    }

    private net.minecraft.world.InteractionResult use(Minecraft mc, Choice choice, String item, float yaw, float pitch) {
        try { return Stealth.useItemSelected(mc, choice.hand(), choice.slot(), item, yaw, pitch); }
        finally {
            if (owner == mc.player && world == mc.level && choice.hand() == InteractionHand.MAIN_HAND
                    && mc.player.getInventory().getSelectedSlot() == choice.slot()) ownedSlot = choice.slot();
        }
    }

    /** Called before vanilla consumes the stack, including the last pearl and creative throws. */
    public LocalThrow captureLocalThrow(Minecraft mc, Object player, Object hand) {
        if (!isEnabled() || !autoTrigger.get() || phase != Phase.IDLE || !available(mc)
                || player != mc.player || !(hand instanceof InteractionHand usedHand)
                || !ModuleUtils.matches(mc.player.getItemInHand(usedHand), "ender_pearl")
                || mc.player.getCooldowns().isOnCooldown(mc.player.getItemInHand(usedHand))
                || !has(mc, "wind_charge")) return null;
        return snapshot(mc);
    }

    /** Arm only after vanilla confirms successful use; no server entity round trip is needed. */
    public void completeLocalThrow(Minecraft mc, LocalThrow shot, net.minecraft.world.InteractionResult result) {
        if (shot == null || result == null || !result.consumesAction() || !isEnabled() || !autoTrigger.get()
                || phase != Phase.IDLE || !available(mc) || shot.player() != mc.player || shot.level() != mc.level
                || shot.slot() != mc.player.getInventory().getSelectedSlot() || !has(mc, "wind_charge")) return;
        prime(mc);
        own(mc);
        predict(shot);
        Stealth.markInteraction();
        phase(Phase.CHARGE);
    }

    private LocalThrow snapshot(Minecraft mc) {
        Vec3 origin = mc.player.getEyePosition().add(0,
                -0.1 + EntityTypes.ENDER_PEARL.getDimensions().height() * 0.5, 0);
        Vec3 velocity = WindChargeIntercept.look(mc.player.getYRot(), mc.player.getXRot())
                .scale(1.5).add(WindChargeIntercept.inherited(mc.player.getDeltaMovement(), mc.player.onGround()));
        return new LocalThrow(mc.player, mc.level, mc.player.getInventory().getSelectedSlot(), origin, velocity);
    }

    private void predict(LocalThrow shot) {
        projectedPosition = shot.position(); projectedVelocity = shot.velocity();
        pearlTick = clock; pearl = null; deadline = clock + 40;
        activePrediction = new Prediction(shot, clock);
        if (predictions.size() >= 32) predictions.removeFirst();
        predictions.addLast(activePrediction);
    }

    private boolean has(Minecraft mc, String item) {
        return ModuleUtils.matches(mc.player.getOffhandItem(), item) || ModuleUtils.findHotbarItem(mc, item) >= 0;
    }

    private void observe(Minecraft mc) {
        while (!predictions.isEmpty() && clock - predictions.peekFirst().tick() > 60) predictions.removeFirst();
        Set<UUID> live = new HashSet<>();
        ThrownEnderpearl fresh = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl candidate) || candidate.isRemoved() || candidate.getOwner() != mc.player) continue;
            live.add(candidate.getUUID());
            if (seenPearls.contains(candidate.getUUID())) continue;
            if (!predictions.isEmpty()) {
                Prediction confirmed = predictions.removeFirst();
                if (confirmed == activePrediction && phase == Phase.CHARGE) pearl = candidate;
                continue;
            }
            if (fresh == null || candidate.tickCount < fresh.tickCount) fresh = candidate;
        }
        if (primed && fresh != null) {
            if (phase == Phase.IDLE && autoTrigger.get() && has(mc, "wind_charge")) {
                own(mc); pearl = fresh; pearlTick = clock - Math.max(1, fresh.tickCount);
                phase(Phase.CHARGE);
            } else if (phase == Phase.CHARGE && pearl == null && projectedPosition != null) {
                pearl = fresh;
            }
        }
        seenPearls.clear(); seenPearls.addAll(live); primed = true;
    }

    private void prime(Minecraft mc) {
        seenPearls.clear();
        for (Entity entity : mc.level.entitiesForRendering())
            if (entity instanceof ThrownEnderpearl candidate && candidate.getOwner() == mc.player)
                seenPearls.add(candidate.getUUID());
        primed = true;
    }

    private void own(Minecraft mc) {
        owner = mc.player; world = mc.level;
        previousSlot = ownedSlot = mc.player.getInventory().getSelectedSlot();
        pearl = null; projectedPosition = projectedVelocity = null;
        activePrediction = null;
        deadline = clock + 40;
    }

    private boolean available(Minecraft mc) {
        return mc != null && mc.player != null && mc.level != null && mc.gameMode != null
                && mc.gui.screen() == null && !mc.isPaused() && com.baggute.cheatclient.util.PhysicalInput.focused(mc)
                && mc.player.isAlive() && !mc.player.isSpectator();
    }

    private void restore(Minecraft mc) {
        if (clock < restoreAt) return;
        if (ownedSlot == previousSlot || Stealth.swapSlot(mc, previousSlot)) clear();
    }

    private void abort(Minecraft mc) {
        if (mc != null && mc.player != null && owner == mc.player && world == mc.level
                && previousSlot >= 0 && mc.player.getInventory().getSelectedSlot() == ownedSlot)
            Stealth.restoreSlot(mc, previousSlot);
        clear();
    }

    private void clear() {
        owner = null; world = null; previousSlot = ownedSlot = -1;
        pearl = null; projectedPosition = projectedVelocity = null;
        activePrediction = null;
        phase(Phase.IDLE);
    }

    private void phase(Phase value) {
        phase = value;
        com.baggute.cheatclient.module.misc.Debug.stage("AutoPearlCatch", value.name());
    }

    private void message(String text) {
        Notifications.push("AutoPearlCatch: " + text, false);
        UtilityClient.LOGGER.warn("[AutoPearlCatch] {}", text);
    }

    @Override protected void onEnable() {
        clear(); seenPearls.clear(); predictions.clear(); primed = false; clock = 0;
    }
    @Override public void onInputSuspended() { abort(Minecraft.getInstance()); seenPearls.clear(); primed = false; }
    @Override protected void onDisable() {
        abort(Minecraft.getInstance()); seenPearls.clear(); predictions.clear(); primed = false;
    }
}
