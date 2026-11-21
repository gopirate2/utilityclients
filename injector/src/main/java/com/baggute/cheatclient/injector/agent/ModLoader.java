package com.baggute.cheatclient.injector.agent;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads a Fabric mod jar into an already-running game.
 *
 * <p>This is what makes injection mean something: Fabric never scans the jar, so
 * the mod is absent from the loader's list, yet its classes are on the game's own
 * classloader and its client initializer runs.
 *
 * <p>The chain, all reflective because none of it is on the agent's classpath:
 * <ol>
 *   <li>register the jar with Fabric's live launcher, or extend an ordinary
 *       {@code URLClassLoader} when the target does not use Knot;</li>
 *   <li>register every nested {@code META-INF/jars/*.jar} through the same
 *       classpath operation and verify the complete runtime helper contract;</li>
 *   <li>register each {@code *.mixins.json} with Mixin's runtime;</li>
 *   <li>instantiate every {@link net.fabricmc.api.ClientModInitializer} found in
 *       the jar and call it;</li>
 *   <li>open the click GUI directly, because a key binding registered after the
 *       game's input pass may never be polled.</li>
 * </ol>
 *
 * <p>Steps 1-2 are what the rest depends on; 3-5 are best effort and each one
 * reports separately, so a partial load is visible instead of silent.
 */
final class ModLoader {

    private static final String CLIENT_INITIALIZER = "net.fabricmc.api.ClientModInitializer";

    private ModLoader() {
    }

    static boolean load(Instrumentation inst, String jarPath, boolean openGui) {
        File jar = new File(jarPath);
        if (!jar.isFile()) {
            Relay.log("mod", "client jar not found: " + jarPath);
            return false;
        }

        Class<?> minecraft = findLoaded(inst, "net.minecraft.client.Minecraft");
        if (minecraft == null) {
            Relay.log("mod", "Minecraft is not loaded yet; inject after the title screen");
            return false;
        }
        ClassLoader gameLoader = minecraft.getClassLoader();

        Class<?> existing = findLoaded(inst, "com.baggute.cheatclient.UtilityClient");
        if (existing != null) {
            if (existing.getClassLoader() != gameLoader) {
                Relay.log("mod", "existing client belongs to another classloader; restart the game with the matching client JAR");
                return false;
            }
            // Loading twice re-runs onInitializeClient, which registers the key
            // bindings and the module list a second time.
            Relay.log("mod", "client already loaded in this JVM; leaving it alone");
            Relay.log("mod", "live build: " + provenance(existing));
            Relay.log("mod", "loaded classes cannot be replaced; restart the game to run "
                    + jar.getName());
            try {
                if (Boolean.TRUE.equals(existing.getMethod("isUnloaded").invoke(null))) {
                    Relay.log("mod", "client was unloaded; restart the game before reinjecting");
                    return false;
                }
                return existing.getField("moduleManager").get(null) != null
                        && Boolean.TRUE.equals(existing.getMethod("isInitialized").invoke(null))
                        && HookLinkage.ready(existing.getClassLoader());
            } catch (ReflectiveOperationException failure) { return false; }
        }

        ClassPath classPath = classPath(inst, gameLoader);
        if (classPath == null || !appendJar(classPath, jar)) return false;
        if (!appendNested(classPath, jar)) return false;
        if (!HookLinkage.ready(gameLoader)) return false;

        // Already-loaded classes need shape-preserving runtime hooks. Registering
        // the mixins now creates a mixture of woven and unwoven classes instead.
        if (!Boolean.getBoolean("utilityclient.lateInit")) registerMixins(jar, gameLoader);
        Relay.stage("INITIALIZING", "Initializing client entrypoints");
        InitResult result = initialize(jar, gameLoader, minecraft);
        List<String> initializers = result.initialized();

        if (openGui && result.success()) {
            openClickGui(minecraft, gameLoader);
        }

        Relay.log("mod", initializers.isEmpty()
                ? "loaded classes but found no client initializer"
                : "client initialized (" + initializers.size() + " entrypoint(s))");
        return result.success();
    }

    // ---- classpath ----

    @FunctionalInterface
    private interface ClassPath {
        void add(Path path) throws ReflectiveOperationException;
    }

    /** Knot's launcher updates its code-source registry as well as its URL search. */
    private static ClassPath classPath(Instrumentation inst, ClassLoader gameLoader) {
        if (gameLoader.getClass().getName().startsWith("net.fabricmc.loader.impl.launch.knot.")) {
            try {
                // Fabric implementation classes belong to Knot's defining loader.
                // Asking Knot itself can define a second launcher copy with no live state.
                ClassLoader fabricLoader = gameLoader.getClass().getClassLoader();
                Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false, fabricLoader);
                Class<?> api = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false, fabricLoader);
                Object launcher = base.getMethod("getLauncher").invoke(null);
                if (launcher == null || api.getMethod("getTargetClassLoader").invoke(launcher) != gameLoader)
                    throw new IllegalStateException("Fabric launcher does not own the target game loader");
                Method add = api.getMethod("addToClassPath", Path.class, String[].class);
                return path -> add.invoke(launcher, path, new String[0]);
            } catch (Throwable failure) {
                Relay.log("mod", "cannot register client with Fabric: " + unwrap(failure));
                return null;
            }
        }
        URLClassLoader urlLoader = asUrlLoader(gameLoader);
        if (urlLoader == null || !openJavaNet(inst)) {
            Relay.log("mod", "cannot extend game classloader " + gameLoader.getClass().getName());
            return null;
        }
        try {
            Method add = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            add.setAccessible(true);
            return path -> {
                try { add.invoke(urlLoader, path.toUri().toURL()); }
                catch (java.net.MalformedURLException failure) { throw new ReflectiveOperationException(failure); }
            };
        } catch (Throwable failure) {
            Relay.log("mod", "addURL unreachable: " + failure);
            return null;
        }
    }

    private static boolean appendJar(ClassPath classPath, File jar) {
        try {
            classPath.add(jar.toPath().toAbsolutePath());
            Relay.log("mod", "added " + jar.getName() + " to the game classloader");
            return true;
        } catch (Throwable failure) {
            Relay.log("mod", "could not add " + jar.getName() + ": " + unwrap(failure));
            return false;
        }
    }

    /**
     * Fabric's nested jars (cloth-config, seedfinding, latticg) are invisible to a
     * plain URLClassLoader, so each one is unpacked to temp and added as well.
     * Without this the client dies on its first seedfinding reference.
     */
    private static boolean appendNested(ClassPath classPath, File jar) {
        try (JarFile file = new JarFile(jar)) {
            List<JarEntry> nested = file.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith("META-INF/jars/"))
                    .filter(entry -> entry.getName().endsWith(".jar"))
                    .toList();

            for (JarEntry entry : nested) {
                Path temp = Files.createTempFile("uc-nested-", ".jar");
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                classPath.add(temp);
                if (!appendNested(classPath, temp.toFile())) return false;
            }
            if (!nested.isEmpty()) {
                Relay.log("mod", "added " + nested.size() + " nested jar(s)");
            }
            return true;
        } catch (Throwable failure) {
            Relay.log("mod", "nested jars failed: " + unwrap(failure));
            return false;
        }
    }

    // ---- mixins ----

    private static void registerMixins(File jar, ClassLoader gameLoader) {
        try (JarFile file = new JarFile(jar)) {
            List<String> configs = file.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".mixins.json"))
                    .toList();
            if (configs.isEmpty()) return;

            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", true, gameLoader);
            Method add = mixins.getMethod("addConfiguration", String.class);
            for (String config : configs) {
                add.invoke(null, config);
                Relay.log("mod", "mixin config registered: " + config);
            }
            Relay.log("mod", "note: mixins apply to classes loaded after this point; "
                    + "already-loaded Minecraft classes keep their original bytecode");
        } catch (Throwable failure) {
            Relay.log("mod", "mixin registration failed: " + unwrap(failure));
        }
    }

    // ---- entrypoints ----

    private record InitResult(List<String> requested, List<String> initialized, List<String> failed) {
        boolean success() { return !requested.isEmpty() && failed.isEmpty() && initialized.containsAll(requested); }
    }

    private static InitResult initialize(File jar, ClassLoader gameLoader, Class<?> minecraft) {
        List<String> done = new ArrayList<>();
        List<String> requested = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            try (JarFile file = new JarFile(jar)) {
                var metadata = file.getJarEntry("fabric.mod.json");
                if (metadata == null) throw new IllegalArgumentException("fabric.mod.json missing");
                try (var reader = new java.io.InputStreamReader(file.getInputStream(metadata), java.nio.charset.StandardCharsets.UTF_8)) {
                    var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    var entries = root.getAsJsonObject("entrypoints").getAsJsonArray("client");
                    if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("client entrypoints missing");
                    for (var entry : entries) {
                        String name;
                        if (entry.isJsonPrimitive()) name = entry.getAsString();
                        else {
                            var object = entry.getAsJsonObject();
                            if (object.has("adapter") && !"default".equals(object.get("adapter").getAsString()))
                                throw new IllegalArgumentException("unsupported entrypoint adapter");
                            name = object.get("value").getAsString();
                        }
                        if (name.isBlank() || name.contains("::")) throw new IllegalArgumentException("unsupported client entrypoint " + name);
                        requested.add(name);
                    }
                }
            }
            Class<?> initializerType = Class.forName(CLIENT_INITIALIZER, false, gameLoader);

            for (String name : requested) {
                try {
                    Class<?> type = Class.forName(name, false, gameLoader);
                    if (!initializerType.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers()))
                        throw new IllegalArgumentException("not a concrete client initializer");
                    Method run = initializerType.getMethod("onInitializeClient");
                    onClientThread(minecraft, () -> {
                        Thread thread = Thread.currentThread();
                        ClassLoader previous = thread.getContextClassLoader();
                        try {
                            thread.setContextClassLoader(gameLoader);
                            run.invoke(type.getDeclaredConstructor().newInstance());
                            return null;
                        } finally { thread.setContextClassLoader(previous); }
                    });
                    done.add(name);
                    Relay.log("mod", "initialized " + name);
                } catch (Throwable failure) {
                    failed.add(name);
                    Relay.log("mod", name + " threw during init: " + unwrap(failure));
                }
            }
        } catch (Throwable failure) {
            failed.add("entrypoint metadata or initializer interface");
            Relay.log("mod", "cannot resolve " + CLIENT_INITIALIZER + ": " + unwrap(failure));
        }
        return new InitResult(List.copyOf(requested), List.copyOf(done), List.copyOf(failed));
    }

    /** Entrypoints and teardown can touch the world and GLFW only on the game thread. */
    static <T> T onClientThread(Class<?> minecraft, java.util.concurrent.Callable<T> callback) throws Exception {
        Object client = minecraft.getMethod("getInstance").invoke(null);
        if (client == null) throw new IllegalStateException("Minecraft instance is not ready");
        var task = new java.util.concurrent.FutureTask<T>(callback);
        minecraft.getMethod("execute", Runnable.class).invoke(client, task);
        try { return task.get(30, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException failure) { task.cancel(false); throw failure; }
    }

    private static List<String> classNames(File jar) {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar)) {
            for (JarEntry entry : file.stream().toList()) {
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                if (name.startsWith("META-INF/")) continue;
                if (name.equals("module-info.class")) continue;
                names.add(name.substring(0, name.length() - 6).replace('/', '.'));
            }
        } catch (Throwable failure) {
            Relay.log("mod", "could not scan " + jar.getName() + ": " + failure);
        }
        return names;
    }

    // ---- gui ----

    /**
     * Opens the click GUI without the key binding.
     *
     * <p>{@code KeyMappingHelper.registerKeyMapping} is a load-time registration;
     * a mapping added after the input pass is set up may never be polled, and the
     * user sees a working injection that does nothing. Calling setScreen on the
     * render thread sidesteps that completely.
     */
    private static void openClickGui(Class<?> minecraft, ClassLoader gameLoader) {
        try {
            Object instance = minecraft.getMethod("getInstance").invoke(null);
            if (instance == null) return;

            Class<?> screenType = Class.forName("net.minecraft.client.gui.screens.Screen",
                    true, gameLoader);
            Class<?> guiType = Class.forName("com.baggute.cheatclient.gui.ClickGuiScreen",
                    true, gameLoader);

            java.lang.reflect.Field guiField = minecraft.getDeclaredField("gui");
            guiField.setAccessible(true);
            Object guiInstance = guiField.get(instance);

            Method setScreen = guiInstance.getClass().getMethod("setScreen", screenType);
            // Constructed inside the runnable, on the render thread: a screen's
            // constructor touches fonts and layout, which are not thread-safe and
            // throw when built from the attach thread.
            Runnable open = () -> {
                try {
                    setScreen.invoke(guiInstance, guiType.getDeclaredConstructor().newInstance());
                    Relay.log("mod", "click GUI opened");
                } catch (Throwable failure) {
                    Relay.log("mod", "opening the GUI failed: " + unwrap(failure));
                }
            };

            Method execute = minecraft.getMethod("execute", Runnable.class);
            execute.invoke(instance, open);
            Relay.log("mod", "click GUI opened on the render thread");
        } catch (Throwable failure) {
            Relay.log("mod", "could not open the GUI: " + unwrap(failure));
        }
    }

    // ---- plumbing ----

    /** Where the live client class was loaded from, so an old copy is named. */
    private static String provenance(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            String where = source == null || source.getLocation() == null
                    ? "unknown location"
                    : new File(source.getLocation().toURI()).getAbsolutePath();
            Package info = type.getPackage();
            String version = info == null || info.getImplementationVersion() == null
                    ? "" : " (v" + info.getImplementationVersion() + ")";
            return where + version;
        } catch (Throwable failure) {
            return "unknown location";
        }
    }

    private static Class<?> findLoaded(Instrumentation inst, String name) {
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.getName().equals(name)) return type;
        }
        return null;
    }

    private static URLClassLoader asUrlLoader(ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader url) return url;
            current = current.getParent();
        }
        return null;
    }

    /** {@code URLClassLoader.addURL} is protected; java.net is not open by default. */
    private static boolean openJavaNet(Instrumentation inst) {
        try {
            Module self = ModLoader.class.getModule();
            Module base = URLClassLoader.class.getModule();
            if (base.isOpen("java.net", self)) return true;
            inst.redefineModule(base, Set.of(), Map.of(), Map.of("java.net", Set.of(self)),
                    Set.of(), Map.of());
            return base.isOpen("java.net", self);
        } catch (Throwable failure) {
            Relay.log("mod", "redefineModule failed: " + failure);
            return false;
        }
    }

    private static String unwrap(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
