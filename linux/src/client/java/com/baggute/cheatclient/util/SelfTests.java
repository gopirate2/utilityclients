// language: Java, file: SelfTests.java, target: Fabric 26.2
package com.baggute.cheatclient.util;

import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.module.render.Trajectory;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-logic checks over code that has no Minecraft runtime dependency. JUnit is
 * not in the offline dependency cache, so these run from the Debug module on
 * enable and log a failure count instead of going through Gradle's test task.
 *
 * Every check here corresponds to a real bug class hit during development: a
 * helper collapsing several states into one value, and an end condition that
 * silently never triggers.
 */
public final class SelfTests {

    private SelfTests() {}

    public static List<String> run() {
        List<String> failures = new ArrayList<>();

        // A flat shot must still travel forward, and it must come back down.
        double[] flat = Trajectory.predict(0f, 3.0, 100);
        if (flat[0] <= 0) failures.add("trajectory: flat shot travelled nowhere");
        if (flat[1] >= 0) failures.add("trajectory: flat shot never dropped");

        // Power scales range monotonically.
        double[] weak = Trajectory.predict(0f, 1.0, 100);
        double[] strong = Trajectory.predict(0f, 3.0, 100);
        if (strong[0] <= weak[0]) failures.add("trajectory: faster shot travelled less");

        // A 45-degree launch must outrange a flat one.
        double[] angled = Trajectory.predict(-45f, 3.0, 100);
        if (angled[0] <= flat[0]) failures.add("trajectory: angled shot did not gain range");

        // The simulation has to terminate within its tick budget.
        double[] bounded = Trajectory.predict(-89f, 3.0, 20);
        if (bounded[0] < 0) failures.add("trajectory: negative range");

        // Layout parsing is order independent and survives junk input.
        if (!layoutRoundTrips()) failures.add("hud: layout round trip lost a widget");
        if (!layoutRejectsJunk()) failures.add("hud: layout parser threw on junk");

        return failures;
    }

    private static boolean layoutRoundTrips() {
        // POTIONS_COUNTER and TARGET_HUD are the two keys that used to be dropped:
        // the writer emitted enum names ("POTIONS_COUNTER") while the reader only
        // matched the lowercase key ("potioncounter"), so the editor lost them.
        String layout = "MODULES:5:5;COORDS:5:70;POTIONS_COUNTER:9:9;TARGET_HUD:150:70";
        java.util.Map<Hud.Widget, int[]> parsed = Hud.parseLayout(layout);

        int[] counter = parsed.get(Hud.Widget.POTIONS_COUNTER);
        if (counter[0] != 9 || counter[1] != 9) return false;
        int[] target = parsed.get(Hud.Widget.TARGET_HUD);
        if (target[0] != 150 || target[1] != 70) return false;

        // Serialising and parsing again must preserve every widget's position.
        java.util.Map<Hud.Widget, int[]> again = Hud.parseLayout(Hud.serializeLayout(parsed));
        for (Hud.Widget widget : Hud.Widget.values()) {
            int[] before = parsed.get(widget);
            int[] after = again.get(widget);
            if (before[0] != after[0] || before[1] != after[1]) return false;
        }
        return true;
    }

    private static boolean layoutRejectsJunk() {
        try {
            // The real parser has to skip junk entries instead of throwing or
            // applying them as 0:0.
            java.util.Map<Hud.Widget, int[]> parsed =
                    Hud.parseLayout(";;;;:;garbage;;MODULES:notanumber:x");
            int[] modules = parsed.get(Hud.Widget.MODULES);
            return modules[0] == 5 && modules[1] == 5;
        } catch (Exception exception) {
            return false;
        }
    }
}
