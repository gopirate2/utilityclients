package com.baggute.cheatclient.module;

public enum Category {
    COMBAT("Combat"),
    AUTOMATION("Automation"),
    MOVEMENT("Movement"),
    PLAYER("Player"),
    RENDER("Render"),
    WORLD("World"),
    MISC("Misc"),
    EXPLOITS("Exploits"),
    ENABLED("Enabled"),
    BINDS("Binds"),
    FRIENDS("Friends"),
    KIT("Kit"),
    /**
     * The rail's "everything" row, not a category a module can belong to.
     *
     * <p>Kept in the enum rather than as a {@code null} sentinel in the rail's list
     * so the row carries the same shape as its neighbours: a display name, a count
     * and a selection state. {@link #isVirtual()} stays false for it - a virtual tab
     * draws its own panel, and this one lists every module instead.
     */
    MODULES("Modules"),
    APEX("skidded off apex");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Tabs that hold no modules at all; the click GUI draws its own panel for them. */
    public boolean isVirtual() {
        return this == ENABLED || this == BINDS || this == FRIENDS || this == KIT;
    }
}
