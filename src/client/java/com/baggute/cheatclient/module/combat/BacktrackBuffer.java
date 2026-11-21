package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.apex.ApexSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import java.util.ArrayDeque;

/** Bounded, ordered delay for the current combat target, never permanent packet loss. */
public final class BacktrackBuffer {
    private BacktrackBuffer() {}
    private record Delayed(ClientboundMoveEntityPacket packet, long expires) {}
    private static final ArrayDeque<Delayed> QUEUE = new ArrayDeque<>();
    private static ClientPacketListener connection;
    private static ClientLevel world;
    private static Entity target;
    private static boolean replaying;

    public static boolean intercept(Object packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || replaying || !(packet instanceof ClientboundMoveEntityPacket move)) return false;
        tick();
        if (target == null || move.getEntity(mc.level) != target) return false;
        var module = UtilityClient.moduleManager.getByName("Backtrack");
        int delay = ApexSettings.integer(module, "delayTicks", 3);
        if (QUEUE.size() >= 256) flush();
        QUEUE.addLast(new Delayed(move, System.nanoTime() + delay * 50_000_000L));
        return true;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world || mc.getConnection() != connection) {
            QUEUE.clear(); target = null; world = mc.level; connection = mc.getConnection();
        }
        var module = UtilityClient.moduleManager == null ? null : UtilityClient.moduleManager.getByName("Backtrack");
        Entity next = null;
        if (!UtilityClient.isUnloaded() && module != null && module.isEnabled()
                && mc.player != null && mc.level != null && mc.gui.screen() == null) {
            next = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : mc.player.getLastHurtMob();
            if (next == mc.player || (next != null && (!next.isAlive() || !mc.player.hasLineOfSight(next)
                    || !com.baggute.cheatclient.util.Stealth.inEntityReach(mc.player, next)))) next = null;
        }
        if (next != target) { flush(); target = next; }
        long now = System.nanoTime();
        while (!QUEUE.isEmpty() && QUEUE.peekFirst().expires <= now) replay(QUEUE.removeFirst().packet);
    }

    /** Flush before absolute position/removal packets, and immediately on disable. */
    public static void flush() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        if (connection != mc.getConnection() || world != mc.level) { QUEUE.clear(); return; }
        while (!QUEUE.isEmpty()) replay(QUEUE.removeFirst().packet);
    }
    private static void replay(ClientboundMoveEntityPacket packet) {
        if (connection == null) return;
        replaying = true;
        try { connection.handleMoveEntity(packet); }
        finally { replaying = false; }
    }
}
