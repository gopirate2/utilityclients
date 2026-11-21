package com.baggute.cheatclient.injector;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Complete built client plus independent corruptions and actionable recovery. */
public final class IntegrityChecks {
    private static final String ROOT = "com/baggute/cheatclient/";
    public static void run() throws Exception {
        String supplied = System.getProperty("uc.regression.clientJar");
        Path production = supplied == null ? Files.createTempFile("uc-integrity-base-", ".jar") : Path.of(supplied);
        if (supplied == null) write(production, com.baggute.cheatclient.injector.agent.HookLinkageChecks.integrityFixture("utilityclient"));
        var verified = ClientIntegrity.verify(production, ClientIntegrity.sha256(production));
        require(verified.expectedDigestMatched(), "published client verifies with expected hash");
        require(!ClientIntegrity.verify(production).expectedDigestMatched(), "structural verification labels missing publisher digest");
        reject(production, "0".repeat(64), "wrong digest rejected"); reject(production, "xyz", "invalid digest rejected");
        Path directory = Files.createTempDirectory("uc-integrity-check-");
        try {
            Map<String, byte[]> fixture = fixture(production, "utilityclient");
            Path jar = directory.resolve("fixture.jar"); write(jar, fixture); ClientIntegrity.verify(jar);
            Map<String, byte[]> bad = new LinkedHashMap<>(fixture); bad.remove(ROOT + "GameplayHooks.class"); write(jar, bad); reject(jar, null, "missing runtime helper rejected");
            bad = new LinkedHashMap<>(fixture); ClassNode node = new ClassNode(); new ClassReader(bad.get(ROOT + "GameplayHooks.class")).accept(node, 0);
            node.methods.removeIf(method -> method.name.equals("camera")); ClassWriter writer = new ClassWriter(0); node.accept(writer); bad.put(ROOT + "GameplayHooks.class", writer.toByteArray()); write(jar, bad); reject(jar, null, "missing method rejected");
            bad = new LinkedHashMap<>(fixture); bad.put("../outside", new byte[1]); write(jar, bad); reject(jar, null, "traversal entry rejected");
            bad = new LinkedHashMap<>(fixture); bad.put("fabric.mod.json", "{\"id\":\"other\",\"version\":\"1\"}".getBytes()); write(jar, bad); reject(jar, null, "wrong identity rejected");
            bad = new LinkedHashMap<>(fixture); bad.put("fabric.mod.json", "{\"id\":\"utilityclient\",\"version\":\"1\",\"jars\":[{\"file\":\"missing.jar\"}]}".getBytes()); write(jar, bad); reject(jar, null, "missing nested dependency rejected");
            bad = new LinkedHashMap<>(fixture); bad.put(ROOT + "UtilityClient.class", new byte[] {1,2,3,4}); write(jar, bad); reject(jar, null, "malformed entrypoint bytecode rejected");
            String hash = verified.sha256();
            require(RemoteClient.parseChecksum(hash + "  cheatclient.jar\n", "cheatclient.jar").equals(hash), "publisher checksum filename matched");
            try { RemoteClient.parseChecksum(hash + "  other.jar\n", "cheatclient.jar"); throw new AssertionError("wrong checksum file accepted"); } catch (IOException expected) { }
            try { RemoteClient.parseChecksum(hash + "  cheatclient.jar\n" + hash + "  cheatclient.jar\n", "cheatclient.jar"); throw new AssertionError("duplicate checksum accepted"); } catch (IOException expected) { }
            for (String message : List.of("Client integrity SHA-256 mismatch", "NoClassDefFoundError", "process exited", "DisableAttachMechanism", "access is denied", "jdk.attach", "timeout", "locked", "HTTP 403", "missing file"))
                require(!FailureRecovery.forMessage(message).steps().isEmpty(), "recovery supplied for " + message);
            require(FailureRecovery.forMessage("NoClassDefFoundError").text().contains("Fully close Minecraft"), "loaded-class failure requires restart");
            System.out.println("PASS: complete client SHA-256, structural provenance, corrupted identities/methods/entrypoint/dependencies/paths, publisher checksum matching and failure recovery");
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            if (supplied == null) Files.deleteIfExists(production);
        }
    }
    static Map<String, byte[]> fixture(Path production, String id) throws IOException {
        if (production == null) return com.baggute.cheatclient.injector.agent.HookLinkageChecks.integrityFixture(id);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile jar = new ZipFile(production.toFile())) {
            for (String name : List.of("UtilityClient", "LateBridge", "GameplayHooks", "ContainerMouseHooks")) {
                String file = ROOT + name + ".class"; try (InputStream input = jar.getInputStream(jar.getEntry(file))) { entries.put(file, input.readAllBytes()); }
            }
        }
        entries.put("fabric.mod.json", ("{\"id\":\"" + id + "\",\"version\":\"1.0.0\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return entries;
    }
    static void write(Path jar, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) { for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } }
    }
    private static void reject(Path jar, String digest, String message) throws Exception {
        try { ClientIntegrity.verify(jar, digest); throw new AssertionError(message); } catch (IOException expected) { }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
