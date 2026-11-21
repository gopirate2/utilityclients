package com.baggute.cheatclient.injector.agent;

import com.baggute.cheatclient.injector.AgentAttacher;
import com.baggute.cheatclient.injector.LogServer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Missing helpers must never become calls in Minecraft's render loop. */
public final class HookLinkageChecks implements Opcodes {
    private static final String ROOT = "com/baggute/cheatclient/";
    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final Map<String, Map<String, String>> METHODS = Map.of(
            "LateBridge", Map.ofEntries(
                    Map.entry("onHudRender", "(Ljava/lang/Object;)V"), Map.entry("onSubmitFeatures", "(Ljava/lang/Object;)V"),
                    Map.entry("onRenderFrame", "()V"), Map.entry("onClientTick", "()V"), Map.entry("onKeyPress", "(Ljava/lang/Object;I)Z"),
                    Map.entry("captureItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                    Map.entry("completeItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                    Map.entry("scaleMotion", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                    Map.entry("applyKnockback", "(Ljava/lang/Object;DDD)V"), Map.entry("explosionImpulse", "(Ljava/lang/Object;)Ljava/util/Optional;"),
                    Map.entry("onSeedChunk", "(Ljava/lang/Object;)V"), Map.entry("onSeedLogin", "(Ljava/lang/Object;)V"),
                    Map.entry("onSeedRespawn", "(Ljava/lang/Object;)V"), Map.entry("onSeedTick", "()V"),
                    Map.entry("onSeedDisconnect", "()V"), Map.entry("delayMovement", "(Ljava/lang/Object;)Z"), Map.entry("flushMovement", "()V")),
            "GameplayHooks", Map.ofEntries(
                    Map.entry("freezeInput", "(Ljava/lang/Object;)V"), Map.entry("camera", "(Ljava/lang/Object;)V"),
                    Map.entry("stepHeight", "(FLjava/lang/Object;)F"), Map.entry("lightmapOption", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                    Map.entry("turn", "(Ljava/lang/Object;DD)Z"), Map.entry("hideHand", "()Z"),
                    Map.entry("scroll", "(D)Z"), Map.entry("blockAttack", "(Ljava/lang/Object;Ljava/lang/Object;)Z")),
            "ContainerMouseHooks", Map.of("handle", "(Ljava/lang/Object;Ljava/lang/Object;I)Z"));

    private HookLinkageChecks() {}

    public static void run() throws Exception {
        byte[] original = fixture(CAMERA, "update");
        byte[] rewritten = RuntimePatcher.rewrite(CAMERA, original);
        require(rewritten != null, "camera rewrite fixture");

        Loader missing = helpers("GameplayHooks", false, false);
        Class<?> vanillaCamera = missing.define(CAMERA, original);
        require(!HookLinkage.ready(missing), "missing helper rejected before patching");
        require(new RuntimePatcher().transform(null, missing, CAMERA, vanillaCamera, null, original) == null,
                "direct transformer refuses invisible camera helper");
        invokeCamera(vanillaCamera, 20);

        // This is the exact pre-fix failure, with only the production guard bypassed.
        Loader unsafe = helpers("GameplayHooks", false, false);
        Class<?> unsafeCamera = unsafe.define(CAMERA, rewritten);
        try {
            unsafeCamera.getMethod("update").invoke(unsafeCamera.getConstructor().newInstance());
            throw new AssertionError("missing GameplayHooks did not reproduce original crash");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            require(expected.getCause() instanceof NoClassDefFoundError, "original missing-class crash reproduced");
        }

        Loader wrong = helpers(null, true, false);
        require(!HookLinkage.ready(wrong), "wrong camera method return type rejected");
        require(!HookLinkage.visible(wrong, CAMERA, rewritten), "wrong emitted-call signature refused");
        require(!HookLinkage.ready(helpers(null, false, true)), "helper initializer failure rejected before hooks");

        Loader good = helpers(null, false, false);
        require(HookLinkage.ready(good), "complete helper contract accepted");
        require(!HookLinkage.ready(new ClassLoader(good) {}), "parent-loaded helpers rejected for a child game loader");
        byte[] safe = new RuntimePatcher().transform(null, good, CAMERA, null, null, original);
        require(safe != null, "camera transforms when helpers are visible");
        invokeCamera(good.define(CAMERA, safe), 20);
        require(Class.forName(ROOT.replace('/', '.') + "GameplayHooks", false, good).getField("calls").getInt(null) == 20,
                "linked camera callback runs every frame");

        Counter rejected = new Counter(vanillaCamera, missing.define(MINECRAFT, fixture(MINECRAFT, "renderFrame")));
        RuntimePatcher.patch(rejected.instrumentation());
        BridgePatcher.patch(rejected.instrumentation());
        require(rejected.added == 0 && rejected.retransformed == 0, "failed full preflight leaves game bytecode untouched");

        Class<?> camera = Class.forName(CAMERA.replace('/', '.'), false, good);
        Counter accepted = new Counter(camera, good.define(MINECRAFT, fixture(MINECRAFT, "renderFrame")));
        Instrumentation instrumentation = accepted.instrumentation();
        RuntimePatcher.patch(instrumentation);
        RuntimePatcher.patch(instrumentation);
        require(accepted.added == 1, "repeated runtime injection reuses transformer");
        BridgePatcher.patch(instrumentation);
        BridgePatcher.patch(instrumentation);
        require(accepted.added == 2, "repeated bridge injection reuses transformer");
        for (ClassFileTransformer transformer : accepted.transformers) {
            String targetName = transformer instanceof RuntimePatcher ? CAMERA : MINECRAFT;
            String targetMethod = transformer instanceof RuntimePatcher ? "update" : "renderFrame";
            Loader isolated = new Loader();
            require(transformer.transform(null, isolated, targetName, null, null, fixture(targetName, targetMethod)) == null,
                    "installed transformers reject another loader without its helper");
        }
        checkPackagedContract();
        checkFreshClient();
        checkFabricRegistration();
        checkAttach(true);
        checkAttach(false);
        System.out.println("PASS: original missing-GameplayHooks crash reproduced; class/signature/initializer preflight; "
                + "both transformer loader guards; repeated injection; real Knot registration; isolated-JVM camera survival");
    }

    private static void checkFabricRegistration() throws Exception {
        Path classes = Files.createTempDirectory("uc-complete-hooks-");
        writeClasses(classes, false);
        Object knot = knot(classes, false);
        ClassLoader loader = targetLoader(knot);
        LinkedBlockingQueue<String> logs = new LinkedBlockingQueue<>();
        try (LogServer server = LogServer.start(logs::offer)) {
            Relay.connect(LogServer.HOST, server.port());
            Method classPath = ModLoader.class.getDeclaredMethod("classPath", Instrumentation.class, ClassLoader.class);
            classPath.setAccessible(true);
            Object operation = classPath.invoke(null, null, loader);
            if (operation == null) {
                Thread.sleep(50);
                throw new AssertionError("actual Fabric launcher registration selected: " + logs);
            }
            Method add = operation.getClass().getDeclaredMethod("add", Path.class);
            add.setAccessible(true);
            add.invoke(operation, classes);
            require(HookLinkage.ready(loader), "complete hooks load through actual Knot classloader");
            Field delegate = loader.getClass().getDeclaredField("delegate"); delegate.setAccessible(true);
            Field sources = delegate.get(loader).getClass().getDeclaredField("codeSources"); sources.setAccessible(true);
            require(((java.util.Set<?>) sources.get(delegate.get(loader))).contains(normalized(classes)),
                    "Fabric registered code source, not only URL search");
        } finally {
            Relay.close();
            clearLauncher();
            removeFixture(classes);
            clearNormalization();
        }
    }

    private static void checkFreshClient() throws Exception {
        for (boolean incomplete : List.of(true, false)) {
            Path classes = Files.createTempDirectory("uc-fresh-client-");
            Path jar = Files.createTempFile("uc-fresh-client-", ".jar");
            writeClass(classes, MINECRAFT, fixture(MINECRAFT, "renderFrame"));
            writeClass(classes, "net/fabricmc/api/ClientModInitializer", initializerApi());
            writeFreshJar(jar, incomplete);
            Object knot = knot(classes, true);
            ClassLoader loader = targetLoader(knot);
            try {
                seedNormalization(jar);
                Class<?> minecraft = Class.forName(MINECRAFT.replace('/', '.'), false, loader);
                Counter counter = new Counter(minecraft);
                require(ModLoader.load(counter.instrumentation(), jar.toString(), false) != incomplete,
                        "fresh client " + (incomplete ? "missing helper rejected" : "initialized after helper verification"));
                if (!incomplete) {
                    Class<?> initializer = Class.forName(ROOT.replace('/', '.') + "FixtureInitializer", false, loader);
                    require(initializer.getField("calls").getInt(null) == 1, "fresh client initializer ran once");
                    require(minecraft.getField("executions").getInt(null) == 1, "initializer scheduled through Minecraft executor");
                }
            } finally {
                clearLauncher(); clearNormalization(); removeFixture(classes); jar.toFile().deleteOnExit();
            }
        }
    }

    private static void checkAttach(boolean old) throws Exception {
        Path jar = Files.createTempFile(old ? "uc-old-client-" : "uc-current-client-", ".jar");
        writeJar(jar, old);
        Path classes = Files.createTempDirectory("uc-client-classes-");
        writeClasses(classes, old);
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process target = new ProcessBuilder(javaExe, "-XX:+EnableDynamicAgentLoading",
                "-Duc.regression.seedNormalization=" + Boolean.getBoolean("uc.regression.seedNormalization"),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", System.getProperty("java.class.path"),
                HookLinkageChecks.class.getName(), old ? "oldTarget" : "target", classes.toString()).redirectErrorStream(true).start();
        LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
                for (String line; (line = stream.readLine()) != null;) output.offer(line);
            } catch (java.io.IOException ignored) {}
        }, "uc-camera-fixture-output");
        reader.setDaemon(true); reader.start();
        try {
            require(awaitPrefix(output, "TARGET_READY", 10_000) != null, "actual Knot camera target started");
            LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
            try (LogServer server = LogServer.start(events::offer)) {
                String id = java.util.UUID.randomUUID().toString();
                Path agent = Path.of(System.getProperty("uc.regression.agentJar", "build/libs/injector.jar")).toAbsolutePath();
                AgentAttacher.inject(Long.toString(target.pid()), agent, Map.of("request", id, "host", LogServer.HOST,
                        "port", Integer.toString(server.port()), "mod", jar.toString(), "openGui", "false"));
                String stage = awaitPrefix(events, "UC_STAGE " + id + (old ? " FAILED " : " READY "), 5000);
                require(stage != null, "real agent reports " + (old ? "failed preflight" : "initialized client"));
                if (old) {
                    require(new String(Base64.getUrlDecoder().decode(stage.substring(stage.lastIndexOf(' ') + 1)),
                            java.nio.charset.StandardCharsets.UTF_8).contains("no new game hooks installed"),
                            "real agent reports failed helper preflight");
                    require(events.stream().noneMatch(line -> line.startsWith("UC_STAGE " + id + " READY ")),
                            "failed helper linkage never claims READY");
                }
                target.getOutputStream().write("check\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                target.getOutputStream().flush();
                String check = awaitPrefix(output, "CAMERA_POST_ATTACH ", 3000);
                require(check != null && check.equals("CAMERA_POST_ATTACH " + (old ? 0 : 20)) && target.isAlive(),
                        "post-attachment camera frames survive with exactly one compatible helper call per frame: " + check);
                if (!old) {
                    String second = java.util.UUID.randomUUID().toString();
                    AgentAttacher.inject(Long.toString(target.pid()), agent, Map.of("request", second, "host", LogServer.HOST,
                            "port", Integer.toString(server.port()), "mod", jar.toString(), "openGui", "false"));
                    require(awaitPrefix(events, "UC_STAGE " + second + " READY ", 5000) != null, "repeat compatible attach READY");
                    target.getOutputStream().write("check\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); target.getOutputStream().flush();
                    require("CAMERA_POST_ATTACH 20".equals(awaitPrefix(output, "CAMERA_POST_ATTACH ", 3000)),
                            "repeat attachment does not duplicate camera hooks");
                }
            }
        } finally {
            target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); reader.join(1000); Files.deleteIfExists(jar);
            removeFixture(classes);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { run(); return; }
        Object knot = knot(Path.of(args[1]), true);
        ClassLoader loader = targetLoader(knot);
        Class.forName(ROOT.replace('/', '.') + "UtilityClient", true, loader);
        Class.forName(MINECRAFT.replace('/', '.'), true, loader);
        Class<?> camera = Class.forName(CAMERA.replace('/', '.'), true, loader);
        Object instance = camera.getConstructor().newInstance();
        Method update = camera.getMethod("update");
        System.out.println("TARGET_READY"); System.out.flush();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        for (int i = 1; i <= 2000; i++) {
            update.invoke(instance);
            if (input.ready() && "check".equals(input.readLine())) {
                Class<?> gameplay = args[0].equals("oldTarget") ? null : Class.forName(ROOT.replace('/', '.') + "GameplayHooks", false, loader);
                Field calls = gameplay == null ? null : gameplay.getField("calls");
                int before = calls == null ? 0 : calls.getInt(null);
                for (int frame = 0; frame < 20; frame++) update.invoke(instance);
                System.out.println("CAMERA_POST_ATTACH " + (calls == null ? 0 : calls.getInt(null) - before)); System.out.flush();
            }
            Thread.sleep(20);
        }
    }

    private static Object knot(Path jar, boolean add) throws Exception {
        seedNormalization(jar);
        Class<?> api = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClassLoaderInterface");
        Class<?> env = Class.forName("net.fabricmc.api.EnvType");
        Class<?> provider = Class.forName("net.fabricmc.loader.impl.game.GameProvider");
        Method create = api.getDeclaredMethod("create", boolean.class, boolean.class, env, provider); create.setAccessible(true);
        Object access = create.invoke(null, false, false, env.getField("CLIENT").get(null), null);
        Class<?> knotType = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
        Object knot = knotType.getConstructor(env).newInstance(env.getField("CLIENT").get(null));
        Field field = knotType.getDeclaredField("classLoader"); field.setAccessible(true); field.set(knot, access);
        Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        base.getMethod("setLauncher", Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher")).invoke(null, knot);
        if (add) knotType.getMethod("addToClassPath", Path.class, String[].class).invoke(knot, jar, new String[0]);
        return knot;
    }

    private static final Map<Path, Path> SEEDED = new java.util.HashMap<>();

    /** Some Windows sandboxes deny GetFinalPathNameByHandle even for readable files. */
    @SuppressWarnings("unchecked")
    private static void seedNormalization(Path path) throws Exception {
        if (!Boolean.getBoolean("uc.regression.seedNormalization")) return;
        require(Files.exists(path), "normalization fixture exists");
        Field field = Class.forName("net.fabricmc.loader.impl.util.LoaderUtil").getDeclaredField("pathNormalizationCache");
        field.setAccessible(true);
        Map<Path, Path> cache = (Map<Path, Path>) field.get(null);
        SEEDED.put(path, cache.put(path, path.toAbsolutePath().normalize()));
    }

    private static Path normalized(Path path) throws Exception {
        return Boolean.getBoolean("uc.regression.seedNormalization") ? path.toAbsolutePath().normalize() : path.toRealPath();
    }

    @SuppressWarnings("unchecked")
    private static void clearNormalization() throws Exception {
        if (SEEDED.isEmpty()) return;
        Field field = Class.forName("net.fabricmc.loader.impl.util.LoaderUtil").getDeclaredField("pathNormalizationCache");
        field.setAccessible(true); Map<Path, Path> cache = (Map<Path, Path>) field.get(null);
        SEEDED.forEach((path, original) -> { if (original == null) cache.remove(path); else cache.put(path, original); });
        SEEDED.clear();
    }

    private static ClassLoader targetLoader(Object knot) throws Exception {
        return (ClassLoader) knot.getClass().getMethod("getTargetClassLoader").invoke(knot);
    }

    private static void clearLauncher() throws Exception {
        Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        Field launcher = base.getDeclaredField("launcher"); launcher.setAccessible(true); launcher.set(null, null);
    }

    private static Loader helpers(String missing, boolean wrongCamera, boolean brokenInitializer) {
        Loader loader = new Loader();
        for (String name : METHODS.keySet()) if (!name.equals(missing))
            loader.define(ROOT + name, helper(name, wrongCamera, brokenInitializer));
        return loader;
    }

    static byte[] helper(String simple, boolean wrongCamera, boolean brokenInitializer) {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String name = ROOT + simple;
        out.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        out.visitField(ACC_PUBLIC | ACC_STATIC, "blocked", "Z", null, null).visitEnd();
        out.visitField(ACC_PUBLIC | ACC_STATIC, "lastArgument", "Ljava/lang/Object;", null, null).visitEnd();
        out.visitField(ACC_PUBLIC | ACC_STATIC, "lastAction", "I", null, null).visitEnd();
        for (var entry : METHODS.get(simple).entrySet()) {
            out.visitField(ACC_PUBLIC | ACC_STATIC, entry.getKey() + "Calls", "I", null, null).visitEnd();
            String desc = wrongCamera && entry.getKey().equals("camera") ? "(Ljava/lang/Object;)I" : entry.getValue();
            MethodVisitor method = out.visitMethod(ACC_PUBLIC | ACC_STATIC, entry.getKey(), desc, null, null); method.visitCode();
            method.visitFieldInsn(GETSTATIC, name, entry.getKey() + "Calls", "I"); method.visitInsn(ICONST_1); method.visitInsn(IADD);
            method.visitFieldInsn(PUTSTATIC, name, entry.getKey() + "Calls", "I");
            if (entry.getKey().equals("onHudRender") || entry.getKey().equals("onSubmitFeatures") || entry.getKey().equals("onKeyPress")) {
                method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(PUTSTATIC, name, "lastArgument", "Ljava/lang/Object;");
                if (entry.getKey().equals("onKeyPress")) {
                    method.visitVarInsn(ILOAD, 1); method.visitFieldInsn(PUTSTATIC, name, "lastAction", "I");
                }
            }
            if (entry.getKey().equals("camera")) {
                method.visitFieldInsn(GETSTATIC, name, "calls", "I"); method.visitInsn(ICONST_1); method.visitInsn(IADD);
                method.visitFieldInsn(PUTSTATIC, name, "calls", "I");
            }
            switch (Type.getReturnType(desc).getSort()) {
                case Type.VOID -> method.visitInsn(RETURN);
                case Type.FLOAT -> { method.visitInsn(FCONST_0); method.visitInsn(FRETURN); }
                case Type.OBJECT -> { method.visitInsn(ACONST_NULL); method.visitInsn(ARETURN); }
                default -> {
                    if (entry.getKey().equals("onKeyPress")) method.visitFieldInsn(GETSTATIC, name, "blocked", "Z");
                    else method.visitInsn(ICONST_0);
                    method.visitInsn(IRETURN);
                }
            }
            method.visitMaxs(0, 0); method.visitEnd();
        }
        if (brokenInitializer && simple.equals("GameplayHooks")) {
            MethodVisitor init = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
            init.visitTypeInsn(NEW, "java/lang/IllegalStateException"); init.visitInsn(DUP);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); init.visitInsn(ATHROW);
            init.visitMaxs(0, 0); init.visitEnd();
        }
        out.visitEnd(); return out.toByteArray();
    }

    private static byte[] fixture(String name, String methodName) {
        if (name.equals(MINECRAFT)) return BridgePatchChecks.fixture(name);
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC, "calls", "I", null, null).visitEnd();
        MethodVisitor init = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor body = out.visitMethod(ACC_PUBLIC, methodName, "()V", null, null); body.visitCode();
        body.visitVarInsn(ALOAD, 0); body.visitInsn(DUP); body.visitFieldInsn(GETFIELD, name, "calls", "I");
        body.visitInsn(ICONST_1); body.visitInsn(IADD); body.visitFieldInsn(PUTFIELD, name, "calls", "I"); body.visitInsn(RETURN);
        body.visitMaxs(0, 0); body.visitEnd(); out.visitEnd(); return out.toByteArray();
    }

    private static byte[] oldClient() {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, ROOT + "UtilityClient", null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC | ACC_STATIC, "moduleManager", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
        init.visitTypeInsn(NEW, "java/lang/Object"); init.visitInsn(DUP); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, ROOT + "UtilityClient", "moduleManager", "Ljava/lang/Object;"); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor unloaded = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "isUnloaded", "()Z", null, null); unloaded.visitCode();
        unloaded.visitInsn(ICONST_0); unloaded.visitInsn(IRETURN); unloaded.visitMaxs(0, 0); unloaded.visitEnd();
        MethodVisitor ready = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "isInitialized", "()Z", null, null); ready.visitCode();
        ready.visitInsn(ICONST_1); ready.visitInsn(IRETURN); ready.visitMaxs(0, 0); ready.visitEnd();
        MethodVisitor unload = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "unload", "()V", null, null); unload.visitCode(); unload.visitInsn(RETURN); unload.visitMaxs(0, 0); unload.visitEnd();
        out.visitEnd(); return out.toByteArray();
    }

    private static byte[] initializerApi() {
        ClassWriter out = new ClassWriter(0);
        out.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "net/fabricmc/api/ClientModInitializer", null, "java/lang/Object", null);
        out.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "onInitializeClient", "()V", null, null).visitEnd(); out.visitEnd();
        return out.toByteArray();
    }

    private static byte[] initializer() {
        String name = ROOT + "FixtureInitializer";
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", new String[]{"net/fabricmc/api/ClientModInitializer"});
        out.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor init = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor method = out.visitMethod(ACC_PUBLIC, "onInitializeClient", "()V", null, null); method.visitCode();
        method.visitFieldInsn(GETSTATIC, name, "calls", "I"); method.visitInsn(ICONST_1); method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, name, "calls", "I"); method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd(); out.visitEnd();
        return out.toByteArray();
    }

    private static void writeFreshJar(Path jar, boolean incomplete) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : METHODS.keySet()) if (!incomplete || !name.equals("GameplayHooks"))
                entry(out, ROOT + name + ".class", helper(name, false, false));
            entry(out, ROOT + "FixtureInitializer.class", initializer());
            entry(out, "fabric.mod.json", "{\"entrypoints\":{\"client\":[\"com.baggute.cheatclient.FixtureInitializer\"]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void writeJar(Path jar, boolean old) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            // The selected file is current; the target's already-loaded old
            // classes still lack a helper and must fail the runtime preflight.
            for (String name : METHODS.keySet())
                entry(out, ROOT + name + ".class", helper(name, false, false));
            entry(out, CAMERA + ".class", fixture(CAMERA, "update"));
            for (var game : BridgePatchChecks.fixtures().entrySet()) entry(out, game.getKey() + ".class", game.getValue());
            entry(out, ROOT + "UtilityClient.class", oldClient());
            entry(out, "fabric.mod.json", "{\"id\":\"utilityclient\",\"version\":\"1.0.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public static Map<String, byte[]> integrityFixture(String id) {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        for (String name : METHODS.keySet()) entries.put(ROOT + name + ".class", helper(name, false, false));
        entries.put(ROOT + "UtilityClient.class", oldClient());
        entries.put("fabric.mod.json", ("{\"id\":\"" + id + "\",\"version\":\"1.0.0\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return entries;
    }

    private static void checkPackagedContract() throws Exception {
        Path project = Path.of("..").toAbsolutePath().normalize();
        String staged = System.getProperty("uc.regression.clientJar");
        List<Path> jars = staged == null ? List.of(project.resolve("build/libs/cheatclient-1.0.0.jar"), project.resolve("linux/build/libs/cheatclient-1.0.0.jar")) : List.of(Path.of(staged));
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
                for (var helper : METHODS.entrySet()) {
                    ClassNode node = new ClassNode();
                    try (var input = archive.getInputStream(archive.getJarEntry(ROOT + helper.getKey() + ".class"))) {
                        new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                    for (var method : helper.getValue().entrySet()) require(node.methods.stream().anyMatch(m ->
                            m.name.equals(method.getKey()) && m.desc.equals(method.getValue())
                                    && (m.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC)),
                            "published helper contract " + helper.getKey() + "." + method.getKey());
                }
            }
        }
    }

    private static void writeClasses(Path directory, boolean old) throws Exception {
        for (String name : METHODS.keySet()) if (!old || !name.equals("GameplayHooks"))
            writeClass(directory, ROOT + name, helper(name, false, false));
        writeClass(directory, CAMERA, fixture(CAMERA, "update"));
        for (var game : BridgePatchChecks.fixtures().entrySet()) writeClass(directory, game.getKey(), game.getValue());
        writeClass(directory, ROOT + "UtilityClient", oldClient());
    }

    private static void writeClass(Path directory, String name, byte[] bytes) throws Exception {
        Path file = directory.resolve(name + ".class"); Files.createDirectories(file.getParent()); Files.write(file, bytes);
    }

    private static void removeFixture(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                require(file.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()), "fixture cleanup boundary");
                Files.deleteIfExists(file);
            }
        }
    }

    private static void entry(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name)); out.write(bytes); out.closeEntry();
    }

    private static void invokeCamera(Class<?> type, int ticks) throws Exception {
        Object instance = type.getConstructor().newInstance();
        for (int i = 0; i < ticks; i++) type.getMethod("update").invoke(instance);
        require(type.getField("calls").getInt(instance) == ticks, "camera body remains valid");
    }

    private static String awaitPrefix(LinkedBlockingQueue<String> lines, String prefix, long timeout) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < end) {
            String line = lines.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line != null && line.startsWith(prefix)) return line;
        }
        return null;
    }

    private static final class Loader extends ClassLoader {
        Loader() { super(ClassLoader.getPlatformClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
    }

    private static final class Counter {
        final Class<?>[] targets;
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        int added, retransformed;
        Counter(Class<?>... targets) { this.targets = targets; }
        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isRetransformClassesSupported", "isModifiableClass" -> true;
                        case "getAllLoadedClasses" -> targets;
                        case "addTransformer" -> { added++; transformers.add((ClassFileTransformer) args[0]); yield null; }
                        case "retransformClasses" -> { retransformed++; yield null; }
                        case "toString" -> "hook-fixture-instrumentation";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
