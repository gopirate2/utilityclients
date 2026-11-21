package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;

public abstract class Setting {
    private final String name;
    private final String description;

    /**
     * The value this setting was constructed with, captured at registration.
     *
     * <p>Stored as a JSON copy rather than a typed field so the reset path works
     * for every subclass without one override each. Null until
     * {@link #captureDefault()} runs, which {@code Module.addSetting} does.
     */
    private JsonElement defaultValue;

    protected Setting(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDisplayName() {
        if (name.equals("sendChatFeedback")) return "Send Chat Feedback";
        if (name.equals("missChance")) return "Miss Chance (%)";
        return name;
    }

    /**
     * Snapshots the current value as the default.
     *
     * <p>Deep-copied because several subclasses hand out the same JSON object
     * shape on every {@code toJson} call and a live reference would follow later
     * edits, turning "reset" into "undo nothing".
     */
    public final void captureDefault() {
        JsonElement snapshot = toJson();
        defaultValue = snapshot == null ? null : snapshot.deepCopy();
    }

    /** True once a default has been captured, i.e. the setting is registered. */
    public final boolean hasDefault() {
        return defaultValue != null;
    }

    /** Restores the captured default. Returns false when nothing was captured. */
    public final boolean resetToDefault() {
        if (defaultValue == null) return false;
        fromJson(defaultValue.deepCopy());
        return true;
    }

    public abstract Object getValue();

    public abstract void setValue(Object value);

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);
}
