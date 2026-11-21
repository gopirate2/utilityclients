package com.baggute.cheatclient.injector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class ProgressChecks {
    static void run() throws Exception {
        OperationProgress.Session session = new OperationProgress.Session();
        require(session.accept(event("other", "READY", "wrong JVM")) == null, "other session ignored");
        require(session.accept("UC_STAGE " + session.id + " READY %%%") == null, "malformed event ignored");
        require(!session.accept(event(session.id, "INITIALIZING", "init")).terminal(), "initializing is not ready");
        require(session.accept(event(session.id, "LOADING", "late event")) == null, "out-of-order event ignored");
        require(session.await(10) == null, "missing target confirmation times out");
        require(!session.accept(event(session.id, "FAILED", "bad client")).ok(), "target failure preserved");
        require(session.accept(event(session.id, "READY", "late ready")) == null, "failure cannot be overwritten");

        AtomicReference<OperationProgress.Session> active = new AtomicReference<>();
        Path incompatible = java.nio.file.Files.createTempFile("uc-non-game-client-", ".jar");
        IntegrityChecks.write(incompatible, com.baggute.cheatclient.injector.agent.HookLinkageChecks.integrityFixture("utilityclient"));
        try (LogServer server = LogServer.start(line -> {
            OperationProgress.Session current = active.get();
            if (current != null) current.accept(line);
        })) {
            String javaExe = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            Process target = new ProcessBuilder(javaExe, "-XX:+EnableDynamicAgentLoading", "-cp",
                    System.getProperty("java.class.path"), RegressionChecks.class.getName(), "attachTarget")
                    .redirectErrorStream(true).start();
            try {
                java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(target.getInputStream()));
                require(output.readLine().equals("TARGET_READY"), "isolated target started");
                Path agent = Path.of(System.getProperty("uc.regression.agentJar", "build/libs/injector.jar")).toAbsolutePath();
                OperationProgress.Session failure = new OperationProgress.Session();
                active.set(failure);
                try {
                    AgentAttacher.inject(Long.toString(target.pid()), agent,
                            Map.of("request", failure.id, "host", LogServer.HOST, "port", Integer.toString(server.port()), "mod", "missing-client.jar"));
                    throw new AssertionError("Missing client passed integrity preflight");
                } catch (java.io.IOException expected) {
                    require(expected.getMessage().contains("integrity"), "missing client rejected before touching target");
                }
                require(failure.await(20) == null, "preflight failure never claims target initialization");
                AgentAttacher.inject(Long.toString(target.pid()), agent,
                        Map.of("request", failure.id, "host", LogServer.HOST, "port", Integer.toString(server.port()),
                                "mod", incompatible.toString()));
                OperationProgress.Event failed = failure.await(5000);
                require(failed != null && !failed.ok(), "actual agent reports incompatible non-game target");

                OperationProgress.Session success = new OperationProgress.Session();
                active.set(success);
                AgentAttacher.inject(Long.toString(target.pid()), agent,
                        Map.of("request", success.id, "host", LogServer.HOST, "port", Integer.toString(server.port())));
                OperationProgress.Event loaded = success.await(5000);
                require(loaded != null && loaded.ok() && loaded.message().contains("no client JAR"),
                        "agent-only attach never claims client readiness");
            } finally {
                target.destroyForcibly();
                target.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally { java.nio.file.Files.deleteIfExists(incompatible); }
        System.out.println("PASS: correlated target progress, rejected client, agent-only status");
    }

    private static String event(String id, String stage, String message) {
        return "UC_STAGE " + id + " " + stage + " " + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
