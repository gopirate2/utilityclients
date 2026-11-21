package com.baggute.cheatclient;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/** Standalone checks use the same vanilla registry/tag/component loading order as the game. */
public final class MinecraftBootstrap {
    private static boolean initialized;
    private static net.minecraft.core.RegistryAccess.Frozen access;
    private MinecraftBootstrap() {}

    public static synchronized void initialize() {
        if (initialized) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String extracted = System.getProperty("uc.regression.vanillaDataRoot");
        PackResources vanilla = extracted == null ? ServerPacksSource.createVanillaPackSource()
                : new PathPackResources(new PackLocationInfo("vanilla", net.minecraft.network.chat.Component.literal("Vanilla"),
                        PackSource.BUILT_IN, java.util.Optional.empty()), java.nio.file.Path.of(extracted));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla))) {
            var base = RegistryLayer.createRegistryAccess();
            var tags = TagLoader.loadTagsForExistingRegistries(resources, base.getLayer(RegistryLayer.STATIC));
            var lookups = TagLoader.buildUpdatedLookups(base.getAccessForLoading(RegistryLayer.WORLDGEN), tags);
            var registries = base.replaceFrom(RegistryLayer.WORLDGEN,
                    RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES,
                            ForkJoinPool.commonPool()).join());
            var loaded = ReloadableServerRegistries.reload(registries, tags, resources,
                    ForkJoinPool.commonPool()).join();
            tags.forEach(tag -> tag.apply());
            access = loaded.layers().compositeAccess();
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(access)
                    .forEach(pending -> pending.apply());
            initialized = true;
        }
    }

    public static net.minecraft.core.RegistryAccess.Frozen registries() {
        initialize();
        return access;
    }
}
