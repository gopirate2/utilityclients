// language: Java, file: ColorSetting.java, target: Fabric 26.2
package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class ColorSetting extends Setting {
    private int value;

    public ColorSetting(String name, String description, int defaultColor) {
        super(name, description);
        this.value = defaultColor;
    }

    public int get() { return value; }
    public void set(int v) { this.value = v; }

    @Override public Object getValue() { return value; }

    @Override public void setValue(Object o) {
        if (o instanceof Number n) value = n.intValue();
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override public void fromJson(JsonElement e) {
        if (e != null && e.isJsonPrimitive()) value = e.getAsInt();
    }
}