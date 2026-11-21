package com.baggute.cheatclient.injector;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The channel back from the injected process.
 *
 * <p>An agent has no way to print into this process, so it dials a loopback port
 * that is opened before injection and streams lines here. Bound to loopback only,
 * one ephemeral port per run, and tolerant of a target that never connects.
 *
 * <p>Connections are handled concurrently: a hooked game holds two of them at
 * once — one from the agent's own relay and one from the bootstrap payload — and
 * a single-threaded acceptor would leave the second waiting forever behind a
 * socket that never closes.
 */
public final class LogServer implements Closeable {

    public static final String HOST = "127.0.0.1";

    private final ServerSocket socket;
    private final Consumer<String> sink;
    private volatile boolean closed;
    private final Object lifecycle = new Object();
    private final java.util.Map<Socket, Thread> clients = new java.util.concurrent.ConcurrentHashMap<>();
    private Thread acceptor;

    private void deliver(String line) {
        synchronized (lifecycle) {
            if (!closed) sink.accept(line);
        }
    }

    private LogServer(ServerSocket socket, Consumer<String> sink) {
        this.socket = socket;
        this.sink = sink;
    }

    public static LogServer start(Consumer<String> sink) throws IOException {
        ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName(HOST));
        LogServer server = new LogServer(socket, sink);

        Thread acceptor = new Thread(server::acceptLoop, "injector-log-accept");
        server.acceptor = acceptor;
        acceptor.setDaemon(true);
        acceptor.start();
        return server;
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException failure) {
                if (!closed) deliver("[!] log channel: " + JvmScanner.describe(failure));
                return;
            }

            Thread reader = new Thread(() -> drain(client), "injector-log-" + client.getPort());
            reader.setDaemon(true);
            synchronized (lifecycle) {
                if (closed) {
                    try { client.close(); } catch (IOException ignored) { }
                    return;
                }
                clients.put(client, reader);
                reader.start();
            }
        }
    }

    private void drain(Socket client) {
        try (Socket connection = client) {
            connection.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (isNoise(line)) continue;
                deliver(line);
            }
            deliver("[*] a log channel closed");
        } catch (IOException failure) {
            if (!closed) deliver("[!] log channel: " + JvmScanner.describe(failure));
        } finally {
            clients.remove(client);
        }
    }

    /**
     * Lines the injector deliberately swallows.
     *
     * <p>The JVM prints the dynamic-agent warning to the target's stderr during
     * {@code loadAgent}, which is before any code of ours runs — it cannot be
     * prevented, only filtered. It is also relayed here because the game's log4j
     * routes stderr through a logger named {@code STDERR}; both routes end at this
     * one filter, so the GUI and {@code --list} mode stay quiet.
     */
    public static boolean isNoise(String line) {
        if (line == null || line.isEmpty()) return false;
        return line.contains("A Java agent has been loaded dynamically")
                || line.contains("EnableDynamicAgentLoading")
                || line.contains("jdk.instrument.traceUsage")
                || line.contains("Dynamic loading of agents will be disallowed")
                || line.contains("[STDERR]")
                || line.contains("[STDOUT]");
    }

    @Override
    public void close() {
        java.util.List<Thread> readers;
        synchronized (lifecycle) {
            closed = true;
            try { socket.close(); } catch (IOException ignored) { }
            readers = new java.util.ArrayList<>(clients.values());
            for (Socket client : clients.keySet()) {
                try { client.close(); } catch (IOException ignored) { }
            }
        }
        if (acceptor != null) readers.add(acceptor);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        for (Thread reader : readers) {
            if (reader == Thread.currentThread()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { reader.join(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }
}
