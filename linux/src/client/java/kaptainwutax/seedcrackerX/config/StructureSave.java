package kaptainwutax.seedcrackerX.config;

import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Structure;
import kaptainwutax.seedcrackerX.Features;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.cracker.storage.ScheduledSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class StructureSave {
    private static final Logger logger = LoggerFactory.getLogger("structureSave");

    public static final Path saveDir = Paths.get(FabricLoader.getInstance().getConfigDir().toFile().toString(), "SeedCrackerX saved structures");

    public static void saveStructures(ScheduledSet<DataStorage.Entry<Feature.Data<?>>> baseData) {
        Path staged = null;
        try {
            Files.createDirectories(saveDir);
            Path saveFile = saveDir.resolve(getWorldName());
            staged = Files.createTempFile(saveDir, "structures-", ".tmp");
            try (var writer = Files.newBufferedWriter(staged, java.nio.charset.StandardCharsets.UTF_8)) {
                for (DataStorage.Entry<Feature.Data<?>> dataEntry : baseData) {
                    if (dataEntry.data.feature instanceof Structure structure) {
                        writer.write(Structure.getName(structure.getClass()) + ";" + dataEntry.data.chunkX
                                + ";" + dataEntry.data.chunkZ + "\n");
                    }
                }
            }
            try (var channel = java.nio.channels.FileChannel.open(staged, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(staged, saveFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, saveFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("seedcracker couldn't save structures", e);
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }

    public static List<RegionStructure.Data<?>> loadStructures() {
        List<RegionStructure.Data<?>> result = new ArrayList<>();
        try {
            Files.createDirectories(saveDir);
            Path saveFile = saveDir.resolve(getWorldName());
            if (!Files.exists(saveFile)) return result;
            if (Files.size(saveFile) > 16L * 1024 * 1024) throw new IOException("Structures file exceeds 16 MiB");
            try (var reader = Files.newBufferedReader(saveFile, java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String[] info = line.split(";", -1);
                    if (info.length != 3 || line.length() > 512) continue;
                    try {
                        int x = Integer.parseInt(info[1].trim());
                        int z = Integer.parseInt(info[2].trim());
                        if (Math.abs((long) x) > 1_875_000L || Math.abs((long) z) > 1_875_000L)
                            throw new NumberFormatException("Chunk coordinate outside world bounds");
                        for (RegionStructure<?, ?> structure : Features.STRUCTURE_TYPES) {
                            if (info[0].equals(structure.getName())) {
                                result.add(structure.at(x, z));
                                break;
                            }
                        }
                    } catch (IllegalArgumentException malformed) {
                        logger.warn("Skipping malformed structures record at line {}", lineNumber);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("seedcracker couldn't load previous structures", e);
        }
        return result;
    }

    private static String getWorldName() {
        Minecraft minecraftClient = Minecraft.getInstance();
        if (minecraftClient.getConnection() != null) {
            Connection connection = minecraftClient.getConnection().getConnection();
            if (connection.isMemoryConnection()) {
                String address = minecraftClient.getSingleplayerServer().getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
                return address.replace("/","_").replace(":", "_")+".txt";
            } else {
                return connection.getRemoteAddress().toString().replace("/","_").replace(":","_")+".txt";
            }
        }
        return "Invalid.txt";
    }
}
