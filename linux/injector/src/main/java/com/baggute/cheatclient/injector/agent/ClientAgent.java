package com.baggute.cheatclient.injector.agent;

import com.baggute.cheatclient.injector.AgentAttacher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * The agent half: what actually runs inside the target JVM after
 * {@code VirtualMachine.loadAgent}.
 *
 * <p>Order matters. The log channel is opened first so every later step has
 * somewhere to report, then stealth is applied before anything can write a file,
 * then the mod is loaded into the game's own classloader, then hooks.
 *
 * <p>Nothing here may throw out of {@code agentmain}: an exception in an agent
 * surfaces as a failed attach in the injector, which reads like the injector
 * broke rather than the target rejecting one step.
 */
public final class ClientAgent {

    /** Hooks already rewritten in this JVM; a second inject must not duplicate them. */
    private static final Set<String> INSTALLED = new LinkedHashSet<>();

    private static final String PORT_PROPERTY = "utilityclient.injector.port";
    private static final String HOST_PROPERTY = "utilityclient.injector.host";
    private static final String STEALTH_PROPERTY = "utilityclient.stealth";
    private static final String PAYLOAD_RESOURCE = "/bootstrap/hooks.jar";

    private ClientAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        run(args, inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        run(args, inst, "attach");
    }

    private static synchronized void run(String args, Instrumentation inst, String mode) {
        try {
            Map<String, String> options = AgentAttacher.decode(args);
            Relay.request(options.get("request"));

            // The unload operation reuses this agent entry point: the jar is already
            // in the target, so a second attach with op=unload reaches the live
            // client through Instrumentation and shuts it down in place. No new
            // mechanism, and it works for a mod install too, where nothing was
            // ever "injected" in the first place.
            if ("unload".equalsIgnoreCase(options.get("op"))) {
                String port = options.get("port");
                if (port != null && !port.isBlank()) {
                    Relay.connect(options.getOrDefault("host", "127.0.0.1"),
                            Integer.parseInt(port.trim()));
                }
                Relay.stage("LOADING", "Connected to target; unloading client");
                if (unloadClient(inst)) Relay.stage("UNLOADED", "Client unloaded");
                else Relay.stage("FAILED", "Unload failed; see target log");
                return;
            }

            String host = options.getOrDefault("host", "127.0.0.1");
            String port = options.get("port");
            if (port != null && !port.isBlank()) {
                // Read by the bootstrap payload, which cannot see this class.
                System.setProperty(PORT_PROPERTY, port);
                System.setProperty(HOST_PROPERTY, host);
                Relay.connect(host, Integer.parseInt(port.trim()));
            }

            Relay.log("agent", "loaded by " + mode + " on pid " + ProcessHandle.current().pid()
                    + " / java " + System.getProperty("java.version"));
            Relay.stage("LOADING", "Connected to target; loading client classes");
            Relay.log("agent", "retransform=" + inst.isRetransformClassesSupported()
                    + " redefine=" + inst.isRedefineClassesSupported());

            // 1. Stealth first: the client reads this while it initializes and picks
            //    its config location from it. Setting it later would be too late.
            boolean stealth = "true".equalsIgnoreCase(options.get("stealth"));
            if (stealth) {
                System.setProperty(STEALTH_PROPERTY, "true");
                Relay.log("agent", "stealth on: config goes to temp, init log suppressed");
            }

            // 2. The client mod itself, into the game's classloader.
            String modJar = options.get("mod");
            boolean modLoaded = false;
            if (modJar != null && !modJar.isBlank()) {
                Relay.log("agent", com.baggute.cheatclient.injector.ClientIntegrity.verify(java.nio.file.Path.of(modJar), options.get("clientSha256")).summary());
                // Anything loaded here is late by definition: the game is already
                // running, so the input registry no longer accepts key mappings.
                System.setProperty("utilityclient.lateInit", "true");
                boolean openGui = !"false".equalsIgnoreCase(options.get("openGui"));
                modLoaded = ModLoader.load(inst, modJar, openGui);
                if (!modLoaded) {
                    Relay.stage("FAILED", "Client initialization or hook compatibility check failed; "
                            + "no new game hooks installed. Restart Minecraft and use the matching client JAR; see target log.");
                    return;
                }

                // The client's mixins never reached the already-loaded render and
                // input classes, so put those hooks back by rewriting them.
                BridgePatcher.patch(inst);
                if (!BridgePatcher.ready(inst)) {
                    unloadClient(inst);
                    Relay.stage("FAILED", "Client disabled: required game hooks unavailable: " + BridgePatcher.missing(inst));
                    return;
                }
                RuntimePatcher.patch(inst);
            } else if ("true".equals(options.get("bridge"))
                    || "true".equals(System.getProperty("utilityclient.bridge"))) {
                // Testing path: run the bridge patcher with no client loaded, so the
                // bytecode edit can be exercised against a stub target.
                BridgePatcher.patch(inst);
                RuntimePatcher.patch(inst);
            }

            // 3. Extra jars on the system classpath, then an optional entry point.
            String extraJar = options.get("jar");
            if (extraJar != null && !extraJar.isBlank()) {
                appendJar(inst, extraJar);
            }
            String entry = options.get("run");
            if (entry != null && !entry.isBlank()) {
                invokeEntry(entry, ClassLoader.getSystemClassLoader());
            }

            // 4. Report what is in this JVM, then mark the bridge if the client is up.
            boolean clientLoaded = Boolean.TRUE.equals(scanForClient(inst, mode));

            // 5. Hooks last, so a bad hook cannot cost us the rest of the report.
            String hooks = options.get("hooks");
            if (hooks != null && !hooks.isBlank()) {
                installHooks(inst, hooks);
            }

            if (!modLoaded && !clientLoaded) {
                Relay.log("agent", "the client is not in this JVM: pass --mod <cheatclient.jar> "
                        + "to load it into the running game");
            }
            Relay.log("agent", "done");
            if (modJar == null || modJar.isBlank()) {
                Relay.stage("AGENT_LOADED", "Agent loaded; no client JAR selected");
            } else if (modLoaded) {
                Relay.stage("READY", "Client initialized in PID " + ProcessHandle.current().pid()
                        + "; tick, input, HUD, frame and world hooks verified");
            } else {
                Relay.stage("FAILED", "Client did not initialize; review target log and check Fabric API/game version");
            }
        } catch (Throwable failure) {
            Relay.stage("FAILED", "Target initialization failed: " + unwrap(failure));
            record("agent failure: " + failure);
        } finally {
            Relay.request(null);
        }
    }

    /**
     * Finds the live client class and calls its {@code unload} method.
     *
     * <p>Reflectively, through the game's own loader: this class was loaded from the
     * system classpath and cannot reference the client's types. A missing class is a
     * normal answer — the agent may be aimed at a JVM that never had the client.
     */
    private static boolean unloadClient(Instrumentation inst) {
        Class<?> client = null;
        try {
            for (Class<?> type : inst.getAllLoadedClasses()) {
                if (type.getName().equals("com.baggute.cheatclient.UtilityClient")) {
                    client = type;
                    break;
                }
            }
        } catch (Throwable failure) {
            Relay.log("agent", "could not enumerate classes: " + failure);
        }

        if (client == null) {
            Relay.log("unload", "the client is not loaded in this JVM; nothing to unload");
            return true;
        }

        try {
            Method unload = client.getMethod("unload");
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, client.getClassLoader());
            ModLoader.onClientThread(minecraft, () -> { unload.invoke(null); return null; });
            Relay.log("unload", "client unloaded; modules off, overlay closed, entry points idle");
            Relay.log("unload", "started through Fabric? the jar is still in mods/ — remove it "
                    + "there to stop it loading on the next launch");
            return true;
        } catch (Throwable failure) {
            Relay.log("unload", "unload failed: " + unwrap(failure));
            return false;
        }
    }

    private static void appendJar(Instrumentation inst, String path) {
        File file = new File(path);
        if (!file.isFile()) {
            Relay.log("agent", "extra jar not found: " + path);
            return;
        }
        try {
            // Deliberately not closed: the JVM keeps reading classes out of it.
            inst.appendToSystemClassLoaderSearch(new JarFile(file));
            Relay.log("agent", "appended to system classpath: " + file.getAbsolutePath());
            Relay.log("agent", "note: Fabric's Knot ignores system-path jars, so the game "
                    + "will not see these classes; --mod is the flag that works there");
        } catch (Throwable failure) {
            Relay.log("agent", "could not append " + path + ": " + failure);
        }
    }

    private static void invokeEntry(String spec, ClassLoader loader) {
        String className = spec;
        String methodName = null;
        int hash = spec.indexOf('#');
        if (hash > 0) {
            className = spec.substring(0, hash);
            methodName = spec.substring(hash + 1);
        }
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (methodName == null) {
                Method main = type.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[0]);
                Relay.log("agent", "ran " + className + "#main");
            } else {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(null);
                Relay.log("agent", "ran " + className + "#" + methodName);
            }
        } catch (Throwable failure) {
            Relay.log("agent", "entry " + spec + " failed: " + unwrap(failure));
        }
    }

    /** Looks for the game and the client; marks the client's bridge when both exist. */
    private static Boolean scanForClient(Instrumentation inst, String mode) {
        boolean minecraft = false;
        boolean fabric = false;
        Class<?> client = null;

        try {
            for (Class<?> type : inst.getAllLoadedClasses()) {
                String name = type.getName();
                if (!fabric && name.equals("net.fabricmc.loader.api.FabricLoader")) fabric = true;
                if (!minecraft && name.equals("net.minecraft.client.Minecraft")) minecraft = true;
                if (client == null && name.equals("com.baggute.cheatclient.UtilityClient")) client = type;
            }
        } catch (Throwable failure) {
            Relay.log("agent", "could not enumerate loaded classes: " + failure);
            return null;
        }

        Relay.log("agent", "minecraft=" + minecraft + " fabric=" + fabric + " client=" + (client != null));

        if (client == null) return false;

        try {
            // The bridge lives in the game's loader, not ours, so it is reached
            // reflectively rather than by reference.
            Class<?> bridge = Class.forName(
                    "com.baggute.cheatclient.injector.InjectorBridge", true, client.getClassLoader());
            bridge.getMethod("mark", String.class).invoke(null, mode);
            Relay.log("agent", "in-game GUI now reports \"injected\"");
        } catch (Throwable failure) {
            Relay.log("agent", "could not mark the bridge: " + unwrap(failure));
        }
        return true;
    }

    // ---- hooks ----

    private static synchronized void installHooks(Instrumentation inst, String specList) {
        if (!inst.isRetransformClassesSupported()) {
            Relay.log("agent", "this JVM does not allow retransformation; no hooks installed");
            return;
        }

        List<HookSpec> fresh = new ArrayList<>();
        for (String part : specList.split("\\|")) {
            if (part.isBlank()) continue;
            try {
                HookSpec spec = HookSpec.parse(part);
                if (!INSTALLED.contains(spec.key())
                        && fresh.stream().noneMatch(old -> old.key().equals(spec.key()))) fresh.add(spec);
            } catch (IllegalArgumentException malformed) {
                Relay.log("agent", "bad hook '" + part + "': " + malformed.getMessage());
            }
        }

        if (fresh.isEmpty()) {
            Relay.log("agent", "no new hooks to install");
            return;
        }

        if (!installPayload(inst)) {
            Relay.log("agent", "hook payload unavailable; refusing to instrument "
                    + "anything, because the call could not link at runtime");
            return;
        }

        HookTransformer transformer = new HookTransformer(fresh);
        inst.addTransformer(transformer, true);

        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (!transformer.classNames().contains(type.getName())) continue;
            if (!inst.isModifiableClass(type)) {
                Relay.log("agent", "class " + type.getName() + " is not modifiable");
                continue;
            }
            if (!HookTransformer.payloadVisible(type.getClassLoader())) {
                // Exactly the case that crashed the game: Knot loads the class but
                // cannot see a payload sitting on the system classpath.
                Relay.log("agent", "skipped " + type.getName()
                        + ": its loader cannot see the hook payload");
                continue;
            }
            loaded.add(type);
        }

        if (loaded.isEmpty()) {
            boolean allUnloaded = true;
            for (Class<?> type : inst.getAllLoadedClasses()) {
                if (transformer.classNames().contains(type.getName())) { allUnloaded = false; break; }
            }
            if (allUnloaded) {
                for (HookSpec spec : fresh) INSTALLED.add(spec.key());
                Relay.log("agent", "hooks armed for " + transformer.classNames());
            } else {
                inst.removeTransformer(transformer);
                Relay.log("agent", "no eligible loaded hook targets; installation can be retried");
            }
            return;
        }

        try {
            inst.retransformClasses(loaded.toArray(new Class<?>[0]));
            if (!transformer.installedKeys().containsAll(fresh.stream().map(HookSpec::key).toList())) {
                inst.removeTransformer(transformer);
                inst.retransformClasses(loaded.toArray(new Class<?>[0]));
                Relay.log("agent", "not all hook methods matched; installation rolled back for retry");
                return;
            }
            for (HookSpec spec : fresh) INSTALLED.add(spec.key());
            Relay.log("agent", "hooks active on " + loaded.size() + " class(es)");
        } catch (Throwable failure) {
            inst.removeTransformer(transformer);
            try { inst.retransformClasses(loaded.toArray(new Class<?>[0])); } catch (Throwable ignored) {}
            Relay.log("agent", "retransform failed: " + unwrap(failure));
        }
    }

    /**
     * Puts the hook payload in the bootstrap loader.
     *
     * <p>It cannot come from this jar directly. {@code loadAgent} adds injector.jar
     * to the system classpath, and Fabric's Knot refuses to expose classes it finds
     * in a system-path jar that was never declared to it — the failure reads
     * "hasn't been exposed to the game". The payload is therefore a nested jar with
     * no top-level copy anywhere, appended to bootstrap only, where every loader
     * including Knot can reach it.
     */
    private static boolean installPayload(Instrumentation inst) {
        InputStream in = ClientAgent.class.getResourceAsStream(PAYLOAD_RESOURCE);
        if (in == null) {
            Relay.log("agent", "no " + PAYLOAD_RESOURCE + " in the agent jar");
            return false;
        }

        File temp = null;
        try (InputStream source = in) {
            temp = File.createTempFile("utilityclient-hooks-", ".jar");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                source.transferTo(out);
            }
            // Not closed on purpose: classes may be read from it lazily, for as long
            // as the game runs. The temp file is cleaned up by the OS.
            JarFile jar = new JarFile(temp);
            inst.appendToBootstrapClassLoaderSearch(jar);
            Relay.log("agent", "hook payload on the bootstrap loader: " + temp.getAbsolutePath());
            return true;
        } catch (Throwable failure) {
            Relay.log("agent", "could not install the hook payload: " + failure);
            if (temp != null) temp.delete();
            return false;
        }
    }

    private static String unwrap(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** Mirrors every line into a temp file, for targets with no log channel. */
    private static void record(String message) {
        Relay.log("agent", message);
        try {
            File log = new File(System.getProperty("java.io.tmpdir"), "utilityclient-injector.log");
            try (FileWriter writer = new FileWriter(log, true)) {
                writer.write("[attach] " + message + System.lineSeparator());
            }
        } catch (Throwable ignored) {
            // Logging must never break the injection.
        }
    }
}
