package com.baggute.cheatclient.testing;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.GameplayHooks;
import com.baggute.cheatclient.settings.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises real woven game methods; callable only through the opt-in test bridge. */
public final class LiveRegressionChecks {
    private LiveRegressionChecks() {}
    public static JsonObject run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw new IllegalStateException("Test world required");
        var player = mc.player;
        var velocity = UtilityClient.moduleManager.getByName("Velocity");
        var fullbright = UtilityClient.moduleManager.getFullbright();
        Map<Setting, Object> values = new LinkedHashMap<>();
        velocity.getSettings().forEach(s -> values.put(s, s.getValue()));
        boolean enabled = velocity.isEnabled(), bright = fullbright.isEnabled(), ground = player.onGround();
        Vec3 movement = player.getDeltaMovement();
        JsonObject result = new JsonObject(); JsonArray checks = new JsonArray(); result.add("checks", checks);
        try {
            set(velocity, "Chance %", 100.0); set(velocity, "Only When Targeting", false);
            velocity.setEnabled(true);
            set(velocity, "Horizontal %", 50.0); set(velocity, "Vertical %", 25.0);
            player.lerpMotion(new Vec3(2, 4, 6));
            check(player.getDeltaMovement().equals(new Vec3(1, 1, 3)), "entity-motion axes", checks);
            set(velocity, "Horizontal %", 0.0); set(velocity, "Vertical %", 100.0);
            player.lerpMotion(new Vec3(2, 4, 6));
            check(player.getDeltaMovement().equals(new Vec3(0, 4, 0)), "independent vertical motion", checks);
            set(velocity, "Horizontal %", 0.0); set(velocity, "Vertical %", 0.0);
            Vec3 base = new Vec3(0.6, 0.2, 0.8); player.setDeltaMovement(base); player.setOnGround(true);
            player.knockback(0.4, 1, 0, player.damageSources().generic(), 1f, false);
            check(player.getDeltaMovement().distanceTo(base) < 1e-9, "local impulse preserves base movement", checks);
            player.setDeltaMovement(base);
            mc.getConnection().handleExplosion(new net.minecraft.network.protocol.game.ClientboundExplodePacket(
                    player.position(), 0f, 0, java.util.Optional.of(new Vec3(0.6, 0.4, -0.3)),
                    net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                    net.minecraft.util.random.WeightedList.<net.minecraft.core.particles.ExplosionParticleInfo>builder().build()));
            check(player.getDeltaMovement().distanceTo(base) < 1e-9, "explosion impulse hook", checks);
            velocity.setEnabled(false); player.lerpMotion(new Vec3(2, 4, 6));
            check(player.getDeltaMovement().equals(new Vec3(2, 4, 6)), "disabled velocity is vanilla", checks);
            var other = new net.minecraft.world.entity.decoration.ArmorStand(mc.level, player.getX(), player.getY(), player.getZ());
            velocity.setEnabled(true); other.lerpMotion(new Vec3(2, 4, 6));
            check(other.getDeltaMovement().equals(new Vec3(2, 4, 6)), "other entities keep vanilla motion", checks);
            fullbright.setEnabled(true);
            double gamma = mc.options.gamma().get();
            check(gamma >= 0 && gamma <= 1, "brightness option stays serializable", checks);
            check(((Number) GameplayHooks.lightmapOption(mc.options.gamma())).doubleValue() == fullbright.effectiveLevel(), "lightmap brightness override", checks);
            check(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("baritone"), "nested Baritone discovered", checks);
            check(baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("help"), "real Baritone command manager", checks);
            check(kaptainwutax.seedcrackerX.SeedCracker.get() != null
                    && kaptainwutax.seedcrackerX.Features.STRUCTURE_TYPES.size() == 10, "all ten structure feature tables initialized", checks);
            check(kaptainwutax.seedcrackerX.SeedCracker.get().getDataStorage().hashedSeedData != null, "login seed hash collected", checks);
            var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher();
            check(dispatcher != null && dispatcher.getRoot().getChild(kaptainwutax.seedcrackerX.init.ClientCommands.PREFIX) != null,
                    "SeedCracker client command registered", checks);
            result.addProperty("ok", true);
            return result;
        } finally {
            values.forEach(Setting::setValue);
            velocity.setEnabled(enabled); fullbright.setEnabled(bright);
            player.setDeltaMovement(movement); player.setOnGround(ground);
        }
    }
    private static void set(com.baggute.cheatclient.module.Module module, String name, Object value) {
        module.getSettings().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow().setValue(value);
    }
    private static void check(boolean passed, String message, JsonArray checks) {
        if (!passed) throw new AssertionError(message);
        checks.add(message);
    }
}
