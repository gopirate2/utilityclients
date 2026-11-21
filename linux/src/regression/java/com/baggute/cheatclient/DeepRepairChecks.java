package com.baggute.cheatclient;

import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.combat.BowAimbot;
import com.baggute.cheatclient.settings.*;
import com.baggute.cheatclient.util.ProjectileMath;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;

/** Reject imports before lifecycle effects, and test trajectories by independent simulation. */
public final class DeepRepairChecks {
    public static void run() throws Exception {
        configRollback();
        trajectories();
        System.out.println("PASS: complete config type validation, lifecycle rollback, reachable/unreachable bow and inherited-velocity pearl trajectories");
    }

    private static void configRollback() {
        ModuleManager manager = new ModuleManager();
        class Probe extends Module {
            int loads; boolean fail;
            Probe() { super("Probe", "Import regression", Category.MISC); }
            @Override public void onConfigLoaded() { loads++; if (fail) { fail = false; throw new IllegalStateException("injected lifecycle failure"); } }
        }
        Probe probe = new Probe();
        IntSetting count = new IntSetting("count", "Count", 3, 0, 20);
        DoubleSetting amount = new DoubleSetting("amount", "Amount", 2, 0, 20);
        ColorSetting color = new ColorSetting("color", "Color", 0xff112233);
        StringSetting text = new StringSetting("text", "Text", "kept");
        KeybindSetting bind = new KeybindSetting("keybind", "Bind", 67, false);
        for (Setting value : new Setting[]{count, amount, color, text, bind}) probe.addSetting(value);
        manager.getModules().add(probe);
        ConfigManager config = new ConfigManager(manager, Path.of("unused-regression-config.json"));
        JsonObject original = config.snapshot();
        for (String field : new String[]{"count", "amount", "color", "text", "keybind"}) {
            JsonObject bad = original.deepCopy();
            JsonObject settings = bad.getAsJsonObject("modules").getAsJsonObject("Probe").getAsJsonObject("settings");
            settings.addProperty("count", 9);
            if (field.equals("text")) settings.addProperty(field, 123);
            else settings.addProperty(field, "invalid");
            require(!config.applySnapshot(bad), "bad type rejected: " + field);
            require(count.get() == 3 && probe.loads == 0, "rejection has no partial mutation or lifecycle");
        }
        JsonObject bad = original.deepCopy();
        JsonObject settings = bad.getAsJsonObject("modules").getAsJsonObject("Probe").getAsJsonObject("settings");
        settings.addProperty("count", 3.5);
        require(!config.applySnapshot(bad), "fractional integer rejected");
        settings.addProperty("count", 3); settings.addProperty("amount", Double.NaN);
        require(!config.applySnapshot(bad), "nonfinite double rejected");
        bad = original.deepCopy();
        bad.getAsJsonObject("modules").getAsJsonObject("Probe").addProperty("enabled", "true");
        require(!config.applySnapshot(bad), "string enabled flag rejected");
        JsonObject next = original.deepCopy();
        next.getAsJsonObject("modules").getAsJsonObject("Probe").getAsJsonObject("settings").addProperty("count", 11);
        next.getAsJsonObject("modules").getAsJsonObject("Probe").addProperty("enabled", true);
        probe.fail = true;
        require(!config.applySnapshot(next), "failed lifecycle reported");
        require(count.get() == 3 && !probe.isEnabled() && probe.loads == 2, "previous settings and enabled state restored");
        require(config.applySnapshot(next) && count.get() == 11 && probe.isEnabled(), "valid import still commits");
    }

    private static void trajectories() throws Exception {
        for (Vec3 inherited : new Vec3[]{Vec3.ZERO, new Vec3(0.2, 0.18, -0.09)}) {
            for (double drag : new double[]{0.99, 1.0}) {
                Vec3 target = new Vec3(24, -2, 9);
                Vec3 launch = ProjectileMath.launchTo(target, inherited, 1.5, drag, 0.03, 160);
                require(launch != null && Math.abs(launch.length() - 1.5) < 1e-8, "bounded launch speed");
                Vec3 position = Vec3.ZERO, velocity = launch.add(inherited);
                double miss = Double.POSITIVE_INFINITY;
                for (int tick = 0; tick < 160; tick++) {
                    velocity = velocity.add(0, -0.03, 0).scale(drag);
                    double t = Math.max(0, Math.min(1, target.subtract(position).dot(velocity) / velocity.lengthSqr()));
                    miss = Math.min(miss, position.add(velocity.scale(t)).distanceTo(target));
                    position = position.add(velocity);
                }
                require(miss < 1e-7, "independent simulation reaches requested point");
            }
        }
        require(ProjectileMath.launchTo(new Vec3(10_000, 0, 0), Vec3.ZERO, 1.5, 0.99, 0.03, 160) == null, "unreachable pearl rejected");
        require(ProjectileMath.launchTo(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 1.5, 0.99, 0.03, 160) == null, "nonfinite target rejected");
        var solve = BowAimbot.class.getDeclaredMethod("solvePitch", double.class, double.class, double.class);
        var simulate = BowAimbot.class.getDeclaredMethod("simulate", float.class, double.class, double.class);
        solve.setAccessible(true); simulate.setAccessible(true);
        BowAimbot bow = new BowAimbot();
        for (double dy : new double[]{-4, 0, 3}) {
            float pitch = (float) solve.invoke(bow, 40.0, dy, 3.0);
            require(Float.isFinite(pitch), "reachable bow solution");
            Vec3 reached = (Vec3) simulate.invoke(bow, -pitch, 3.0, 40.0);
            require(Math.abs(reached.y - dy) < 1e-4, "bow compensation reaches exact horizontal distance");
        }
        require(!Float.isFinite((float) solve.invoke(bow, 40.0, 0.0, 0.0)), "undrawn bow rejected");
        require(!Float.isFinite((float) solve.invoke(bow, 10_000.0, 0.0, 3.0)), "unreachable bow rejected");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
