package com.baggute.cheatclient.injector.agent;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Outbound half of the log channel, running inside the injected process.
 *
 * <p>Everything is best-effort: a dead injector UI must never break the target
 * game, so a failed write flips the relay off instead of throwing.
 */
final class Relay {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile PrintWriter out;
    private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();

    private Relay() {
    }

    static void request(String id) {
        if (id == null) REQUEST.remove(); else REQUEST.set(id);
    }

    static void stage(String stage, String message) {
        String id = REQUEST.get();
        PrintWriter writer = out;
        if (id == null || writer == null) return;
        writer.println("UC_STAGE " + id + " " + stage + " "
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        message.getBytes(StandardCharsets.UTF_8)));
    }

    static void connect(String host, int port) {
        close();
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 2000);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            out = null;
            System.out.println("[injector] no log channel to " + host + ":" + port + " (" + failure + ")");
        }
    }

    static boolean connected() {
        return out != null;
    }

    static void log(String tag, String message) {
        PrintWriter writer = out;
        if (writer == null) return;
        try {
            writer.println(LocalTime.now().format(TIME) + " [" + tag + "] " + message);
            if (writer.checkError()) out = null;
        } catch (RuntimeException ignored) {
            out = null;
        }
    }

    static void close() {
        PrintWriter writer = out;
        out = null;
        if (writer != null) writer.close();
    }
}
