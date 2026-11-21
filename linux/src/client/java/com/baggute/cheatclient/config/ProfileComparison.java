package com.baggute.cheatclient.config;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.settings.*;
import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Predicts the effective imported values without touching live settings or modules. */
public final class ProfileComparison {
    public record Change(String module, String setting, String current, String incoming, boolean ignored) {}
    private ProfileComparison() {}

    public static List<Change> compare(ModuleManager manager, JsonObject candidate) {
        List<Change> changes = new ArrayList<>();
        JsonObject modules = candidate.getAsJsonObject("modules");
        for (var entry : modules.entrySet()) {
            Module module = manager.getModules().stream().filter(m -> m.getName().equals(entry.getKey())).findFirst().orElse(null);
            if (module == null) {
                changes.add(new Change(entry.getKey(), "module", "unavailable", "ignored", true));
                continue;
            }
            JsonObject item = entry.getValue().getAsJsonObject();
            if (item.has("enabled") && item.get("enabled").getAsBoolean() != module.isEnabled())
                changes.add(new Change(module.getName(), "enabled", module.isEnabled() ? "on" : "off",
                        item.get("enabled").getAsBoolean() ? "on" : "off", false));
            JsonObject settings = item.getAsJsonObject("settings");
            if (settings == null) continue;
            for (var supplied : settings.entrySet()) {
                Setting setting = module.getPersistentSettings().stream().filter(s -> s.getName().equals(supplied.getKey())).findFirst().orElse(null);
                if (setting == null) {
                    changes.add(new Change(module.getName(), supplied.getKey(), "unavailable", "ignored", true));
                    continue;
                }
                JsonElement before = setting.toJson();
                JsonElement after = normalized(setting, supplied.getValue());
                if (before.equals(after)) continue;
                if (setting instanceof KeybindSetting) {
                    for (String field : new String[]{"key", "held"}) {
                        JsonElement oldValue = before.getAsJsonObject().get(field), nextValue = after.getAsJsonObject().get(field);
                        if (!oldValue.equals(nextValue)) changes.add(new Change(module.getName(), setting.getName() + "." + field,
                                display(oldValue), display(nextValue), false));
                    }
                } else changes.add(new Change(module.getName(), setting.getName(), display(before), display(after), false));
            }
        }
        changes.sort(java.util.Comparator.comparing(Change::module, String.CASE_INSENSITIVE_ORDER).thenComparing(Change::setting));
        return List.copyOf(changes);
    }

    /** Compare only stored identities; resolving names against the world is a later apply step. */
    public static List<Change> friends(JsonObject current, JsonObject incoming) {
        if (incoming == null) return List.of();
        com.baggute.cheatclient.friends.FriendsManager.validateJson(incoming);
        List<Change> changes = new ArrayList<>();
        for (String field : new String[]{"names", "uuids", "associations"}) {
            String before = friendValue(current, field), after = friendValue(incoming, field);
            if (!before.equals(after)) changes.add(new Change("Friends", field, before, after, false));
        }
        return List.copyOf(changes);
    }

    private static String friendValue(JsonObject object, String field) {
        if (field.equals("associations")) {
            java.util.SortedMap<String, String> sorted = new java.util.TreeMap<>();
            if (object.has(field)) object.getAsJsonObject(field).entrySet().forEach(e ->
                    sorted.put(e.getKey().trim().toLowerCase(java.util.Locale.ROOT), com.baggute.cheatclient.friends.FriendsManager.parseUuid(e.getValue().getAsString()).toString()));
            return sorted.toString();
        }
        java.util.SortedSet<String> sorted = new java.util.TreeSet<>();
        if (object.has(field)) for (JsonElement item : object.getAsJsonArray(field)) {
            String text = field.equals("uuids") ? com.baggute.cheatclient.friends.FriendsManager.parseUuid(item.getAsString()).toString()
                    : item.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!text.isEmpty()) sorted.add(text);
        }
        return sorted.toString();
    }

    private static JsonElement normalized(Setting setting, JsonElement value) {
        if (setting instanceof IntSetting integer)
            return new JsonPrimitive(Math.max(integer.getMin(), Math.min(integer.getMax(), value.getAsInt())));
        if (setting instanceof DoubleSetting number)
            return new JsonPrimitive(Math.max(number.getMin(), Math.min(number.getMax(), value.getAsDouble())));
        if (setting instanceof KeybindSetting) {
            JsonObject result = setting.toJson().getAsJsonObject().deepCopy();
            if (value.isJsonPrimitive()) result.addProperty("key", value.getAsInt());
            else for (String key : new String[]{"key", "held"}) if (value.getAsJsonObject().has(key))
                result.add(key, value.getAsJsonObject().get(key).deepCopy());
            return result;
        }
        return value.deepCopy();
    }

    private static String display(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        return value.toString();
    }
}
