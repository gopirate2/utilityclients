// language: Java, file: LateBridge.java, target: Fabric 26.2
package com.baggute.cheatclient;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.render.Freecam;
import com.baggute.cheatclient.render.ApexRender;
import com.baggute.cheatclient.render.BlockEspExtractor;
import com.baggute.cheatclient.render.EspExtractor;
import com.baggute.cheatclient.render.HudRenderer;
import com.baggute.cheatclient.stealth.PanicHandler;
import com.baggute.cheatclient.stealth.ScreenBypass;
import com.baggute.cheatclient.render.IndicatorExtractor;
import com.baggute.cheatclient.render.StorageEspExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.lwjgl.glfw.GLFW;

/**
 * The mixin entry points, callable from bytecode the injector inserts at runtime.
 *
 * <p>When the client is loaded into a game that is already running, its mixins
 * cannot apply: Fabric already read {@code Hud}, {@code LevelRenderer},
 * {@code KeyboardHandler} and {@code Minecraft}. The injector rewrites those four
 * classes at five methods and calls the matching method here, which is why this bridge
 * exists at all and why its method names and descriptors are part of a contract
 * with {@code BridgePatcher}.
 *
 * <p>Every method swallows its own failures. This runs inside the game's render
 * loop: a throw here would be a black screen, not a log line.
 */
public final class LateBridge {

    private LateBridge() {
    }

    /** HUD: {@code Hud#extractRenderState}, tail. */
    public static void onHudRender(Object graphics) {
        if (!(graphics instanceof GuiGraphicsExtractor extractor)) return;
        if (UtilityClient.moduleManager == null) return;
        try {
            ScreenBypass.renderHud(extractor);
        } catch (Throwable failure) {
            debug("hud", failure);
        }
    }

    /** World: {@code LevelRenderer#submitFeatures}, head. */
    public static void onSubmitFeatures(Object state) {
        if (!(state instanceof LevelRenderState renderState)) return;
        if (UtilityClient.moduleManager == null) return;
        if (com.baggute.cheatclient.module.misc.StreamProof.hidingFromCapture()) return;
        if (ScreenBypass.worldHidden()) return;
        try {
            EspExtractor.emitGizmos(renderState);
        } catch (Throwable failure) {
            debug("esp", failure);
        }
        try {
            BlockEspExtractor.emitGizmos();
        } catch (Throwable failure) {
            debug("blockesp", failure);
        }
        try {
            StorageEspExtractor.emitGizmos();
        } catch (Throwable failure) {
            debug("storageesp", failure);
        }
        try {
            IndicatorExtractor.emit(renderState);
        } catch (Throwable failure) {
            debug("indicators", failure);
        }
        try {
            com.baggute.cheatclient.render.NametagRenderer.emit(renderState);
            com.baggute.cheatclient.render.VanishExtractor.emitGizmos(renderState);
        } catch (Throwable failure) { debug("nametags/vanish", failure); }
        try {
            ApexRender.emit(renderState);
        } catch (Throwable failure) {
            debug("apex", failure);
        }
    }

    /** Frame: {@code Minecraft#renderFrame}, head. */
    public static void onRenderFrame() {
        if (UtilityClient.isUnloaded()) return;
        if (UtilityClient.moduleManager == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            UtilityClient.moduleManager.onRenderFrame(partialTick);
        } catch (Throwable failure) {
            debug("frame", failure);
        }
    }

    /** Tick: Minecraft.tick, tail; independent of Fabric's startup mixins. */
    public static void onClientTick() {
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return;
        try { UtilityClient.onClientTick(Minecraft.getInstance()); }
        catch (Throwable failure) { debug("tick", failure); }
    }

    public static Object captureItemUse(Object player, Object hand) {
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return null;
        try {
            Module module = UtilityClient.moduleManager.getByName("AutoPearlCatch");
            if (module instanceof com.baggute.cheatclient.module.combat.AutoPearlCatch catcher)
                return catcher.captureLocalThrow(Minecraft.getInstance(), player, hand);
        } catch (Throwable failure) { debug("item use capture", failure); }
        return null;
    }

    /** The original return object survives both disabled modules and callback failures. */
    public static Object completeItemUse(Object result, Object snapshot) {
        if (snapshot == null || UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return result;
        try {
            Module module = UtilityClient.moduleManager.getByName("AutoPearlCatch");
            if (module instanceof com.baggute.cheatclient.module.combat.AutoPearlCatch catcher
                    && snapshot instanceof com.baggute.cheatclient.module.combat.AutoPearlCatch.LocalThrow shot
                    && result instanceof net.minecraft.world.InteractionResult used)
                catcher.completeLocalThrow(Minecraft.getInstance(), shot, used);
        } catch (Throwable failure) { debug("item use result", failure); }
        return result;
    }

    /**
     * Keys: {@code KeyboardHandler#keyPress}, head.
     *
     * <p>Both paths consume global panic and Freecam input when handled; ordinary
     * action binds and toggles also reach vanilla input.
     *
     * @param event the {@link KeyEvent}; typed as Object because the patch site
     *              passes its own reference without a cast
     * @param action GLFW press/release/repeat
     */
    public static boolean onKeyPress(Object event, int action) {
        if (UtilityClient.isUnloaded()) return false;
        if (!(event instanceof KeyEvent keyEvent)) return false;
        if (UtilityClient.moduleManager == null) return false;

        try {
            int key = keyEvent.key();
            if (PanicHandler.handle(key, action)) return true;
            if (UtilityClient.moduleManager.onGlobalKey(key, action)) return true;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || (action != GLFW.GLFW_RELEASE && (mc.gui.screen() != null || mc.isPaused()
                    || !com.baggute.cheatclient.util.PhysicalInput.focused(mc)))) return false;

            Module freecam = UtilityClient.moduleManager.getByName("Freecam");
            if (freecam instanceof Freecam fc && fc.isEnabled() && fc.handlesKey(keyEvent)) return true;

            UtilityClient.moduleManager.onKey(key, action);
        } catch (Throwable failure) {
            debug("keys", failure);
        }
        return false;
    }

    private static void debug(String where, Throwable failure) {
        com.baggute.cheatclient.util.ClientErrors.report("LateBridge/" + where, failure);
    }

    public static Object scaleMotion(Object entity, Object motion) {
        if (!(entity instanceof net.minecraft.world.entity.Entity self)
                || !(motion instanceof net.minecraft.world.phys.Vec3 vector)) return motion;
        return com.baggute.cheatclient.module.combat.Velocity.apply(self, vector);
    }

    public static void applyKnockback(Object entity, double x, double y, double z) {
        if (!(entity instanceof net.minecraft.world.entity.Entity self)) return;
        var base = self.getDeltaMovement();
        var impulse = new net.minecraft.world.phys.Vec3(x, y, z).subtract(base);
        self.setDeltaMovement(base.add(com.baggute.cheatclient.module.combat.Velocity.apply(self, impulse)));
    }

    public static java.util.Optional<net.minecraft.world.phys.Vec3> explosionImpulse(Object packet) {
        var explosion = (net.minecraft.network.protocol.game.ClientboundExplodePacket) packet;
        return explosion.playerKnockback().map(v -> com.baggute.cheatclient.module.combat.Velocity.apply(Minecraft.getInstance().player, v));
    }

    public static void onSeedChunk(Object packet) {
        if (!UtilityClient.LATE_INIT || UtilityClient.isUnloaded()) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk)
            kaptainwutax.seedcrackerX.SeedRuntime.chunk(chunk);
    }
    public static void onSeedLogin(Object packet) {
        if (!UtilityClient.LATE_INIT || UtilityClient.isUnloaded()) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket login)
            kaptainwutax.seedcrackerX.SeedRuntime.login(login.commonPlayerSpawnInfo().seed());
    }
    public static void onSeedRespawn(Object packet) {
        if (!UtilityClient.LATE_INIT || UtilityClient.isUnloaded()) return;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket respawn)
            kaptainwutax.seedcrackerX.SeedRuntime.dimension(respawn.commonPlayerSpawnInfo().seed(), true);
    }
    public static void onSeedTick() {
        if (UtilityClient.LATE_INIT && !UtilityClient.isUnloaded()) kaptainwutax.seedcrackerX.SeedRuntime.tick();
    }
    public static void onSeedDisconnect() {
        if (UtilityClient.LATE_INIT && !UtilityClient.isUnloaded()) kaptainwutax.seedcrackerX.SeedRuntime.disconnect();
    }
    public static boolean delayMovement(Object packet) {
        return com.baggute.cheatclient.module.combat.BacktrackBuffer.intercept(packet);
    }
    public static void flushMovement() { com.baggute.cheatclient.module.combat.BacktrackBuffer.flush(); }
}
