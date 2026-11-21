package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class DoubleSetting extends Setting {
    private double value;
    private final double min;
    private final double max;

    public DoubleSetting(String name, String description, double value, double min, double max) {
        super(name, description);
        this.min = min;
        this.max = max;
        set(value);
    }

    public double get() {
        return value;
    }

    public void set(double value) {
        // Math.min/max propagate NaN, which would defeat the clamp entirely and
        // then be persisted as a NaN config value.
        if (!Double.isFinite(value)) return;
        this.value = Math.max(min, Math.min(max, value));
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof Number number) {
            set(number.doubleValue());
        }
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsDouble());
        }
    }
}