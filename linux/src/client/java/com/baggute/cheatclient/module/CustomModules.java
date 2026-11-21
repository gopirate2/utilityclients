package com.baggute.cheatclient.module;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.api.ModuleProvider;
import com.baggute.cheatclient.config.Storage;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** One loader per extension; a broken JAR cannot prevent other JARs from loading. */
public final class CustomModules implements AutoCloseable {
    public static final String SERVICE = "META-INF/services/com.baggute.cheatclient.api.ModuleProvider";
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    public static Path directory() { return Storage.root().resolve("utilityclient/modules"); }
    public List<String> diagnostics() { return List.copyOf(diagnostics); }
    public void load(ModuleManager manager, Path directory) {
        try {
            Files.createDirectories(directory);
            for (String name : new String[]{"README.md", "build.ps1", "build.sh", "example/src/example/HelloProvider.java", "example/resources/META-INF/services/com.baggute.cheatclient.api.ModuleProvider"}) {
                Path target = directory.resolve("SDK").resolve(name);
                if (!Files.exists(target)) try (var input = CustomModules.class.getResourceAsStream("/module-sdk/" + name)) {
                    if (input != null) { Files.createDirectories(target.getParent()); Files.copy(input, target); }
                }
            }
            List<Path> jars;
            try (var files = Files.list(directory)) { jars = files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().limit(65).toList(); }
            if (jars.size() > 64) throw new IOException("At most 64 custom JARs are supported");
            for (Path path : jars) {
                URLClassLoader loader = null;
                try {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16L * 1024 * 1024) throw new IOException("JAR is linked or larger than 16 MiB");
                    List<String> providers;
                    try (JarFile jar = new JarFile(path.toFile())) {
                        var entry = jar.getJarEntry(SERVICE);
                        if (entry == null || entry.getSize() > 8192) throw new IOException("Missing or oversized ModuleProvider service file");
                        try (var input = jar.getInputStream(entry)) {
                            byte[] bytes = input.readNBytes(8193);
                            if (bytes.length > 8192) throw new IOException("Oversized service file");
                            providers = new String(bytes, StandardCharsets.UTF_8).lines().map(s -> s.split("#",2)[0].trim()).filter(s -> !s.isEmpty()).distinct().toList();
                        }
                        if (providers.isEmpty() || providers.size() > 16) throw new IOException("Expected 1-16 providers");
                        for (String name : providers) if (!name.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+") || jar.getJarEntry(name.replace('.','/') + ".class") == null)
                            throw new IOException("Provider class is absent: " + name);
                    }
                    loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, Module.class.getClassLoader());
                    List<Module> batch = new ArrayList<>();
                    for (String name : providers) {
                        ModuleProvider provider = Class.forName(name, true, loader).asSubclass(ModuleProvider.class).getDeclaredConstructor().newInstance();
                        if (provider.apiVersion() != 1) throw new IOException("Unsupported module API version");
                        var modules = Objects.requireNonNull(provider.createModules(), "Provider returned null");
                        if (modules.size() > 128 || batch.size() + modules.size() > 128) throw new IOException("At most 128 modules per JAR");
                        batch.addAll(modules);
                    }
                    manager.registerCustom(batch);
                    loaders.add(loader); loader = null;
                    diagnostics.add(path.getFileName() + ": loaded " + batch.size() + " modules");
                } catch (Throwable error) {
                    diagnostics.add(path.getFileName() + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
                    UtilityClient.LOGGER.warn("Custom module JAR failed: {}", path.getFileName(), error);
                } finally { if (loader != null) try { loader.close(); } catch (IOException ignored) {} }
            }
            Files.write(directory.resolve("load-report.txt"), diagnostics, StandardCharsets.UTF_8);
        } catch (IOException error) { UtilityClient.LOGGER.warn("Custom module folder unavailable", error); }
    }
    @Override public void close() {
        for (URLClassLoader loader : loaders) try { loader.close(); } catch (IOException ignored) {}
        loaders.clear();
    }
}
