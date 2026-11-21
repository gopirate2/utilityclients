package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public final class KeybindSetting extends Setting {
    private int key;
    private boolean held;

    public KeybindSetting(String name, String description, int key, boolean held) {
        super(name, description);
        this.key = key;
        this.held = held;
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    public boolean isHeld() {
        return held;
    }

    /** Secondary bindings perform an action; their held flag never toggles state. */
    public String activationLabel() {
        return "keybind".equals(getName()) ? (held ? "hold" : "toggle") : "press";
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    @Override
    public Object getValue() {
        return key;
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof Number number) {
            setKey(number.intValue());
        }
    }

    @Override
    public JsonElement toJson() {
        // The held flag has to survive a round trip; writing only the key turned a
        // hold-to-act bind back into a toggle on the next load. Plain numbers stay
        // readable so configs written before this change still load.
        JsonObject object = new JsonObject();
        object.addProperty("key", key);
        object.addProperty("held", held);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null) return;
        if (element.isJsonPrimitive()) {
            setKey(element.getAsInt());
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("key")) setKey(object.get("key").getAsInt());
            if (object.has("held")) setHeld(object.get("held").getAsBoolean());
        }
    }
}
