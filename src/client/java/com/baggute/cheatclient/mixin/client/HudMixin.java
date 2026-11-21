// language: Java, file: HudMixin.java, target: Fabric 26.2
package com.baggute.cheatclient.mixin.client;

import com.baggute.cheatclient.stealth.ScreenBypass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the client HUD at the tail of vanilla's own HUD pass.
 *
 * <p>{@code Hud.extractRenderState} is the only HUD hook in 26.2: the GUI is not
 * painted in an immediate-mode pass any more, it is recorded into a
 * {@code GuiRenderState} and submitted later, so there is no {@code render}
 * method to intercept and no {@code HudRenderCallback} in Fabric API 26.2. TAIL
 * means vanilla has finished recording its own elements and every client element
 * lands on top of them, inside the same stratum, at the same GUI scale.
 *
 * <p>There is no gate on the call. Every version of this hook that asked a
 * question first - is a recorder running, is exclusion applied, is the module on
 * - produced a build where the answer was wrong and the HUD was simply absent.
 * The decision moved into {@link ScreenBypass#renderHud}, which now draws
 * unconditionally as well. The only failure that can still hide the HUD is an
 * exception, and that is caught here so it costs the client overlay rather than
 * the rest of the vanilla pass.
 *
 * <p>Registered in {@code utilityclient.mixins.json} as {@code client.HudMixin}.
 */
@Mixin(Hud.class)
public class HudMixin {

    /** One report per session, not one per frame. */
    private static boolean utilityclient$reported;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void utilityclient$onHudRender(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            ScreenBypass.renderHud(g);
        } catch (Throwable failure) {
            // Printed first: a stack trace in the log is the only way to tell a
            // render fault from a registration fault.
            failure.printStackTrace();
            if (utilityclient$reported) return;
            utilityclient$reported = true;
            try {
                com.baggute.cheatclient.stealth.LogManager.log(
                        "ERROR", "HudMixin: client HUD render failed", failure);
            } catch (Throwable ignored) {
                // Logging must not be the thing that breaks the pass.
            }
            try {
                com.baggute.cheatclient.UtilityClient.LOGGER
                        .error("[HudMixin] client HUD render failed", failure);
            } catch (Throwable ignored) {
                // Same.
            }
        }
    }
}
