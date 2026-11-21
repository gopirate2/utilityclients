package com.baggute.cheatclient.injector;

import java.nio.file.*;
import java.util.List;

public final class LaunchOptionsChecks {
    public static void run() throws Exception {
        Path root = Files.createTempDirectory("uc-launch-options-");
        try {
            LaunchSettings value = LaunchSettings.load(root);
            require(!value.autoInject && !value.startMinimized && !value.allowMultiple, "automatic and startup behaviors are opt-in");
            Files.writeString(root.resolve(LaunchSettings.FILE_NAME), "{\"futureOption\":\"keep\"}");
            value.autoInject = true; value.startMinimized = true; value.gameDirectory = root.resolve("snow 雪 % folder").toString();
            value.warmupSeconds = 5; value.scanIntervalMs = 750; value.save(root);
            LaunchSettings saved = LaunchSettings.load(root);
            require(saved.autoInject && saved.startMinimized && saved.gameDirectory.equals(value.gameDirectory)
                    && saved.warmupSeconds == 5 && saved.scanIntervalMs == 750, "shared JSON roundtrip keeps Unicode and values");
            require(Files.readString(root.resolve(LaunchSettings.FILE_NAME)).contains("futureOption"), "saving preserves future fields");
            for (String invalid : List.of("{\"autoInject\":\"true\"}", "{\"scanIntervalMs\":1}", "{\"warmupSeconds\":0.5}", "{\"schema\":2}", "[]")) {
                Files.writeString(root.resolve(LaunchSettings.FILE_NAME), invalid);
                try { LaunchSettings.load(root); throw new AssertionError("invalid launch settings accepted"); } catch (java.io.IOException expected) { }
            }
            String oldRoot = System.getProperty("utilityclient.injectorRoot");
            try { System.setProperty("utilityclient.injectorRoot", root.toString()); require(InstallPaths.root().equals(root.toAbsolutePath().normalize()), "launcher root is honored"); }
            finally { if (oldRoot == null) System.clearProperty("utilityclient.injectorRoot"); else System.setProperty("utilityclient.injectorRoot", oldRoot); }
            for (JvmScanner.Target target : List.of(new JvmScanner.Target("1", "minecraft-helper.jar", "java -jar minecraft-helper.jar"),
                    new JvmScanner.Target("2", "Gradle", "org.gradle.launcher.daemon /cache/minecraft-client.jar"),
                    new JvmScanner.Target("3", "server", "net.fabricmc.loader.impl.launch.knot.KnotServer")))
                require(!target.isAutoInjectable(), "automatic matcher rejects wrappers, helpers and servers");
            JvmScanner.Target first = new JvmScanner.Target("10", "Minecraft", "net.fabricmc.loader.impl.launch.knot.KnotClient", value.gameDirectory);
            JvmScanner.Target second = new JvmScanner.Target("11", "Minecraft", "net.minecraft.client.main.Main", value.gameDirectory);
            require(first.isAutoInjectable() && second.isAutoInjectable(), "explicit client entrypoints accepted");
            AutoInjectController.Candidate one = new AutoInjectController.Candidate(first, new AutoInjectController.Identity("10", 1000));
            AutoInjectController.Candidate two = new AutoInjectController.Candidate(second, new AutoInjectController.Identity("11", 2000));
            AutoInjectController controller = new AutoInjectController();
            value.autoInject = false; require(!controller.poll(List.of(one), value, 20000).ready(), "off switch does not schedule");
            value.autoInject = true;
            require(!controller.poll(List.of(one), value, 5999).ready() && controller.poll(List.of(one), value, 6000).ready(), "configured startup deadline respected");
            require(!controller.poll(List.of(one, two), value, 20000).ready(), "ambiguous games pause by default");
            value.allowMultiple = true; require(controller.poll(List.of(one, two), value, 20000).candidate().identity().equals(two.identity()), "explicit newest policy chooses newest process");
            value.allowMultiple = false; controller.started(one); controller.completed(one, false);
            require(!controller.poll(List.of(one), value, 20000).ready(), "failed attachment is not repeatedly retried");
            AutoInjectController.Candidate reused = new AutoInjectController.Candidate(first, new AutoInjectController.Identity("10", 12000));
            require(controller.poll(List.of(reused), value, 20000).ready(), "PID reuse gets a new startup identity");
            value.reinjectOnRestart = false; controller.completed(reused, true);
            require(!controller.poll(List.of(two), value, 20000).ready(), "one-session mode stops after confirmed success");
            controller.reset(); require(controller.poll(List.of(two), value, 20000).ready(), "explicit reset arms another attempt");
            value.gameDirectory = root.resolve("other").toString(); require(!controller.poll(List.of(two), value, 20000).ready(), "game-folder filter excludes another instance");
            System.out.println("PASS: shared launch JSON/defaults/validation, Unicode/root handling, exact Minecraft matcher, warmup, ambiguity, newest selection, failure bounds, PID reuse and explicit reset");
        } finally {
            try (var files = Files.walk(root)) { for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
