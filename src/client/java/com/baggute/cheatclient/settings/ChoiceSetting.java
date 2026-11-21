package com.baggute.cheatclient.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;

/** A finite selection, shared by the menu, config validation and extensions. */
public final class ChoiceSetting extends Setting {
    private final List<String> choices;
    private String value;
    public ChoiceSetting(String name, String description, String initial, String... choices) {
        super(name, description);
        this.choices = List.of(choices);
        if (this.choices.isEmpty() || this.choices.stream().distinct().count() != this.choices.size())
            throw new IllegalArgumentException("Choices must be nonempty and unique");
        set(initial);
    }
    public List<String> choices() { return choices; }
    public String get() { return value; }
    public boolean accepts(String candidate) { return choices.contains(candidate); }
    public void set(String candidate) {
        if (!accepts(candidate)) throw new IllegalArgumentException("Unknown choice: " + candidate);
        value = candidate;
    }
    public void cycle(int direction) { value = choices.get(Math.floorMod(choices.indexOf(value) + direction, choices.size())); }
    @Override public Object getValue() { return value; }
    @Override public void setValue(Object candidate) { if (candidate instanceof String text) set(text); }
    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement candidate) { set(candidate.getAsString()); }
}
