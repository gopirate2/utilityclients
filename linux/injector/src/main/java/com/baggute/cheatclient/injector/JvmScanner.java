package com.baggute.cheatclient.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reading side of the attach API.
 *
 * <p>Everything here is reflective: {@code jdk.attach} is not present in every
 * runtime, and a NoClassDefFoundError at class-init time would take the whole UI
 * down with it. A missing module is reported as a normal exception instead.
 */
public final class JvmScanner {

    /**
     * Product names only. A generic "launcher" matched Gradle daemons
     * ({@code org.gradle.launcher.daemon}) and labelled them as game launchers.
     */
    private static final String[] LAUNCHER_HINTS = {
            "prismlauncher", "multimc", "gdlauncher", "curseforge",
            "modrinth", "atlauncher", "bootstraplauncher"
    };

    /** Arguments and class names only the game process has. */
    private static final String[] GAME_MARKERS = {
            "knotclient", "net.minecraft.client.main", "--gamedir", "fabric.gamejarpath",
            // Prism runs the game under its own EntryPoint class, so the launcher's
            // name is on a process that is really the game. Its classpath is not.
            "fabric-loader", "minecraft-", "/minecraft/client/", "net/fabricmc"
    };

    private static final String[] MINECRAFT_HINTS = {
            "knotclient", "devlaunchinjector", "net.minecraft.client.main.main",
            "net.fabricmc", "minecraft"
    };

    private JvmScanner() {
    }

    /**
     * One running Java process as the attach API describes it.
     *
     * <p>{@code commandLine} is what actually decides the kind. A game launched by
     * Prism carries the launcher's install path all over its classpath, so the
     * display name alone cannot tell the game from the launcher.
     *
     * <p>{@code gameDirectory} is resolved once, at construction, because the
     * table asks for it on every repaint and tokenising a command line per cell
     * would be absurd. Empty when the process was started without one - a Gradle
     * daemon has no {@code --gameDir}, and neither does a launcher wrapper.
     */
    public record Target(String pid, String description, String commandLine, String gameDirectory) {

        public Target(String pid, String description, String commandLine) {
            this(pid, description, commandLine, resolveGameDirectory(commandLine));
        }

        public Target(String pid, String description) {
            this(pid, description, "");
        }

        /**
         * True when the process looks like a Minecraft client. Launch wrappers are
         * excluded first because Prism/CurseForge start their own JVMs whose command
         * line mentions the game without being it.
         */
        public boolean isMinecraft() {
            if (gameMarkers().length() > 0) return true;
            if (isLauncher()) return false;
            String text = description.toLowerCase(Locale.ROOT);
            for (String hint : MINECRAFT_HINTS) {
                if (text.contains(hint)) return true;
            }
            return false;
        }

        /**
         * Tokens only a running game carries: Fabric's launch class, the vanilla main
         * class, or the arguments every launcher passes to the game. The launcher
         * process has none of these — it spawns a child that does.
         */
        private String gameMarkers() {
            String text = commandLine.toLowerCase(Locale.ROOT);
            StringBuilder found = new StringBuilder();
            for (String marker : GAME_MARKERS) {
                if (text.contains(marker)) found.append(marker).append(' ');
            }
            return found.toString();
        }

        /**
         * True for a launcher's own JVM (Prism, CurseForge, MultiMC). Those carry
         * the word "minecraft" on their command line without being the game, and
         * attaching to one is always a mistake — it cannot load the client.
         */
        public boolean isLauncher() {
            if (gameMarkers().length() > 0) return false;
            String text = (description + " " + commandLine).toLowerCase(Locale.ROOT);
            if (text.contains("net.minecraft.client.main")) return false;
            for (String launcher : LAUNCHER_HINTS) {
                if (text.contains(launcher)) return true;
            }
            return false;
        }

        public String kind() {
            if (isMinecraft()) return "minecraft";
            return isLauncher() ? "launcher" : "java";
        }

        /** Automatic attachment requires a client launch marker, not a filename containing Minecraft. */
        public boolean isAutoInjectable() {
            String text = (description + " " + commandLine).toLowerCase(Locale.ROOT);
            if (text.contains("knotserver") || text.contains("net.minecraft.server") || text.contains("fabric.side=server")) return false;
            for (String token : tokens(text)) {
                if (token.equals("net.minecraft.client.main.main") || token.equals("net.fabricmc.loader.impl.launch.knot.knotclient")) return true;
            }
            return !gameDirectory.isBlank() && (text.contains("knotclient") || text.contains("org.prismlauncher.entrypoint")
                    || text.contains("net.fabricmc.devlaunchinjector.main") && text.contains("fabric.dli.env=client"));
        }

        public String shortDescription() {
            String text = description.trim();
            int cut = text.indexOf(' ');
            return cut > 40 ? text.substring(0, 40) + "…" : text;
        }

        /**
         * The directory the game was told to run in.
         *
         * <p>{@code -Duser.dir=} wins because it is what the JVM actually changes
         * into, so {@code .minecraft/saves} and the logs land there even when the
         * launcher rewrites its arguments. {@code --gameDir} is the launcher's own
         * flag and covers every process that leaves the property alone.
         *
         * <p>{@code ProcessHandle.Info} has no working-directory accessor on
         * Windows, so the command line is the only source; a process that carries
         * neither token reports an empty directory rather than a guess.
         */
        private static String resolveGameDirectory(String commandLine) {
            if (commandLine == null || commandLine.isBlank()) return "";

            List<String> parts = tokens(commandLine);
            String gameDir = "";
            String inferred = "";
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                if (part.startsWith("-Duser.dir=")) {
                    String value = part.substring("-Duser.dir=".length()).trim();
                    if (!value.isEmpty()) return tidy(value);
                }
                if (part.equals("--gameDir") && i + 1 < parts.size()) {
                    String value = parts.get(i + 1).trim();
                    if (!value.isEmpty()) gameDir = tidy(value);
                } else if (part.startsWith("--gameDir=")) {
                    String value = part.substring("--gameDir=".length()).trim();
                    if (!value.isEmpty()) gameDir = tidy(value);
                } else if (part.startsWith("-Djava.library.path=")) {
                    // Prism passes <instance>/minecraft/natives (older builds pass
                    // <instance>/natives) and runs the game from the minecraft folder,
                    // so the library directory is one level below the game.
                    String value = tidy(part.substring("-Djava.library.path=".length()));
                    if (value.endsWith("/natives") || value.endsWith("\\natives")) {
                        String parent = tidy(value.substring(0, value.length() - "natives".length()));
                        if (!parent.isEmpty()) inferred = gameRoot(parent);
                    }
                }
            }
            return gameDir.isEmpty() ? inferred : gameDir;
        }

        /**
         * The directory the game runs in, from the launcher's library path.
         *
         * <p>Prism keeps the game inside a {@code minecraft} folder under the
         * instance and its natives one level deeper, while older builds pointed at
         * {@code <instance>/natives}. The subfolder wins only when it exists, so a
         * layout this code has not seen still reports a real directory.
         */
        private static String gameRoot(String parent) {
            try {
                java.nio.file.Path nested = java.nio.file.Path.of(parent, "minecraft");
                if (java.nio.file.Files.isDirectory(nested)) return nested.toString();
            } catch (RuntimeException ignored) {
                // An unparseable path is not a directory; the parent is still useful.
            }
            return parent;
        }

        /** Drops the trailing separator and any stray quote a launcher left behind. */
        private static String tidy(String value) {            String text = value.replace("\"", "").trim();
            while (text.length() > 2 && (text.endsWith("\\") || text.endsWith("/"))) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }

        /**
         * Splits a command line the way the OS does, honouring double quotes.
         *
         * <p>A Windows path with spaces arrives quoted; splitting on whitespace
         * would cut {@code C:\Users\Bob Smith\.minecraft} in half and the column
         * would show a directory that does not exist.
         */
        private static List<String> tokens(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                    continue;
                }
                if (!quoted && Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) out.add(current.toString());
            return out;
        }
    }

    /** Every Java process this user can see, minus the injector itself. */
    public static List<Target> list() {
        String self = Long.toString(ProcessHandle.current().pid());
        List<Target> out = new ArrayList<>();

        for (Object descriptor : descriptors()) {
            try {
                String id = String.valueOf(method(descriptor, "id").invoke(descriptor));
                if (self.equals(id)) continue;
                String display = String.valueOf(method(descriptor, "displayName").invoke(descriptor));
                out.add(new Target(id, display, commandLine(id)));
            } catch (ReflectiveOperationException ignored) {
                // A process that vanished mid-enumeration is not interesting.
            }
        }
        return enrich(out);
    }

    /**
     * Fills in command lines the JVM could not read.
     *
     * <p>On Windows {@code ProcessHandle.Info.commandLine()} is empty for almost
     * every process, which left the classifier guessing from a display name. One
     * PowerShell query answers for all of them at once.
     */
    private static List<Target> enrich(List<Target> targets) {
        Map<String, String> commands = windowsCommandLines(targets);
        if (commands.isEmpty()) return targets;

        List<Target> out = new ArrayList<>(targets.size());
        for (Target target : targets) {
            String command = !target.commandLine().isBlank()
                    ? target.commandLine()
                    : commands.getOrDefault(target.pid(), "");
            out.add(new Target(target.pid(), target.description(), command));
        }
        return out;
    }

    private static Map<String, String> windowsCommandLines(List<Target> targets) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return Map.of();

        List<String> pids = new ArrayList<>();
        for (Target target : targets) {
            if (!target.pid().isBlank() && target.pid().chars().allMatch(Character::isDigit)) {
                pids.add(target.pid());
            }
        }
        if (pids.isEmpty()) return Map.of();

        List<String> command = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -in @("
                        + String.join(",", pids) + ") } | Select-Object ProcessId,CommandLine "
                        + "| ConvertTo-Json -Compress");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = ProcessOutput.read(process, 5000);
            return parseCommandLines(output);
        } catch (Exception failure) {
            if (process != null) process.destroyForcibly();
            return Map.of();
        }
    }

    private static Map<String, String> parseCommandLines(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            JsonArray rows = new JsonArray();
            if (parsed.isJsonArray()) rows = parsed.getAsJsonArray();
            else if (parsed.isJsonObject()) rows.add(parsed);

            for (JsonElement element : rows) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                if (!row.has("ProcessId") || !row.has("CommandLine")) continue;
                if (row.get("CommandLine").isJsonNull()) continue;
                out.put(row.get("ProcessId").getAsString(), row.get("CommandLine").getAsString());
            }
        } catch (RuntimeException ignored) {
            // No PowerShell, or a JSON shape we do not know: classification falls
            // back to the display name, which is what it did before this existed.
        }
        return out;
    }

    /** Full command line, empty when the OS refuses to hand it over. */
    private static String commandLine(String pid) {
        try {
            return ProcessHandle.of(Long.parseLong(pid))
                    .flatMap(handle -> handle.info().commandLine())
                    .orElse("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    /** Reads a few system properties out of a live target. */
    public static Map<String, String> probe(String pid, Collection<String> keys) {
        Map<String, String> wanted = new LinkedHashMap<>();
        Object vm = null;
        try {
            Class<?> type = virtualMachineClass();
            vm = type.getMethod("attach", String.class).invoke(null, pid);
            @SuppressWarnings("unchecked")
            Map<String, String> all = (Map<String, String>) type.getMethod("getSystemProperties")
                    .invoke(vm);
            for (String key : keys) {
                String value = all.get(key);
                if (value != null) wanted.put(key, value);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(describe(failure), failure);
        } finally {
            detach(vm);
        }
        return wanted;
    }

    /** Flag that matters for injection: the target must allow retransformation. */
    public static String probeJdk(String pid) {
        Map<String, String> properties = probe(pid, List.of("java.version", "java.vendor"));
        return properties.getOrDefault("java.version", "?");
    }

    // ---- attach plumbing ----

    static Class<?> virtualMachineClass() throws ClassNotFoundException {
        return Class.forName("com.sun.tools.attach.VirtualMachine");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<?> descriptors() {
        try {
            Class<?> type = virtualMachineClass();
            return (List) type.getMethod("list").invoke(null);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                    "This runtime has no attach API (jdk.attach). Run the injector with a full JDK 21+.",
                    missing);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(describe(failure), failure);
        }
    }

    private static Method method(Object instance, String name) throws NoSuchMethodException {
        return instance.getClass().getMethod(name);
    }

    static void detach(Object vm) {
        if (vm == null) return;
        try {
            virtualMachineClass().getMethod("detach").invoke(vm);
        } catch (ReflectiveOperationException ignored) {
            // A stale attach socket is harmless; the JVM drops it on exit.
        }
    }

    /** Attach failures are wrapped several layers deep; surface the cause. */
    static String describe(Throwable failure) {
        Throwable current = failure;
        String best = String.valueOf(failure);
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) best = message;
            current = current.getCause();
        }
        return best;
    }
}
