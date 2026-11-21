package com.baggute.cheatclient.injector;

import com.google.gson.*;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import java.lang.reflect.InvocationTargetException;

/** Validates the intersection using Fabric's own acceptance rules. */
public final class DependencyChecks {
    public static void run() throws Exception {
        var merge = ModMerger.class.getDeclaredMethod("mergeDepends", JsonObject.class, JsonObject.class);
        merge.setAccessible(true);
        JsonObject host = JsonParser.parseString("{\"depends\":{\"java\":\">=21\",\"minecraft\":[\"~1.21\",\"26.2\"],\"fabricloader\":\">=0.16\",\"other\":\"*\"}}").getAsJsonObject();
        JsonObject client = JsonParser.parseString("{\"depends\":{\"java\":\">=25\",\"minecraft\":\"26.2\",\"fabricloader\":\">=0.19.5\"}}").getAsJsonObject();
        merge.invoke(null, host, client);
        JsonObject merged = host.getAsJsonObject("depends");
        require(!accepts(merged.get("java"),"21") && accepts(merged.get("java"),"25") && accepts(merged.get("java"),"26"), "Java keeps the stricter lower bound");
        require(!accepts(merged.get("minecraft"),"1.21.1") && accepts(merged.get("minecraft"),"26.2"), "incompatible OR branch excluded");
        require(!accepts(merged.get("fabricloader"),"0.19.4") && accepts(merged.get("fabricloader"),"0.19.5"), "Fabric keeps client minimum");
        require(merged.has("other"), "unrelated host requirement retained");
        for (String incompatible : new String[]{"<25", "21", ">=20 <25"}) {
            JsonObject old = JsonParser.parseString("{\"depends\":{\"java\":\""+incompatible+"\"}}").getAsJsonObject();
            try { merge.invoke(null,old,client); throw new AssertionError("incompatible host accepted"); }
            catch (InvocationTargetException expected) { require(expected.getCause() instanceof IllegalArgumentException,"incompatibility reported before commit"); }
        }
        System.out.println("PASS: Fabric dependency interval intersection, OR alternatives, incompatible runtimes and retained host requirements");
    }
    private static boolean accepts(JsonElement rule,String version) throws Exception {
        if (rule.isJsonArray()) { for (JsonElement branch : rule.getAsJsonArray()) if (accepts(branch,version)) return true; return false; }
        return VersionPredicate.parse(rule.getAsString()).test(Version.parse(version));
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
