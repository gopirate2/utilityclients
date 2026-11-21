package com.baggute.cheatclient.injector;

import com.baggute.cheatclient.injector.agent.HookSpec;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point of {@code injector.jar}.
 *
 * <p>Double-clicking the jar launches {@link InjectorFrame} — no console, no
 * arguments needed. The command line exists for scripting and for machines with
 * no display, and both paths do exactly the same work.
 */
public final class InjectorMain {

    private InjectorMain() {
    }

    public static void main(String[] args) {
        Options options;
        try { options = Options.parse(args); }
        catch (RuntimeException invalid) {
            System.out.println("[!] " + invalid.getMessage());
            System.out.println("Recovery:\n" + FailureRecovery.forMessage(invalid.getMessage()).text());
            System.exit(1); return;
        }

        if (options.help) {
            usage();
            return;
        }

        boolean headless = options.headless || GraphicsEnvironment.isHeadless();
        if ((options.mode == Mode.GUI || options.mode == Mode.WATCH) && !headless) {
            InjectorFrame.launch(options.startup, options.minimized, options.watch);
            return;
        }

        try {
            runCli(options);
        } catch (Exception failure) {
            System.out.println("[!] " + JvmScanner.describe(failure));
            System.out.println("Recovery:\n" + FailureRecovery.forMessage(JvmScanner.describe(failure)).text());
            System.exit(1);
        }
    }

    // ---- command line ----

    private static void runCli(Options options) throws Exception {
        switch (options.mode) {
            case LIST -> printTargets(JvmScanner.list());
            case JVM_ARGS -> {
                Path jar = AgentAttacher.selfJar();
                System.out.println(jar == null
                        ? "[!] run the packaged jar, not a classes directory"
                        : "-javaagent:" + jar.toAbsolutePath());
            }
            case INJECT -> inject(options);
            case VERIFY -> System.out.println(ClientIntegrity.verify(Path.of(options.clientJar), options.expectedSha256).summary());
            case UNLOAD -> unload(options);
            case WATCH -> watch(options);
            case GUI -> {
                if (options.watch) watch(options);
                else throw new IllegalStateException("no display available; pass --inject <pid> or --watch instead of --gui");
            }
        }
    }

    private static void watch(Options options) throws Exception {
        LaunchSettings settings = LaunchSettings.load(InstallPaths.root());
        settings.autoInject = true;
        AutoInjectController controller = new AutoInjectController();
        String last = "";
        while (!Thread.currentThread().isInterrupted()) {
            List<AutoInjectController.Candidate> targets = JvmScanner.list().stream().map(AutoInjectController.Candidate::observe).filter(java.util.Objects::nonNull).toList();
            var decision = controller.poll(targets, settings, System.currentTimeMillis());
            if (!decision.message().equals(last)) { System.out.println(decision.message()); last = decision.message(); }
            if (decision.ready()) {
                var candidate = decision.candidate(); controller.started(candidate);
                boolean success = false;
                try {
                    if (!candidate.stillRunning()) throw new IOException("Selected game exited or restarted before attachment");
                    Options selected = Options.parse(new String[] {"--inject", candidate.target().pid(), "--headless"});
                    selected.clientJar = options.clientJar == null ? InstallPaths.root().resolve("cheatclient.jar").toString() : options.clientJar;
                    selected.expectedSha256 = options.expectedSha256; selected.stealth = options.stealth; selected.noGui = options.noGui;
                    selected.waitSeconds = 1; inject(selected); success = true;
                } catch (Exception failure) {
                    System.out.println("Auto Inject stopped for this process: " + JvmScanner.describe(failure));
                    System.out.println(FailureRecovery.forMessage(JvmScanner.describe(failure)).text());
                } finally { controller.completed(candidate, success); }
                if (success && !settings.reinjectOnRestart) return;
            }
            Thread.sleep(settings.scanIntervalMs);
        }
    }

    private static void printTargets(List<JvmScanner.Target> targets) {
        if (targets.isEmpty()) {
            System.out.println("no other Java processes found");
            return;
        }
        System.out.printf("%-8s %-10s %s%n", "PID", "KIND", "PROCESS");
        for (JvmScanner.Target target : targets) {
            System.out.printf("%-8s %-10s %s%n", target.pid(), target.kind(), target.description());
        }
    }

    private static void inject(Options options) throws Exception {
        if (options.pid == null) {
            throw new IOException("--inject needs a pid (see --list)");
        }
        Path jar = AgentAttacher.selfJar();
        if (jar == null) {
            throw new IOException("run the packaged injector.jar, not a classes directory");
        }

        Map<String, String> agentOptions = new LinkedHashMap<>();
        OperationProgress.Session session = new OperationProgress.Session();
        try (LogServer server = LogServer.start(line -> printProgress(session, line))) {
            agentOptions.put("request", session.id);
            agentOptions.put("port", Integer.toString(server.port()));
            agentOptions.put("host", LogServer.HOST);
            agentOptions.put("mod", options.clientJar);
            agentOptions.put("clientSha256", options.expectedSha256);
            agentOptions.put("stealth", Boolean.toString(options.stealth));
            agentOptions.put("openGui", Boolean.toString(!options.noGui));
            agentOptions.put("jar", options.extraJar);
            agentOptions.put("run", options.runClass);
            agentOptions.put("hooks", String.join("|", options.hooks));
            if (options.bridge) agentOptions.put("bridge", "true");

            System.out.println("[1/5] Checking client and process " + options.pid);
            System.out.println("[2/5] Connecting to target (log port " + server.port() + ")");
            AgentAttacher.inject(options.pid, jar, agentOptions);
            confirm(session);

            if (options.waitSeconds > 0) {
                Thread.sleep(options.waitSeconds * 1000L);
            } else {
                System.out.println("[*] streaming; Ctrl-C to stop");
                Thread.currentThread().join();
            }
        }
    }

    /**
     * Attaches the same agent jar with {@code op=unload}.
     *
     * <p>Works for every way the client can be present: an agent session, or a jar
     * merged into a host mod — in that case the classes are in the game's loader
     * and the agent finds them the same way. Removing the jar from {@code mods/} is
     * still the only way to stop it loading next launch.
     */
    private static void unload(Options options) throws Exception {
        if (options.pid == null) {
            throw new IOException("--unload needs a pid (see --list)");
        }
        Path jar = AgentAttacher.selfJar();
        if (jar == null) {
            throw new IOException("run the packaged injector.jar, not a classes directory");
        }

        Map<String, String> agentOptions = new LinkedHashMap<>();
        agentOptions.put("op", "unload");
        OperationProgress.Session session = new OperationProgress.Session();
        try (LogServer server = LogServer.start(line -> printProgress(session, line))) {
            agentOptions.put("request", session.id);
            agentOptions.put("port", Integer.toString(server.port()));
            agentOptions.put("host", LogServer.HOST);
            System.out.println("[*] unloading the client in pid " + options.pid);
            AgentAttacher.inject(options.pid, jar, agentOptions);
            confirm(session);
            Thread.sleep(options.waitSeconds > 0 ? options.waitSeconds * 1000L : 1500L);
        }
    }

    private static void printProgress(OperationProgress.Session session, String line) {
        OperationProgress.Event event = session.accept(line);
        if (event != null) System.out.println((event.ok() ? "[" + event.step() + "/5] " : "[!] ") + event.message());
        else if (!line.startsWith("UC_STAGE ")) System.out.println(line);
    }

    private static void confirm(OperationProgress.Session session) throws IOException, InterruptedException {
        OperationProgress.Event event = session.await(10_000);
        if (event == null) throw new IOException("Attach returned but target confirmation timed out; inspect logs before retrying");
        if (!event.ok()) throw new IOException(event.message());
    }

    private static void usage() {
        System.out.println("""
                UtilityClient injector

                  double-click, or: java -jar injector.jar

                usage:
                  --list                       list running Java processes
                  --verify <client.jar>        verify identity, archive and runtime helpers without attaching
                  --sha256 <digest>            require the expected client SHA-256 for verify/inject
                  --inject <pid>               attach the agent to a process
                  --unload <pid>               disable the client in a running game
                                               (agent session or mod install)
                  --mod <path>                 load a Fabric client jar into the game
                                               (no mod-list entry, no discovery)
                  --stealth / --visible        hide the session (default: stealth)
                  --no-gui-open                do not open the click GUI after init
                  --hook <a.b.C#method>        instrument a method (repeatable)
                  --jar <path>                 append a jar to the target's classpath
                  --run <a.b.C[#method]>       run an entry point from that jar
                  --wait <seconds>             how long to stream output (0 = until Ctrl-C)
                  --gui                        force the window
                  --watch                      watch matching Minecraft clients (use --headless for console)
                  --startup                    start with saved launch options
                  --minimized                  open the injector minimized
                  --headless                   force the console
                  --jvm-args                   print -javaagent: for a launcher
                  --help                       this text

                examples:
                  java -jar injector.jar --list
                  java -jar injector.jar --inject 12345 --mod build/libs/cheatclient-1.0.0.jar
                  java -jar injector.jar --inject 12345 --hook com.baggute.cheatclient.module.Module#onTick
                """);
    }

    // ---- option parsing ----

    private enum Mode { GUI, LIST, INJECT, UNLOAD, JVM_ARGS, VERIFY, WATCH }

    private static final class Options {

        private Mode mode = Mode.GUI;
        private String pid;
        private String clientJar;
        private String expectedSha256;
        private String extraJar;
        private String runClass;
        private final List<String> hooks = new ArrayList<>();
        private int waitSeconds;
        private boolean headless;
        private boolean help;
        private boolean stealth = true;
        private boolean noGui;
        private boolean bridge;
        private boolean startup, minimized, watch;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--list" -> options.mode = Mode.LIST;
                    case "--verify" -> { options.mode = Mode.VERIFY; options.clientJar = next(args, ++i, "--verify"); }
                    case "--sha256" -> options.expectedSha256 = next(args, ++i, "--sha256");
                    case "--gui" -> options.mode = Mode.GUI;
                    case "--watch" -> { options.mode = Mode.WATCH; options.watch = true; }
                    case "--startup" -> options.startup = true;
                    case "--minimized" -> options.minimized = true;
                    case "--headless" -> options.headless = true;
                    case "--help", "-h" -> options.help = true;
                    case "--jvm-args" -> options.mode = Mode.JVM_ARGS;
                    case "--inject" -> {
                        options.mode = Mode.INJECT;
                        options.pid = next(args, ++i, "--inject");
                    }
                    case "--unload" -> {
                        options.mode = Mode.UNLOAD;
                        options.pid = next(args, ++i, "--unload");
                    }
                    case "--hook" -> {
                        String spec = next(args, ++i, "--hook");
                        try {
                            HookSpec.parse(spec);
                            options.hooks.add(spec);
                        } catch (IllegalArgumentException malformed) {
                            throw new IllegalArgumentException("bad --hook " + spec + ": "
                                    + malformed.getMessage());
                        }
                    }
                    case "--mod" -> options.clientJar = next(args, ++i, "--mod");
                    case "--bridge" -> options.bridge = true;
                    case "--jar" -> options.extraJar = next(args, ++i, "--jar");
                    case "--run" -> options.runClass = next(args, ++i, "--run");
                    case "--stealth" -> options.stealth = true;
                    case "--visible" -> options.stealth = false;
                    case "--no-gui-open" -> options.noGui = true;
                    case "--wait" -> options.waitSeconds = Integer.parseInt(next(args, ++i, "--wait"));
                    default -> throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            return options;
        }

        private static String next(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return args[index];
        }
    }
}
