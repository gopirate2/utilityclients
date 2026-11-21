package com.baggute.cheatclient.injector;

import com.google.gson.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Structural verification without loading or initializing any client class. */
public final class ClientIntegrity {
    public record Report(String version, String sha256, long bytes, int entries, boolean expectedDigestMatched) {
        public String summary() { return "Client structure verified · " + version + " · SHA-256 " + sha256
                + (expectedDigestMatched ? " · expected digest matched" : " · no trusted release digest supplied"); }
    }
    private static final String ROOT = "com/baggute/cheatclient/";
    private static final long MAX_FILE = 256L * 1024 * 1024, MAX_EXPANDED = 512L * 1024 * 1024;
    private static final Map<String, List<String>> METHODS = Map.of(
            "LateBridge", List.of("onHudRender(Ljava/lang/Object;)V", "onSubmitFeatures(Ljava/lang/Object;)V",
                    "onRenderFrame()V", "onClientTick()V", "onKeyPress(Ljava/lang/Object;I)Z",
                    "captureItemUse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "completeItemUse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "scaleMotion(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "applyKnockback(Ljava/lang/Object;DDD)V",
                    "explosionImpulse(Ljava/lang/Object;)Ljava/util/Optional;", "onSeedChunk(Ljava/lang/Object;)V",
                    "onSeedLogin(Ljava/lang/Object;)V", "onSeedRespawn(Ljava/lang/Object;)V", "onSeedTick()V",
                    "onSeedDisconnect()V", "delayMovement(Ljava/lang/Object;)Z", "flushMovement()V"),
            "GameplayHooks", List.of("freezeInput(Ljava/lang/Object;)V", "camera(Ljava/lang/Object;)V",
                    "stepHeight(FLjava/lang/Object;)F", "lightmapOption(Ljava/lang/Object;)Ljava/lang/Object;",
                    "turn(Ljava/lang/Object;DD)Z", "hideHand()Z", "scroll(D)Z", "blockAttack(Ljava/lang/Object;Ljava/lang/Object;)Z"),
            "ContainerMouseHooks", List.of("handle(Ljava/lang/Object;Ljava/lang/Object;I)Z"));
    private ClientIntegrity() {}

    public static Report verify(Path path) throws IOException { return verify(path, null); }
    public static Report verify(Path path, String expectedSha256) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Client integrity: client JAR is missing");
        long size = Files.size(path);
        if (size < 4 || size > MAX_FILE) throw new IOException("Client integrity: JAR size is outside the supported limit");
        String expected = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        if (!expected.isEmpty() && !expected.matches("[0-9a-f]{64}")) throw new IOException("Client integrity: expected SHA-256 is invalid");
        String digest = sha256(path);
        if (!expected.isEmpty() && !digest.equals(expected)) throw new IOException("Client integrity: SHA-256 mismatch; download a fresh client");
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Set<String> names = new HashSet<>();
            long expanded = 0;
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement(); String name = entry.getName();
                if (!names.add(name) || names.size() > 60000 || name.startsWith("/") || name.contains("\\")
                        || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Client integrity: invalid or duplicate archive entry " + name);
                if (entry.isDirectory()) continue;
                CRC32 crc = new CRC32(); long count = 0;
                try (InputStream stream = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[65536]; int read;
                    while ((read = stream.read(buffer)) != -1) {
                        count += read; expanded += read;
                        if (expanded > MAX_EXPANDED) throw new IOException("Client integrity: archive expands beyond 512 MiB");
                        crc.update(buffer, 0, read);
                    }
                }
                if (entry.getSize() != count || entry.getCrc() != crc.getValue()) throw new IOException("Client integrity: damaged archive entry " + name);
            }
            byte[] metadata = read(zip, "fabric.mod.json", 1024 * 1024);
            JsonObject root = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = string(root, "id"), version = string(root, "version");
            if ((!id.equals("utilityclient") && !id.equals("cheatclient")) || version.isBlank())
                throw new IOException("Client integrity: selected artifact is not a UtilityClient build");
            ClassNode main = new ClassNode();
            new ClassReader(read(zip, ROOT + "UtilityClient.class", 4 * 1024 * 1024)).accept(main, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!main.name.equals(ROOT + "UtilityClient")) throw new IOException("Client integrity: entrypoint class identity mismatch");
            for (var contract : METHODS.entrySet()) {
                ClassNode node = new ClassNode();
                new ClassReader(read(zip, ROOT + contract.getKey() + ".class", 4 * 1024 * 1024))
                        .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (!node.name.equals(ROOT + contract.getKey())) throw new IOException("Client integrity: class identity mismatch");
                for (String signature : contract.getValue()) if (node.methods.stream().noneMatch(method ->
                        (method.name + method.desc).equals(signature)
                                && (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)))
                    throw new IOException("Client integrity: incompatible helper " + contract.getKey() + "." + signature);
            }
            // The declared mixin and nested dependency files must actually be present.
            if (root.has("mixins")) for (JsonElement mixin : root.getAsJsonArray("mixins"))
                read(zip, mixin.isJsonObject() ? string(mixin.getAsJsonObject(), "config") : mixin.getAsString(), 1024 * 1024);
            if (root.has("accessWidener")) read(zip, string(root, "accessWidener"), 1024 * 1024);
            if (root.has("jars")) for (JsonElement nested : root.getAsJsonArray("jars")) {
                String name = string(nested.getAsJsonObject(), "file");
                if (!names.contains(name)) throw new IOException("Client integrity: missing nested dependency " + name);
            }
            if (Files.size(path) != size || !sha256(path).equals(digest)) throw new IOException("Client integrity: JAR changed during verification");
            return new Report(version, digest, size, names.size(), !expected.isEmpty());
        } catch (RuntimeException malformed) {
            throw new IOException("Client integrity: malformed client metadata or helper bytecode", malformed);
        }
    }

    private static byte[] read(ZipFile zip, String name, int maximum) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > maximum) throw new IOException("Client integrity: missing or oversized " + name);
        try (InputStream stream = zip.getInputStream(entry)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Client integrity: oversized " + name);
            return bytes;
        }
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid " + key);
        return value.getAsString();
    }
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
