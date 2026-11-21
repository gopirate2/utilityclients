package com.baggute.cheatclient;

import com.baggute.cheatclient.config.*;
import com.baggute.cheatclient.friends.FriendsManager;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.settings.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Review must predict the actual bounded values and leave live state untouched. */
public final class ProfileComparisonChecks {
    private static final class Sample extends Module {
        final IntSetting amount = new IntSetting("amount", "", 5, 0, 10);
        final DoubleSetting range = new DoubleSetting("range", "", 2.5, 0, 5);
        final KeybindSetting key = new KeybindSetting("keybind", "", 82, true);
        int callbacks;
        Sample() { super("Sample", "", Category.MISC); addSetting(amount); addSetting(range); addSetting(key); }
        @Override public void onConfigLoaded() { callbacks++; }
    }
    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("uc-profile-check-");
        ModuleManager manager = new ModuleManager(); Sample sample = new Sample(); manager.getModules().add(sample);
        ConfigManager config = new ConfigManager(manager, directory.resolve("settings.json"));
        var field = UtilityClient.class.getDeclaredField("configManager"); field.setAccessible(true); Object previous = field.get(null);
        JsonObject oldFriends = FriendsManager.toJson(); field.set(null, config);
        try {
            Files.writeString(directory.resolve("settings.json"), "preserved");
            JsonObject before = config.snapshot().getAsJsonObject("modules").deepCopy();
            JsonObject incoming = config.snapshot(), settings = incoming.getAsJsonObject("modules").getAsJsonObject("Sample").getAsJsonObject("settings");
            settings.addProperty("amount", 999); settings.addProperty("range", -10); settings.addProperty("keybind", 89); settings.addProperty("unavailable", "ignored");
            incoming.getAsJsonObject("modules").add("OldModule", new JsonObject());
            var changes = config.compareSnapshot(incoming);
            require(changes.stream().anyMatch(c -> c.setting().equals("amount") && c.incoming().equals("10")), "integer effective value");
            require(changes.stream().anyMatch(c -> c.setting().equals("range") && c.incoming().equals("0.0")), "double effective value");
            require(changes.stream().anyMatch(c -> c.setting().equals("keybind.key") && c.incoming().equals("89")), "legacy key comparison");
            require(changes.stream().noneMatch(c -> c.setting().equals("keybind.held")), "legacy key preserves hold mode");
            require(changes.stream().filter(ProfileComparison.Change::ignored).count() == 2, "unknown settings and modules marked ignored");
            require(config.snapshot().getAsJsonObject("modules").equals(before) && sample.callbacks == 0 && Files.readString(directory.resolve("settings.json")).equals("preserved"), "review causes no mutation, lifecycle or save");
            JsonObject prepared = ShareCode.prepareCode(incoming.toString());
            require(prepared.getAsJsonObject("modules").equals(incoming), "raw preset shape retained");
            JsonObject envelope = new JsonObject(); envelope.addProperty("client", UtilityClient.MOD_ID); envelope.addProperty("format", 1); envelope.add("modules", incoming);
            require(ShareCode.prepareCode(ShareCode.encodeKit(envelope)).equals(prepared), "share envelope shape decoded");
            require(ShareCode.prepareCode(envelope.toString()).equals(prepared), "raw envelope decoded");
            envelope.addProperty("client", "otherclient"); reject(envelope.toString(), "wrong client rejected"); envelope.addProperty("client", UtilityClient.MOD_ID);
            envelope.addProperty("format", 1.5); reject(envelope.toString(), "fractional format rejected"); envelope.addProperty("format", 1);
            settings.addProperty("amount", "bad"); reject(envelope.toString(), "invalid setting rejected before apply"); settings.addProperty("amount", 999);
            reject("{\"modules\":null}", "missing snapshot rejected");
            JsonObject huge = new JsonObject(); huge.addProperty("padding", "x".repeat(2 * 1024 * 1024 + 1)); reject(ShareCode.encodeKit(huge), "expanded import bounded");
            require(config.snapshot().getAsJsonObject("modules").equals(before), "invalid imports leave state untouched");
            String uuid = "a1234567-89ab-cdef-0123-456789abcdef";
            JsonObject friends = JsonParser.parseString("{\"names\":[\"  ALIce \"],\"uuids\":[\"" + uuid + "\"],\"associations\":{\" ALIce \":\"" + uuid + "\"}}").getAsJsonObject();
            JsonObject canonical = JsonParser.parseString("{\"names\":[\"alice\",\"alice\"],\"uuids\":[\"" + uuid.replace("-", "").toUpperCase(Locale.ROOT) + "\"],\"associations\":{\"alice\":\"" + uuid.replace("-", "") + "\"}}").getAsJsonObject();
            require(ProfileComparison.friends(friends, canonical).isEmpty(), "friend case, duplicates, whitespace and UUID formats normalize");
            require(ProfileComparison.friends(friends, new JsonObject()).size() == 3, "empty friend snapshot replaces stored fields");
            require(ProfileComparison.friends(friends, null).isEmpty(), "absent friends remain unchanged");
            require(ShareCode.applyPrepared(prepared), "reviewed snapshot applies");
            require(sample.amount.get() == 10 && sample.range.get() == 0 && sample.key.getKey() == 89 && sample.key.isHeld() && sample.callbacks == 1, "applied values match review");
            require(config.compareSnapshot(incoming).stream().allMatch(ProfileComparison.Change::ignored), "comparison clears after apply");
            System.out.println("PASS: profile review has no side effects; bounded values, key modes, friend UUIDs, envelope/raw shapes, rejected imports and exact reviewed apply");
        } finally {
            field.set(null, previous); FriendsManager.fromJson(oldFriends);
            try (var files = Files.walk(directory)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static void reject(String code, String message) throws Exception {
        try { ShareCode.prepareCode(code); throw new AssertionError(message); } catch (java.io.IOException expected) { }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
