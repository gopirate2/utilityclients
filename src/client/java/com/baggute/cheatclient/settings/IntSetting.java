package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class IntSetting extends Setting {
    private int value;
    private final int min;
    private final int max;

    public IntSetting(String name, String description, int value, int min, int max) {
        super(name, description);
        this.min = min;
        this.max = max;
        set(value);
    }

    public int get() {
        return value;
    }

    public void set(int value) {
        this.value = Math.max(min, Math.min(max, value));
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof Number number) {
            set(number.intValue());
        }
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsInt());
        }
    }
}