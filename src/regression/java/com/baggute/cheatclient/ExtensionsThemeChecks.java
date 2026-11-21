package com.baggute.cheatclient;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.CustomModules;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.misc.Appearance;
import com.baggute.cheatclient.gui.ThemePalette;
import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.settings.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

public final class ExtensionsThemeChecks {
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        MinecraftBootstrap.initialize();
        Path root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "module-sdk-");
        ModuleManager manager = new ModuleManager(); manager.init();
        Appearance appearance = (Appearance) manager.getByName("Appearance");
        Set<Integer> backgrounds = new HashSet<>();
        for (String palette : appearance.palette.choices()) {
            if (palette.equals("Custom")) continue;
            appearance.palette.set(palette); var resolved = ThemePalette.resolve(appearance);
            require(backgrounds.add(resolved.background()), "distinct preset backgrounds");
            require(resolved.text() != resolved.background(), "text remains visible");
        }
        appearance.palette.set("Custom"); appearance.accent.set(0x123456); appearance.background.set(0x654321);
        require(ThemePalette.resolve(appearance).accent() == 0xFF123456, "custom colors normalized opaque");
        for (String style : appearance.style.choices()) { appearance.style.set(style); require(ThemePalette.resolve(appearance).style().equals(style), "style resolution"); }
        appearance.style.cycle(1); require(appearance.style.get().equals("Flat"), "choice wraps");
        JsonObject config = new ConfigManager(manager, root.resolve("config.json")).snapshot();
        config.getAsJsonObject("modules").getAsJsonObject("Appearance").getAsJsonObject("settings").addProperty("Style", "Imaginary");
        require(!new ConfigManager(manager, root.resolve("config.json")).validateSnapshot(config), "unknown choices rejected before apply");
        Path classes = root.resolve("classes"); Files.createDirectories(classes);
        Path source = root.resolve("HelloProvider.java");
        try (var input = ExtensionsThemeChecks.class.getResourceAsStream("/module-sdk/example/src/example/HelloProvider.java")) { Files.copy(Objects.requireNonNull(input), source); }
        if (System.getProperty("uc.regression.classRoot") != null) {
        String production = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(s -> s.contains("activation-") && s.endsWith("-classes.jar")).findFirst().orElseThrow();
        Path classRoot = Path.of(System.getProperty("uc.regression.classRoot"));
        Path buildArgs = root.resolve("compile.args"), deps = root.resolve("compile-deps.txt");
        var gson = new com.google.gson.Gson();
        Files.write(buildArgs, List.of("--release", "25", "-d", classes.toString(), source.toString()).stream().map(gson::toJson).toList());
        Files.writeString(deps, production + "\n");
        Process compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp",
                classRoot.getParent().resolve("compiler").toString(), "BuildCompiler", classRoot.toString(),
                classRoot.getParent().resolve("class-index.txt").toString(), buildArgs.toString(), deps.toString()).redirectErrorStream(true).start();
        if (!compiler.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { compiler.destroyForcibly(); throw new AssertionError("SDK compilation timed out"); }
        require(compiler.exitValue() == 0, "shipped SDK example compiles: " + new String(compiler.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            require(compiler != null, "SDK compilation requires a JDK");
            var diagnostics = new java.io.StringWriter();
            try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
                boolean compiled = compiler.getTask(diagnostics, files, null,
                        List.of("--release", "25", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                        null, files.getJavaFileObjects(source.toFile())).call();
                require(compiled, "shipped SDK example compiles: " + diagnostics);
            }
        }
        Path jars = root.resolve("jars"); Files.createDirectories(jars);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jars.resolve("01-example.jar")))) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/'))); Files.copy(file,jar); jar.closeEntry();
                }
            }
            jar.putNextEntry(new JarEntry(CustomModules.SERVICE)); jar.write("example.HelloProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
        }
        Files.write(jars.resolve("00-broken.jar"), new byte[]{1,2,3});
        Files.copy(jars.resolve("01-example.jar"), jars.resolve("02-duplicate.jar"));
        try (CustomModules loader = new CustomModules()) {
            loader.load(manager,jars);
            Module imported = manager.getByName("HelloCustom");
            require(imported != null && imported.getSettings().stream().anyMatch(s -> s instanceof KeybindSetting), "actual JAR imported with keybind");
            require(manager.getModules().stream().filter(m -> m.getName().equals("HelloCustom")).count() == 1, "duplicate JAR cannot replace module");
            require(loader.diagnostics().size() == 3 && Files.readString(jars.resolve("load-report.txt")).contains("loaded 1"), "failure diagnostics retained");
            int before = manager.getModules().size();
            try { manager.registerCustom(List.of(new Module("UniqueFixture", "fixture", Category.MISC){}, new Module("Appearance", "duplicate", Category.MISC){})); throw new AssertionError("duplicate accepted"); }
            catch (IllegalArgumentException expected) { require(manager.getModules().size() == before, "batch rejection is atomic"); }
            JsonObject saved = new ConfigManager(manager, root.resolve("config.json")).snapshot();
            require(saved.getAsJsonObject("modules").getAsJsonObject("HelloCustom").getAsJsonObject("settings").has("Greeting"), "extension settings participate in config");
        }
        manager.closeCustomModules();
        System.out.println("PASS: shipped SDK compiles/loads, broken and duplicate JAR isolation, atomic batches, persisted plugin settings, theme presets/custom colors and validated choice controls");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
