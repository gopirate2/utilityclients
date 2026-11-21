package kaptainwutax.seedcrackerX;

import com.mojang.logging.LogUtils;
import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.init.ClientCommands;
import kaptainwutax.seedcrackerX.util.Database;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.util.ArrayList;

public class SeedCracker implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ArrayList<SeedCrackerAPI> entrypoints = new ArrayList<>();
    private static SeedCracker INSTANCE;
    private volatile DataStorage dataStorage = new DataStorage();

    public static SeedCracker get() {
        return INSTANCE;
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        Config.load();
        // Use the configured version; each feature reports unsupported tables.
        try {
            Features.init(Config.get().getVersion());
        } catch (Throwable unknownVersion) {
            LOGGER.error("No seed data for {}; falling back to 1.21", Config.get().getVersion(), unknownVersion);
            try {
                Features.init(com.seedfinding.mccore.version.MCVersion.v1_21);
            } catch (Throwable fallbackFailed) {
                LOGGER.error("Seed feature tables are unusable; finders are off", fallbackFailed);
            }
        }
        LOGGER.info("Initialized {} structure feature tables for {}",
                Features.STRUCTURE_TYPES.size(), Config.get().getVersion());
        FabricLoader.getInstance().getEntrypointContainers("seedcrackerx", SeedCrackerAPI.class).forEach(entrypoint ->
                entrypoints.add(entrypoint.getEntrypoint()));

        FinderQueue.registerEvents();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ClientCommands.registerCommands(dispatcher));

        Database.fetchSeeds();
    }

    public DataStorage getDataStorage() {
        return this.dataStorage;
    }

    public void reset() {
        SeedCracker.get().getDataStorage().clear();
        FinderQueue.get().clear();
        dataStorage = new DataStorage();
    }
}
