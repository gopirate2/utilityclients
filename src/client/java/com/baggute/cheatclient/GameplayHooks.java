package com.baggute.cheatclient;

import com.baggute.cheatclient.module.combat.AntiMiss;
import com.baggute.cheatclient.module.combat.HitSelect;
import com.baggute.cheatclient.module.render.Freecam;
import com.baggute.cheatclient.module.render.Fullbright;
import com.baggute.cheatclient.module.player.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Shape-preserving gameplay callbacks shared with the runtime injector. */
public final class GameplayHooks {
    private GameplayHooks() {}
    private static Field movementField;
    private static Method cameraPosition, cameraRotation;
    private static boolean inputResolved, cameraResolved;
    private static boolean enabled(String name) {
        return !UtilityClient.isUnloaded() && UtilityClient.moduleManager != null
                && UtilityClient.moduleManager.getByName(name) != null
                && UtilityClient.moduleManager.getByName(name).isEnabled();
    }
    public static Object lightmapOption(Object option) {
        OptionInstance<?> instance = (OptionInstance<?>) option;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null && instance == mc.options.gamma() && enabled("Fullbright"))
            return ((Fullbright) UtilityClient.moduleManager.getByName("Fullbright")).effectiveLevel();
        if(mc!=null&&mc.options!=null&&instance==mc.options.sensitivity()&&!UtilityClient.isUnloaded()
                &&UtilityClient.moduleManager!=null&&UtilityClient.moduleManager.getByName("Zoom") instanceof Zoom zoom)
            return zoom.sensitivity((Double)instance.get());
        return instance.get();
    }
    public static void freezeInput(Object input) {
        if (!enabled("Freecam") || !(input instanceof net.minecraft.client.player.ClientInput controls)) return;
        controls.keyPresses = Input.EMPTY;
        try {
            if (!inputResolved) {
                movementField = net.minecraft.client.player.ClientInput.class.getDeclaredField("moveVector");
                movementField.setAccessible(true); inputResolved = true;
            }
            movementField.set(controls, Vec2.ZERO);
        } catch (ReflectiveOperationException failure) { report("freecam input", failure); }
    }
    public static boolean turn(Object entity, double yaw, double pitch) {
        if (!enabled("Freecam") || entity != Minecraft.getInstance().player) return false;
        Freecam.camYaw += (float) yaw * 0.15f;
        Freecam.camPitch = net.minecraft.util.Mth.clamp(Freecam.camPitch + (float) pitch * 0.15f, -90f, 90f);
        return true;
    }
    public static void camera(Object camera) {
        if (!enabled("Freecam")) return;
        try {
            if (!cameraResolved) {
                cameraPosition = net.minecraft.client.Camera.class.getDeclaredMethod("setPosition", double.class, double.class, double.class);
                cameraRotation = net.minecraft.client.Camera.class.getDeclaredMethod("setRotation", float.class, float.class);
                cameraPosition.setAccessible(true); cameraRotation.setAccessible(true); cameraResolved = true;
            }
            cameraPosition.invoke(camera, Freecam.camPos.x, Freecam.camPos.y, Freecam.camPos.z);
            cameraRotation.invoke(camera, Freecam.camYaw, Freecam.camPitch);
        } catch (ReflectiveOperationException failure) { report("freecam camera", failure); }
    }
    public static float stepHeight(float vanilla, Object entity) {
        if (entity != Minecraft.getInstance().player || !enabled("StepUp")) return vanilla;
        return (float) com.baggute.cheatclient.module.movement.StepUp.stepHeight;
    }
    public static float cameraFov(float vanilla){
        if(UtilityClient.isUnloaded()||UtilityClient.moduleManager==null)return vanilla;
        var module=UtilityClient.moduleManager.getByName("Zoom");
        return module instanceof Zoom zoom?zoom.applyFov(vanilla):vanilla;
    }
    public static boolean hideHand() {
        return enabled("Zoom") && ((Zoom) UtilityClient.moduleManager.getByName("Zoom")).shouldHideHand();
    }
    public static boolean scroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        return enabled("Zoom") && mc.gui.screen() == null
                && ((Zoom) UtilityClient.moduleManager.getByName("Zoom")).onScroll(amount);
    }
    public static boolean blockAttack(Object player, Object target) {
        return player instanceof Player p && target instanceof Entity e
                && (AntiMiss.shouldBlock(p, e) || HitSelect.shouldBlock(Minecraft.getInstance()));
    }
    private static long lastReport;
    public static boolean blockClick(Object game) {
        if (UtilityClient.isUnloaded() || !(game instanceof Minecraft mc) || UtilityClient.moduleManager == null) return false;
        if (AntiMiss.shouldBlockClick(mc)) return true;
        var manager = UtilityClient.moduleManager;
        return (manager.getByName("AutoMace") instanceof com.baggute.cheatclient.module.combat.AutoMace mace && mace.ownsClick(mc))
                || (manager.getByName("AutoBreachSwap") instanceof com.baggute.cheatclient.module.combat.AutoBreachSwap breach && breach.ownsClick(mc))
                || (manager.getByName("AutoStunSlam") instanceof com.baggute.cheatclient.module.combat.AutoStunSlam stun && stun.ownsClick(mc));
    }
    public static double entityRange(double vanilla, Object player) {
        return player instanceof Player p ? com.baggute.cheatclient.module.combat.Reach.effectiveRange(vanilla, p) : vanilla;
    }
    public static void beforeTick(Object game) {
        if (!(game instanceof Minecraft) || UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return;
        var module = UtilityClient.moduleManager.getByName("AutoJumpReset");
        if (module instanceof com.baggute.cheatclient.module.combat.AutoJumpReset reset && reset.isEnabled()) reset.beforeTick();
    }
    public static void afterAttack(Object player, Object target) {
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return;
        var module = UtilityClient.moduleManager.getByName("SmartWTap");
        if (module instanceof com.baggute.cheatclient.module.combat.SmartWTap tap && tap.isEnabled()
                && player instanceof Player p && target instanceof Entity e) tap.afterAttack(p, e);
    }
    public static Object beginBowRelease(Object player) {
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null || !(player instanceof Player p)) return null;
        var module = UtilityClient.moduleManager.getByName("BowAimbot");
        return module instanceof com.baggute.cheatclient.module.combat.BowAimbot bow ? bow.beginRelease(p) : null;
    }
    public static void endBowRelease(Object player, Object token) {
        com.baggute.cheatclient.module.combat.BowAimbot.endRelease(player, token);
    }
    private static void report(String hook, Throwable failure) {
        long now = System.currentTimeMillis();
        if (now - lastReport < 5_000) return;
        lastReport = now;
        UtilityClient.LOGGER.error("[GameplayHooks] {} failed", hook, failure);
    }
}
