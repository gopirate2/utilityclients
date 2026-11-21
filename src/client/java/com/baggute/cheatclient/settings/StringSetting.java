package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class StringSetting extends Setting {
    private String value;

    public StringSetting(String name, String description, String value) {
        super(name, description);
        this.value = value;
    }

    public String get() {
        return value;
    }

    public void set(String value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        if (value != null) {
            set(String.valueOf(value));
        }
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsString());
        }
    }
}