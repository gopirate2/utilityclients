package com.baggute.cheatclient.util;

import java.nio.file.*;
import com.baggute.cheatclient.UtilityClient;
import net.fabricmc.loader.api.FabricLoader;

/** Baritone loads as its own Fabric mod, with its standard commands and settings. */
public final class BaritoneSideload {
    private BaritoneSideload() {}
    public static void install() {
        var loader=FabricLoader.getInstance();
        if(loader.isModLoaded("baritone")) return;
        try {
            Path mods=loader.getGameDir().resolve("mods");
            if(Files.isSymbolicLink(mods)) throw new java.io.IOException("Linked mods folder");
            Files.createDirectories(mods);
            try(var entries=Files.list(mods)) {
                for(Path entry:entries.filter(p->p.toString().endsWith(".jar")).toList()) {
                    if(Files.isSymbolicLink(entry)) continue;
                    try(var jar=new java.util.zip.ZipFile(entry.toFile())) {
                        var metadata=jar.getEntry("fabric.mod.json");
                        if(metadata!=null) try(var input=jar.getInputStream(metadata)) {
                            var json=com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                            if(json.has("id") && json.get("id").getAsString().equals("baritone")) { notifyRestart(); return; }
                        }
                    } catch(java.io.IOException ignored) { }
                }
            }
            Path target=mods.resolve("baritone-utilityclient.jar");
            if(Files.exists(target)) throw new java.io.IOException("Baritone destination already belongs to a file");
            Path staged=Files.createTempFile(mods,".baritone-",".tmp");
            try(var input=BaritoneSideload.class.getResourceAsStream("/sideload/baritone.jar")) {
                if(input==null) throw new java.io.IOException("Missing Baritone side-load package");
                Files.copy(input,staged,StandardCopyOption.REPLACE_EXISTING);
                try(var jar=new java.util.zip.ZipFile(staged.toFile())) {
                    if(jar.getEntry("fabric.mod.json")==null) throw new java.io.IOException("Invalid Baritone package");
                }
                try { Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException ignored) { Files.move(staged,target); }
            } finally { Files.deleteIfExists(staged); }
            notifyRestart();
        } catch(Exception error) { UtilityClient.LOGGER.warn("Baritone side-load failed",error); }
    }
    private static void notifyRestart() { com.baggute.cheatclient.module.misc.Notifications.push("Baritone installed as a separate mod. Restart Minecraft, then use #help.",true); }
}
