package kaptainwutax.seedcrackerX;

import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.config.StructureSave;
import kaptainwutax.seedcrackerX.cracker.DataAddedEvent;
import kaptainwutax.seedcrackerX.cracker.HashedSeedData;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.finder.ReloadFinders;
import kaptainwutax.seedcrackerX.util.Database;
import kaptainwutax.seedcrackerX.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/** Shared observation lifecycle for Fabric mixins and runtime injection. */
public final class SeedRuntime {
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static int scanX, scanZ, scanRadius;
    private static ChunkPos scanCenter;
    private static boolean completionsPending;
    private static com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> commands;
    private SeedRuntime() {}

    public static void chunk(ClientboundLevelChunkWithLightPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (SeedCracker.get() != null && mc.level != null)
            FinderQueue.get().onChunkData(mc.level, new ChunkPos(packet.getX(), packet.getZ()));
    }

    public static void dimension(long hash, boolean changed) {
        Minecraft mc = Minecraft.getInstance();
        if (SeedCracker.get() == null || mc.level == null) return;
        if (world != null && world != mc.level) FinderQueue.get().clear();
        boolean entering = world != mc.level;
        world = mc.level;
        if (entering && mc.player != null && com.baggute.cheatclient.UtilityClient.LATE_INIT) {
            scanCenter = mc.player.chunkPosition();
            scanRadius = Math.min(32, mc.options.renderDistance().get());
            scanX = scanZ = -scanRadius;
        }
        var dimension = world.dimensionType();
        ReloadFinders.reloadHeight(dimension.minY(), dimension.minY() + dimension.logicalHeight());
        var storage = SeedCracker.get().getDataStorage();
        if (storage.addHashedSeedData(new HashedSeedData(hash), DataAddedEvent.POKE_BIOMES)
                && Config.get().active && changed) Log.error(Log.translate("fetchedHashedSeed"));
        var connection = mc.getConnection();
        if (connection != null && connection.getConnection().getRemoteAddress() != null) {
            Long seed = Database.getSeed(connection.getConnection().getRemoteAddress().toString(), hash);
            if (seed != null) Log.printSeed("tmachine.foundWorldSeedFromDatabase", seed);
        }
    }

    public static void login(long hash) {
        dimension(hash, false);
        var preloaded = StructureSave.loadStructures();
        if (!preloaded.isEmpty()) Log.warn("foundRestorableStructures", preloaded.size());
    }

    /** An injected client may join after the login packet was already handled. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (SeedCracker.get() == null || mc.level == null) return;
        // The dispatcher can arrive after login/hash recovery, and is replaced
        // when the server sends a new command tree. Retry until each one exists.
        if (com.baggute.cheatclient.UtilityClient.LATE_INIT) {
            var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher();
            if (dispatcher != null && dispatcher != commands) {
                if (dispatcher.getRoot().getChild(kaptainwutax.seedcrackerX.init.ClientCommands.PREFIX) == null) {
                    kaptainwutax.seedcrackerX.init.ClientCommands.registerCommands(dispatcher);
                    completionsPending = true;
                }
                commands = dispatcher;
            }
            if (completionsPending) {
                try {
                    net.fabricmc.fabric.api.client.command.v2.ClientCommands.refreshCommandCompletions();
                    commands = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher();
                    completionsPending = false;
                } catch (IllegalStateException notReady) {
                    // Login can precede the server's command tree; retry next tick.
                }
            }
        }
        if (world != mc.level || SeedCracker.get().getDataStorage().hashedSeedData == null) {
            try {
                var manager = mc.level.getBiomeManager();
                var field = manager.getClass().getDeclaredField("biomeZoomSeed");
                field.setAccessible(true);
                dimension(field.getLong(manager), false);
            } catch (ReflectiveOperationException failure) {
                world = mc.level;
                var dimension = world.dimensionType();
                ReloadFinders.reloadHeight(dimension.minY(), dimension.minY() + dimension.logicalHeight());
                SeedCracker.LOGGER.warn("Could not recover the current world's seed hash; reconnect to collect it", failure);
            }
        }
        // Recover observations from chunks loaded before injection. Walk a bounded
        // number each tick and never request/generate chunks which are absent.
        if (scanCenter != null && Config.get().active) {
            for (int n = 0; n < 2 && scanCenter != null; n++) {
                int x = scanCenter.x() + scanX, z = scanCenter.z() + scanZ;
                if (world.hasChunk(x, z)) FinderQueue.get().onChunkData(world, new ChunkPos(x, z));
                if (++scanX > scanRadius) {
                    scanX = -scanRadius;
                    if (++scanZ > scanRadius) scanCenter = null;
                }
            }
        }
        SeedCracker.get().getDataStorage().tick();
    }

    public static void disconnect() {
        if (SeedCracker.get() == null) return;
        StructureSave.saveStructures(SeedCracker.get().getDataStorage().baseSeedData);
        SeedCracker.get().reset();
        world = null;
        scanCenter = null;
        commands = null;
        completionsPending = false;
    }
}
