package com.baggute.cheatclient.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Executable regression checks with no game or external service dependency. */
public final class RegressionChecks {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("attachTarget")) {
                System.out.println("TARGET_READY");
                Thread.sleep(60_000);
                return;
            }
            if (args[0].equals("stall")) Thread.sleep(30_000);
            else System.out.print("x".repeat(2 * 1024 * 1024));
            return;
        }
        var options = Map.of("path", "C:\\build;alternate\\雪 = client.jar", "label", " a;b=c ");
        require(AgentAttacher.decode(AgentAttacher.encode(options)).equals(options), "separator/Unicode roundtrip");
        require(AgentAttacher.decode("path=legacy.jar;open=true").get("path").equals("legacy.jar"), "legacy decoding");

        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!System.getProperty("os.name").toLowerCase().contains("win"))
            javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Process stalled = new ProcessBuilder(javaExe, "-cp", cp, RegressionChecks.class.getName(), "stall")
                .redirectErrorStream(true).start();
        long start = System.nanoTime();
        try { ProcessOutput.read(stalled, 150); throw new AssertionError("stalled process accepted"); }
        catch (IOException expected) { require((System.nanoTime() - start) / 1_000_000 < 3000, "bounded process deadline"); }
        require(stalled.waitFor(3, java.util.concurrent.TimeUnit.SECONDS), "timed-out child terminated");
        Process flood = new ProcessBuilder(javaExe, "-cp", cp, RegressionChecks.class.getName(), "flood")
                .redirectErrorStream(true).start();
        require(ProcessOutput.read(flood, 5000).length() == 1024 * 1024, "bounded output drained without deadlock");

        var merge = ModMerger.class.getDeclaredMethod("mergeWideners", byte[].class, byte[].class);
        merge.setAccessible(true);
        byte[] host = "accessWidener v2 named\naccessible class host/A\n".getBytes(StandardCharsets.UTF_8);
        byte[] client = "accessWidener v2 named\naccessible class client/B\n".getBytes(StandardCharsets.UTF_8);
        String merged = new String((byte[]) merge.invoke(null, host, client), StandardCharsets.UTF_8);
        require(merged.contains("host/A") && merged.contains("client/B"), "both access wideners retained");
        try {
            merge.invoke(null, host, "accessWidener v2 intermediary\n".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("incompatible widener accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            require(expected.getCause() instanceof IOException, "incompatible widener rejected");
        }
        JsonArray assets = JsonParser.parseString("""
                [{"id":1,"name":"cheatclient.jar","updated_at":"2026-01-01T00:00:00Z"},
                 {"id":2,"name":"injector.jar","updated_at":"2026-12-01T00:00:00Z"}]
                """).getAsJsonArray();
        var newest = RemoteClient.class.getDeclaredMethod("newestAsset", JsonArray.class);
        newest.setAccessible(true);
        Object asset = newest.invoke(null, assets);
        var name = asset.getClass().getDeclaredMethod("name");
        name.setAccessible(true);
        require(name.invoke(asset).equals("cheatclient.jar"), "newer injector excluded from client selection");
        startupInstall();
        RepairChecks.run();
        LaunchOptionsChecks.run();
        IntegrityChecks.run();
        WindowChromeChecks.run();
        ProgressChecks.run();
        com.baggute.cheatclient.injector.agent.RuntimePatchChecks.run();
        com.baggute.cheatclient.injector.agent.BridgePatchChecks.run();
        com.baggute.cheatclient.injector.agent.HookLinkageChecks.run();
        System.out.println("PASS: encoding, process timeout/output ceiling, widener merge, artifact identity");
    }

    private static void startupInstall() throws Exception {
        String previous=System.getProperty("utilityclient.injectorRoot");
        Path root=java.nio.file.Files.createTempDirectory("utilityclient-startup-check-");
        System.setProperty("utilityclient.injectorRoot",root.resolve("data").toString());
        try {
            Path mods=java.nio.file.Files.createDirectory(root.resolve("mods"));
            Path source=root.resolve("client.jar");modJar(source,"utilityclient","first");
            Path target=StartupModInstaller.install(source,mods);
            require(java.nio.file.Files.mismatch(source,target)==-1,"startup install verified copy");
            require(StartupModInstaller.install(source,mods).equals(target),"startup install idempotence");
            modJar(source,"utilityclient","second");StartupModInstaller.install(source,mods);
            require(java.nio.file.Files.mismatch(source,target)==-1,"startup replacement verified");
            try(var backups=java.nio.file.Files.list(root.resolve("data/backups/startup-mods"))){require(backups.count()==1,"previous startup client backed up");}
            modJar(mods.resolve("duplicate.jar"),"cheatclient","duplicate");
            try{StartupModInstaller.install(source,mods);throw new AssertionError("duplicate accepted");}catch(IOException expected){}
            java.nio.file.Files.delete(mods.resolve("duplicate.jar"));java.nio.file.Files.delete(target);
            modJar(mods.resolve("utilityclient.jar"),"othermod","unrelated");
            byte[] unrelated=java.nio.file.Files.readAllBytes(mods.resolve("utilityclient.jar"));
            try{StartupModInstaller.install(source,mods);throw new AssertionError("unrelated overwritten");}catch(IOException expected){}
            require(java.util.Arrays.equals(unrelated,java.nio.file.Files.readAllBytes(mods.resolve("utilityclient.jar"))),"unrelated mod preserved");
            System.out.println("PASS: startup Fabric install, verified replacement, backup, duplicate and unrelated-mod guards");
        }finally{
            if(previous==null)System.clearProperty("utilityclient.injectorRoot");else System.setProperty("utilityclient.injectorRoot",previous);
        }
    }
    private static void modJar(Path path,String id,String content)throws IOException{
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path))){
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("marker"));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
