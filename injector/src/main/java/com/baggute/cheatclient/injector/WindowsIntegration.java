package com.baggute.cheatclient.injector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Optional controls delegate shell-link creation to the installed Unicode Windows launcher. */
public final class WindowsIntegration {
    private WindowsIntegration() {}
    public static boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && Files.isRegularFile(InstallPaths.root().resolve("UtilityClient.exe"));
    }
    public static String apply(String operation) throws IOException, InterruptedException {
        if (!available()) throw new IOException("Install the Windows launcher with the bootstrapper to use these controls");
        String[] parts = operation.split(":", 2);
        if (parts.length != 2 || !java.util.Set.of("create", "remove").contains(parts[0])
                || !java.util.Set.of("desktop", "start-menu", "startup").contains(parts[1])) throw new IOException("Unknown shortcut action");
        Path root = InstallPaths.root();
        Process process = new ProcessBuilder(root.resolve("UtilityClient.exe").toString(), "--root", root.toString(),
                parts[0].equals("create") ? "--create-shortcut" : "--remove-shortcut", parts[1])
                .redirectErrorStream(true).start();
        String output = ProcessOutput.read(process, 20_000).strip();
        if (process.exitValue() != 0) throw new IOException(output.isBlank() ? "Windows shortcut action failed" : output);
        return output;
    }
}
