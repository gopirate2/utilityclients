// language: Java, file: WatermarkRenderer.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.misc.Watermark;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the {@link Watermark} module: name, fps and the ping the network handler
 * reports for this player.
 *
 * <p>Ping comes from {@code PlayerInfo.getLatency()}, the same value the tab list
 * shows, so it reflects the server's own measurement instead of a client-side
 * round-trip guess. The fallback is the average across the player list, which is
 * what you want on a server that has not sent this player's entry yet.
 */
public final class WatermarkRenderer {

    private WatermarkRenderer() {}

    /**
     * The watermark's text, or null when the module is off.
     *
     * <p>Shared with the capture-excluded overlay: the game window draws nothing of
     * the client while a capture runs, so a watermark built only here would vanish
     * from the screen the moment the overlay took over.
     */
    public static String line(Minecraft mc) {
        if (UtilityClient.moduleManager == null || mc.player == null) return null;
        Watermark watermark = UtilityClient.moduleManager.getByName("Watermark") instanceof Watermark w ? w : null;
        if (watermark == null || !watermark.isEnabled()) return null;

        StringBuilder line = new StringBuilder(watermark.label());
        if (watermark.showFps()) line.append("  ").append(mc.getFps()).append(" fps");
        if (watermark.showPing()) line.append("  ").append(ping(mc)).append("ms");
        return line.toString();
    }

    public static void draw(GuiGraphicsExtractor g, Minecraft mc) {
        if (UtilityClient.moduleManager == null) return;
        Watermark watermark = UtilityClient.moduleManager.getByName("Watermark") instanceof Watermark w ? w : null;
        if (watermark == null || !watermark.isEnabled()) return;
        if (mc.player == null) return;

        String text = line(mc);
        if (text == null) return;

        g.pose().pushMatrix();
        g.pose().translate((float) watermark.x(), (float) watermark.y());
        float scale = (float) watermark.scale();
        g.pose().scale(scale, scale);

        int width = mc.font.width(text);
        if (watermark.background()) {
            g.fill(-2, -2, width + 4, 12, 0x80000000);
        }
        g.text(mc.font, text, 0, 0, watermark.color(), true);

        g.pose().popMatrix();
    }

    /** This player's latency, or the player-list average, or -1. */
    public static int ping(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return -1;

        var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info != null) return info.getLatency();

        int total = 0;
        int count = 0;
        for (var entry : mc.getConnection().getOnlinePlayers()) {
            total += entry.getLatency();
            count++;
        }
        return count == 0 ? -1 : total / count;
    }
}
