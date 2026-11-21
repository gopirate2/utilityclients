package com.baggute.cheatclient.injector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RepairChecks {
    public static void run() throws Exception {
        acceptedSocketsClose();
        closeFromSink();
        remoteCommit();
        DependencyChecks.run();
        System.out.println("PASS: accepted log sockets close, delivery stops, reentrant shutdown and atomic bare/wrapped JAR commit/failure cleanup");
    }

    private static void acceptedSocketsClose() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch initial = new CountDownLatch(4);
        LogServer server = LogServer.start(line -> { delivered.incrementAndGet(); initial.countDown(); });
        java.util.List<Socket> sockets = new java.util.ArrayList<>();
        try {
            for (int i=0; i<4; i++) {
                Socket socket = new Socket(LogServer.HOST, server.port()); sockets.add(socket);
                socket.setSoTimeout(1500); socket.getOutputStream().write("before\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            require(initial.await(3,TimeUnit.SECONDS), "accepted connections drain concurrently");
            server.close();
            int before = delivered.get();
            for (Socket socket : sockets) {
                try { socket.getOutputStream().write("after\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (java.io.IOException expected) { }
                try { require(socket.getInputStream().read() == -1, "accepted peer closed"); }
                catch (java.net.SocketException reset) { }
            }
            require(delivered.get() == before, "no sink delivery after close");
            var field = LogServer.class.getDeclaredField("clients"); field.setAccessible(true);
            require(((java.util.Map<?,?>)field.get(server)).isEmpty(), "reader resources released");
        } finally { server.close(); for (Socket socket : sockets) socket.close(); }
    }

    private static void closeFromSink() throws Exception {
        LogServer[] holder = new LogServer[1]; CountDownLatch closed = new CountDownLatch(1);
        holder[0] = LogServer.start(line -> { holder[0].close(); closed.countDown(); });
        try (Socket socket = new Socket(LogServer.HOST, holder[0].port())) {
            socket.getOutputStream().write("close\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require(closed.await(3,TimeUnit.SECONDS), "sink-triggered shutdown cannot join itself");
        } finally { holder[0].close(); }
    }

    private static void remoteCommit() throws Exception {
        Path dir = Files.createTempDirectory("uc-remote-check-");
        try {
            Path bare=dir.resolve("client.jar"), target=dir.resolve("installed.jar");
            jar(bare,"utilityclient"); Files.writeString(target,"old working jar");
            require(RemoteClient.unpackLocal(bare,target).isEmpty(), "bare jar installed");
            require(java.util.Arrays.equals(Files.readAllBytes(bare),Files.readAllBytes(target)), "complete jar committed");
            Path wrapper=dir.resolve("wrapper.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(wrapper))) {
                zip.putNextEntry(new ZipEntry("cheatclient.jar")); zip.write(Files.readAllBytes(bare)); zip.closeEntry();
            }
            require(RemoteClient.unpackLocal(wrapper,target).equals("unpacked cheatclient.jar"), "wrapped jar installed");
            Path platform=dir.resolve("platform.zip");
            try(var zip=new ZipOutputStream(Files.newOutputStream(platform))) {
                zip.putNextEntry(new ZipEntry("cheatclient.jar"));zip.write(Files.readAllBytes(bare));zip.closeEntry();
            }
            Path update=dir.resolve("UtilityClient-Update.zip");
            String platformDigest=ClientIntegrity.sha256(platform);
            try(var zip=new ZipOutputStream(Files.newOutputStream(update))) {
                zip.putNextEntry(new ZipEntry("update.json"));
                zip.write(("{\"schema\":1,\"repository\":\"gopirate2/utilityclients\",\"files\":{\"Payload.zip\":\""+platformDigest+"\",\"LinuxBundle.zip\":\""+platformDigest+"\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                for(String name:java.util.List.of("Payload.zip","LinuxBundle.zip")) {zip.putNextEntry(new ZipEntry(name));zip.write(Files.readAllBytes(platform));zip.closeEntry();}
            }
            require(RemoteClient.unpackLocal(update,target).endsWith("/cheatclient.jar"),"complete updater package supplies the platform client");
            require(java.util.Arrays.equals(Files.readAllBytes(bare),Files.readAllBytes(target)),"nested platform client is exact");
            var releases=com.google.gson.JsonParser.parseString("[{\"tag_name\":\"stable\",\"name\":\"UtilityClient 1.0.0 — "+ClientIntegrity.sha256(bare).substring(0,16)+"\",\"published_at\":\"2026-11-21\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"id\":1,\"name\":\"UtilityClient-Update.zip\",\"digest\":\"sha256:"+ClientIntegrity.sha256(update)+"\"}]}]").getAsJsonArray();
            var choose=RemoteClient.class.getDeclaredMethod("pickNewest",com.google.gson.JsonArray.class);choose.setAccessible(true);
            Object picked=choose.invoke(null,releases);require(picked!=null,"current updater assets participate in release discovery");
            var describe=RemoteClient.class.getDeclaredMethod("describeUpdate",picked.getClass(),Path.class);describe.setAccessible(true);
            var check=(RemoteClient.UpdateCheck)describe.invoke(null,picked,bare);
            require(check.current()&&check.message().contains("up to date"),"startup check recognizes exact installed version/build");
            require(!((RemoteClient.UpdateCheck)describe.invoke(null,picked,(Path)null)).current(),"missing client receives an update offer");
            byte[] good=Files.readAllBytes(target);
            Path bad=dir.resolve("wrong.jar"); jar(bad,"wrongmod");
            try { RemoteClient.unpackLocal(bad,target); throw new AssertionError("wrong client accepted"); }
            catch (java.io.IOException expected) { require(java.util.Arrays.equals(good,Files.readAllBytes(target)), "validation failure preserves old jar"); }
            Path blocked=Files.createDirectory(dir.resolve("blocked")); Files.writeString(blocked.resolve("keep"),"preserve");
            try { RemoteClient.unpackLocal(wrapper,blocked); throw new AssertionError("blocked commit accepted"); }
            catch (java.io.IOException expected) { require(Files.readString(blocked.resolve("keep")).equals("preserve"), "commit failure preserves destination"); }
            try (var paths=Files.list(dir)) { require(paths.noneMatch(p -> p.getFileName().toString().startsWith("uc-install-")), "all failed wrapper stages cleaned"); }
        } finally {
            try (var paths=Files.walk(dir)) {
                for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void jar(Path file, String identity) throws Exception {
        String supplied = System.getProperty("uc.regression.clientJar");
        IntegrityChecks.write(file, IntegrityChecks.fixture(supplied == null ? null : Path.of(supplied), identity));
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
