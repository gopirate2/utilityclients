package com.baggute.cheatclient.injector;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writing side of the attach API: loads this jar into a chosen process. */
public final class AgentAttacher {

    private AgentAttacher() {
    }

    /** The jar that is currently running, or null when started from a classes dir. */
    public static Path selfJar() {
        try {
            Path path = Paths.get(AgentAttacher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Loads {@code agentJar} into {@code pid}. The option string is what
     * {@code agentmain(String, Instrumentation)} receives on the other side.
     */
    public static void inject(String pid, Path agentJar, Map<String, String> options) throws IOException {
        if (!Files.isRegularFile(agentJar)) {
            throw new IOException("agent jar not found: " + agentJar);
        }

        Map<String, String> verifiedOptions = new LinkedHashMap<>(options);
        String client = options.get("mod");
        if (client != null && !client.isBlank() && !"unload".equals(options.get("op"))) {
            ClientIntegrity.Report report = ClientIntegrity.verify(Path.of(client), options.get("clientSha256"));
            verifiedOptions.put("clientSha256", report.sha256());
        }

        Object vm = null;
        try {
            Class<?> type = JvmScanner.virtualMachineClass();
            vm = type.getMethod("attach", String.class).invoke(null, pid);
            type.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, agentJar.toAbsolutePath().toString(), encode(verifiedOptions));
        } catch (ClassNotFoundException missing) {
            throw new IOException("This runtime has no attach API (jdk.attach). Use a full JDK.", missing);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IOException(explain(JvmScanner.describe(cause)), cause);
        } finally {
            JvmScanner.detach(vm);
        }
    }

    /**
     * Turns the attach API's terse refusals into something actionable.
     *
     * <p>"Access is denied" on Windows is a privilege mismatch: an elevated game
     * cannot be attached to from a normal process, and the reverse is also blocked.
     * Nothing in the message says which side to fix, so this does.
     */
    private static String explain(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("access is denied") || lower.contains("access denied")) {
            return message + " — the target is running elevated, or with attach disabled. "
                    + "Run the game and the injector at the same privilege level, and do not "
                    + "launch the game with -XX:+DisableAttachMechanism";
        }
        if (lower.contains("no such process") || lower.contains("process not found")) {
            return message + " — that process exited; press refresh and pick it again";
        }
        if (lower.contains("disableattachmechanism") || lower.contains("attach is not supported")) {
            return message + " — the target JVM was started with -XX:+DisableAttachMechanism";
        }
        return message;
    }

    /** URL-safe Base64 values preserve separators and Unicode in valid paths. */
    static String encode(Map<String, String> options) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            if (text.length() > 0) text.append(';');
            text.append(entry.getKey()).append('=').append(java.util.Base64.getUrlEncoder()
                    .withoutPadding().encodeToString(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return "b64:" + text;
    }

    /** Public because the agent half lives in a subpackage and parses the same format. */
    public static Map<String, String> decode(String options) {
        Map<String, String> out = new LinkedHashMap<>();
        if (options == null || options.isBlank()) return out;
        boolean encoded = options.startsWith("b64:");
        if (encoded) options = options.substring(4);
        for (String pair : options.split(";")) {
            if (pair.isBlank()) continue;
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            String value = pair.substring(split + 1).trim();
            if (encoded) value = new String(java.util.Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
            out.put(pair.substring(0, split).trim(), value);
        }
        return out;
    }
}
