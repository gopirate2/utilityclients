package com.baggute.cheatclient.injector.agent;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Log channel for the bootstrap half of the agent.
 *
 * <p>Separate from the agent's own {@code Relay} because a bootstrap class cannot
 * see a system-classloader class. The port arrives as a system property set by
 * {@code ClientAgent} before any hook can fire.
 *
 * <p>Nothing here may run on the game thread in a way that blocks: the socket is
 * opened on a daemon thread, and lines emitted before it is up are dropped rather
 * than queued. A hook on a 60 fps method must never stall a frame.
 */
public final class AgentLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String PORT_PROPERTY = "utilityclient.injector.port";
    private static final String HOST_PROPERTY = "utilityclient.injector.host";

    private static volatile PrintWriter out;
    private static volatile Socket connection;
    private static volatile String endpoint;
    private static boolean connecting;
    private static long nextAttempt;

    private AgentLog() {}

    public static void log(String tag, String message) {
        String requested = System.getProperty(HOST_PROPERTY, "127.0.0.1") + ":"
                + System.getProperty(PORT_PROPERTY, "");
        PrintWriter writer = out;
        if (writer == null || !requested.equals(endpoint)) {
            if (writer != null) disconnect(writer);
            connectOnce();
            return;
        }
        try {
            writer.println(LocalTime.now().format(TIME) + " [" + tag + "] " + message);
            if (writer.checkError()) disconnect(writer);
        } catch (RuntimeException ignored) {
            disconnect(writer);
        }
    }

    public static void error(String message) { log("!", message); }

    private static synchronized void disconnect(PrintWriter expected) {
        if (out != expected) return;
        out = null;
        endpoint = null;
        Socket old = connection;
        connection = null;
        if (old != null) try { old.close(); } catch (Exception ignored) {}
    }

    private static synchronized void connectOnce() {
        if (connecting || System.nanoTime() < nextAttempt) return;
        connecting = true;
        Thread thread = new Thread(AgentLog::connect, "uc-hook-log");
        thread.setDaemon(true);
        thread.start();
    }

    private static void connect() {
        Socket socket = new Socket();
        try {
            String portText = System.getProperty(PORT_PROPERTY, "").trim();
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) return;
            String host = System.getProperty(HOST_PROPERTY, "127.0.0.1");
            String target = host + ":" + portText;
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 2000);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            synchronized (AgentLog.class) {
                if (connection != null) try { connection.close(); } catch (Exception ignored) {}
                connection = socket;
                endpoint = target;
                out = writer;
                socket = null;
            }
        } catch (Exception ignored) {
            // Retry on a later log event without blocking a game callback.
        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            synchronized (AgentLog.class) {
                connecting = false;
                nextAttempt = System.nanoTime() + 2_000_000_000L;
            }
        }
    }
}
