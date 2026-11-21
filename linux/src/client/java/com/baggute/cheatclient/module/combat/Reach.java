package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Extends the local crosshair entity ray without rotating or auto-attacking. */
public final class Reach extends Module {
    private final DoubleSetting range = new DoubleSetting("range", "Crosshair attack range", 3.5, 3, 6);
    private final BoolSetting onlyWhileClicking = new BoolSetting("onlyWhileClicking", "Only while left-click is held", true);
    public Reach() {
        super("Reach", "Extends crosshair entity targeting to the selected range.", Category.COMBAT);
        addSetting(range); addSetting(onlyWhileClicking);
    }
    public static double effectiveRange(double vanilla, Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null || mc == null
                || player != mc.player || mc.options == null || mc.gui.screen() != null || mc.isPaused()) return vanilla;
        Module module = UtilityClient.moduleManager.getByName("Reach");
        if (!(module instanceof Reach reach) || !reach.isEnabled()
                || (reach.onlyWhileClicking.get() && !mc.options.keyAttack.isDown())) return vanilla;
        return Math.max(vanilla, reach.range.get());
    }
}
