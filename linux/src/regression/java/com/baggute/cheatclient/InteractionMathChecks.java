package com.baggute.cheatclient;

import com.baggute.cheatclient.config.KitManager;
import com.baggute.cheatclient.module.combat.AimPipeline;
import com.baggute.cheatclient.util.InventoryUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Runs the real geometry, slot mapping and quantization helpers without a game. */
public final class InteractionMathChecks {
    public static void main(String[] args) throws Exception {
        for (int slot = 0; slot < 36; slot++)
            require(InventoryUtils.hotbarMenuSlot(slot) == (slot < 9 ? slot + 36 : slot), "main/hotbar mapping " + slot);
        require(InventoryUtils.hotbarMenuSlot(-1) == -1 && InventoryUtils.hotbarMenuSlot(36) == -1, "invalid slot rejected");
        for (int slot = 36; slot < 40; slot++)
            require(KitManager.menuSlotFor(slot) == 44 - slot, "armor mapping " + slot);
        require(KitManager.menuSlotFor(40) == 45, "offhand mapping");
        AABB box = new AABB(0, 0, 0, 1, 2, 1);
        require(Stealth.eyeToHitbox(new Vec3(0.5, 1, 0.5), box) == 0, "inside hitbox distance");
        require(Math.abs(Stealth.eyeToHitbox(new Vec3(4, 1, 0.5), box) - 3) < 1e-9, "distance to face");
        require(Math.abs(Stealth.eyeToHitbox(new Vec3(4, 6, 1), box) - 5) < 1e-9, "distance to corner");
        for (float step : new float[]{0.0096f, 0.15f, 0.6144f}) {
            for (int n = -1000; n <= 1000; n++) {
                float angle = n * 0.0137f;
                float quantized = AimPipeline.quantize(angle, step);
                require(Math.abs(quantized - angle) <= step * 0.501f, "quantization residual");
                float jittered = AimPipeline.quantizeJittered(angle, step, 1, n & 1);
                require(Math.abs(jittered / step - Math.round(jittered / step)) < 0.001f, "pixel-space jitter grid");
            }
        }
        System.out.println("PASS: inventory/armor indices, eye-to-hitbox reach geometry, mouse pixel quantization");
        ModuleSettingsChecks.run();
        KitImportChecks.run();
        RepairChecks.run();
        ActivationChecks.run();
        PearlInterceptChecks.run();
        ProfileComparisonChecks.run();
        ContainerDragChecks.run();
        ExtensionsThemeChecks.run();
        ThemeShareChecks.run();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
