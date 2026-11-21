package com.baggute.cheatclient.testing;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.combat.AutoXbow;
import com.baggute.cheatclient.module.combat.LungeSwap;
import com.baggute.cheatclient.util.Stealth;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;

/** Opt-in end-to-end checks, restricted to the development client's creative test world. */
public final class ComboRegressionChecks {
    private ComboRegressionChecks() {}
    private static Run active;
    private static JsonObject result = new JsonObject();
    private static boolean registered;
    public static JsonObject start(boolean xbowOnly) {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) return status();
        if (mc.getSingleplayerServer() == null || mc.player == null || mc.gameMode.getPlayerMode() != GameType.CREATIVE)
            throw new IllegalStateException("Isolated creative development world required");
        if (!registered) { ClientTickEvents.END_CLIENT_TICK.register(ComboRegressionChecks::tick); registered = true; }
        result = new JsonObject(); result.addProperty("running", true);
        active = new Run(mc, xbowOnly); active.setup(); return status();
    }
    public static JsonObject status() { return result.deepCopy(); }
    private static void tick(Minecraft mc) {
        if (active == null) return;
        try { active.tick(); }
        catch (Throwable failure) { result.addProperty("ok", false); result.addProperty("error", failure.toString()); finish(); }
    }
    private static void finish() {
        if (active != null) active.restore(); active = null; result.addProperty("running", false);
    }
    private static final class Run {
        final Minecraft mc;
        final Map<Module, Boolean> enabled = new LinkedHashMap<>();
        final ItemStack[] hotbar = new ItemStack[9];
        final int slot;
        final Vec3 position;
        final float yaw, pitch;
        final boolean sprint;
        final JsonArray checks = new JsonArray();
        final Map<com.baggute.cheatclient.settings.Setting, Object> settings = new LinkedHashMap<>();
        final LungeSwap lunge;
        final AutoXbow xbow;
        final boolean xbowOnly;
        int ticks, stage, stageTicks;
        boolean sawSpear, fired;
        int observedCart = -1;
        volatile boolean burningArrow, exploded;
        float viewYaw, viewPitch;
        boolean viewSprint;
        Run(Minecraft mc, boolean xbowOnly) {
            this.xbowOnly = xbowOnly;
            this.mc = mc; slot = mc.player.getInventory().getSelectedSlot(); position = mc.player.position();
            yaw = mc.player.getYRot(); pitch = mc.player.getXRot(); sprint = mc.player.isSprinting();
            lunge = (LungeSwap) UtilityClient.moduleManager.getByName("LungeSwap");
            xbow = (AutoXbow) UtilityClient.moduleManager.getByName("AutoXbow");
            for (Module module : UtilityClient.moduleManager.getModules()) enabled.put(module, module.isEnabled());
            for (Module module : List.of(lunge, xbow)) for (var setting : module.getSettings()) settings.put(setting, setting.getValue());
            for (int i = 0; i < 9; i++) hotbar[i] = mc.player.getInventory().getItem(i).copy();
            result.add("checks", checks);
        }
        void setup() {
            enabled.keySet().stream().filter(module -> !module.getName().equals("SeedCrackerX"))
                    .forEach(module -> module.setEnabled(false));
            lunge.getSettings().stream().filter(s -> s.getName().equals("requireTarget") || s.getName().equals("auto")).forEach(s -> s.setValue(false));
            xbow.getSettings().stream().filter(s -> s.getName().equals("missChance")).forEach(s -> s.setValue(0.0));
            command("tp @a 0.5 301 0.5 0 28");
            mc.getSingleplayerServer().execute(() -> {
                var world = mc.getSingleplayerServer().overworld();
                for (int x = -3; x <= 3; x++) for (int z = -1; z <= 6; z++) {
                    world.setBlock(new BlockPos(x, 300, z), Blocks.STONE.defaultBlockState(), 3);
                    for (int y = 301; y <= 303; y++) world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            });
        }
        void tick() {
            if (++ticks > 360) throw new AssertionError("combo timeout in stage " + stage);
            stageTicks++;
            if (stage == 0) {
                if (mc.player.getY() < 300.9 || !mc.level.getBlockState(new BlockPos(0, 300, 3)).is(Blocks.STONE)) return;
                for (int i = 0; i < 9; i++) item(i, ItemStack.EMPTY);
                if (xbowOnly) { stage = 4; stageTicks = 0; return; }
                ItemStack spear = new ItemStack(Items.DIAMOND_SPEAR);
                var key = ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("lunge"));
                spear.enchant(mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), 5);
                item(1, spear);
                Stealth.swapSlot(mc, 0);
                lunge.setEnabled(true);
                viewYaw = mc.player.getYRot(); viewPitch = mc.player.getXRot();
                viewSprint = mc.player.isSprinting();
                require(lunge.arm(mc), "Lunge hotkey arms"); stage = 1; stageTicks = 0;
            } else if (stage == 1) {
                require(mc.player.getYRot() == viewYaw && mc.player.getXRot() == viewPitch && mc.player.isSprinting() == viewSprint,
                        "Lunge leaves camera/sprint unchanged");
                if (Stealth.attackedThisTick()) {
                    require(mc.player.getMainHandItem().is(Items.DIAMOND_SPEAR), "stab uses spear"); sawSpear = true;
                }
                if (!sawSpear || mc.player.getInventory().getSelectedSlot() != 0) return;
                checks.add("fast-hand -> immediate spear stab -> fast-hand; camera and sprint preserved");
                lunge.setEnabled(false);
                command("tp @a 0.5 301 0.5 0 28"); stage = 4; stageTicks = 0;
            } else if (stage == 4) {
                if (stageTicks < 30) { mc.player.setDeltaMovement(Vec3.ZERO); return; }
                if (mc.player.position().distanceTo(new Vec3(0.5, 301, 0.5)) > 0.3) return;
                mc.player.setDeltaMovement(Vec3.ZERO);
                item(2, new ItemStack(Items.RAIL, 8)); item(3, new ItemStack(Items.TNT_MINECART));
                item(4, new ItemStack(Items.FLINT_AND_STEEL));
                ItemStack bow = new ItemStack(Items.CROSSBOW);
                bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));
                item(5, bow);
                mc.hitResult = new BlockHitResult(new Vec3(0.5, 301, 3.5), net.minecraft.core.Direction.UP, new BlockPos(0, 300, 3), false);
                xbow.setEnabled(true);
                require(xbow.trigger(), "crosshair AutoXbow trigger");
                viewYaw = mc.player.getYRot(); viewPitch = mc.player.getXRot(); stage = 2; stageTicks = 0;
            } else if (stage == 2) {
                for (var entity : mc.level.entitiesForRendering())
                    if (entity instanceof MinecartTNT && entity.position().distanceTo(new Vec3(0.5, 301, 3.5)) < 2)
                        observedCart = entity.getId();
                observeShot();
                require(mc.player.getYRot() == viewYaw && mc.player.getXRot() == viewPitch, "AutoXbow camera invariance");
                ItemStack bow = mc.player.getInventory().getItem(5);
                if (!CrossbowItem.isCharged(bow)) fired = true;
                if (!fired || mc.player.getInventory().getSelectedSlot() != 0) return;
                checks.add("silent crosshair rail/cart and fire-lane placement; directed crossbow shot; slot restored");
                stage = 3; stageTicks = 0;
            } else if (stage == 3) {
                observeShot();
                if (exploded) {
                    checks.add("server TNT cart exploded immediately and destroyed its support after the directed shot");
                    checks.add("burning projectile observed: " + burningArrow);
                    result.addProperty("ok", true); finish(); return;
                }
                if (stageTicks > 40) throw new AssertionError("server TNT cart did not explode after shot (cart=" + observedCart + ", burningArrow=" + burningArrow + ")");
            }
        }
        void observeShot() {
            int id = observedCart;
            mc.getSingleplayerServer().execute(() -> {
                var world = mc.getSingleplayerServer().overworld();
                for (var entity : world.getEntities((net.minecraft.world.entity.Entity) null, new AABB(-3, 300, -1, 4, 304, 7)))
                    if (entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow && entity.isOnFire()) burningArrow = true;
                // A burning arrow calls MinecartTNT.explode directly; isPrimed()
                // need never become true. Missing cart plus destroyed stone
                // establishes the server-side explosion, not just an item use.
                if (id >= 0 && world.getEntity(id) == null && world.getBlockState(new BlockPos(0, 300, 3)).isAir()) exploded = true;
            });
        }
        void item(int index, ItemStack stack) {
            mc.player.getInventory().setItem(index, stack.copy());
            mc.gameMode.handleCreativeModeItemAdd(stack.copy(), 36 + index);
        }
        void command(String command) {
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        }
        void restore() {
            lunge.setEnabled(false); xbow.setEnabled(false);
            for (int i = 0; i < 9; i++) item(i, hotbar[i]);
            Stealth.restoreSlot(mc, slot);
            command(String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f %.3f %.3f", position.x, position.y, position.z, yaw, pitch));
            enabled.forEach(Module::setEnabled);
            settings.forEach(com.baggute.cheatclient.settings.Setting::setValue);
        }
        void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
    }
}
