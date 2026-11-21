package com.baggute.cheatclient;

import com.baggute.cheatclient.config.KitManager;
import com.baggute.cheatclient.config.ShareCode;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Exercises clipboard-kit validation without a world or filesystem writes. */
public final class KitImportChecks {
    public static void run() throws Exception {
        JsonObject kit = new JsonObject();
        kit.addProperty("format", 1);
        JsonArray slots = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("slot", 0);
        row.addProperty("itemId", "minecraft:stone");
        row.addProperty("count", 32);
        slots.add(row);
        kit.add("slots", slots);
        require(KitManager.decodeImport(ShareCode.encodeKit(kit)).equals(kit), "kit share-code roundtrip");
        require(KitManager.decodeImport(kit.toString()).equals(kit), "raw JSON clipboard import");
        reject("{\"modules\":{}}", "config cannot be imported as a kit");
        reject("{\"slots\":{}}", "slots must be an array");
        reject("[]", "kit must be an object");
        slots.add(row.deepCopy());
        reject(kit.toString(), "duplicate slots rejected");
        slots.remove(1);
        row.addProperty("slot", 41);
        reject(kit.toString(), "out-of-bounds slot rejected");
        row.addProperty("slot", 0.5);
        reject(kit.toString(), "fractional slot rejected");
        row.addProperty("slot", 0);
        row.addProperty("count", 0);
        reject(kit.toString(), "invalid count rejected");
        row.addProperty("count", 32);
        row.addProperty("itemId", "INVALID ID");
        reject(kit.toString(), "invalid item ID rejected");
        JsonObject huge = new JsonObject();
        huge.addProperty("padding", "x".repeat(2 * 1024 * 1024 + 1));
        reject(ShareCode.encodeKit(huge), "gzip expansion bounded");
        System.out.println("PASS: kit share-code/JSON import, wrong payloads, duplicate slots, indices, counts and bounded expansion");
    }
    private static void reject(String code, String name) {
        boolean rejected = false;
        try { KitManager.decodeImport(code); }
        catch (Exception expected) { rejected = true; }
        require(rejected, name);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
