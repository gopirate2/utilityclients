// language: Java, file: Hud.java, target: Fabric 26.2
package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.StringSetting;
import net.minecraft.client.Minecraft;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * HUD host. Every block on screen is a widget with its own toggle and its own
 * position, and the editor drags them individually.
 *
 * <p>Positions live in one setting ("layout", "WIDGET:x:y;..."), so a config
 * preset carries the whole arrangement. Widget visibility is registered as a
 * real setting too ("show_*"), which is what lets the editor add and remove
 * widgets and have the choice survive a restart.
 */
public class Hud extends Module {

    public enum Widget {
        // A few blocks are on from the start, so enabling the Hud module puts
        // something on screen instead of an empty corner that reads as broken.
        // The rest stay hidden and are added from the editor's picker.
        MODULES("modules", "Modules", true),
        COORDS("coords", "Coordinates", true),
        ARMOR("armor", "Armor", true),
        POTIONS("potions", "Potions", false),
        COUNTERS("counters", "Counters", false),
        POTIONS_COUNTER("potioncounter", "Potion count", false),
        NAMETAGS("nametags", "Nametags", false),
        TARGET_HUD("targethud", "Target HUD", false),
        TRAJECTORY("trajectory", "Trajectory", false),
        KEYSTROKES("keystrokes", "Keystrokes", false),
        FPS("fps", "FPS", true),
        PING("ping", "Ping", true),
        CLOCK("clock", "Clock", false),
        SPEED("speed", "Speed", false);

        private final String key;
        private final String title;
        private final boolean defaultVisible;

        Widget(String key, String title, boolean defaultVisible) {
            this.key = key;
            this.title = title;
            this.defaultVisible = defaultVisible;
        }

        public String key() { return key; }

        /** Human label used by the editor and the widget picker. */
        public String title() { return title; }

        /** Newer widgets start hidden so "add widget" has something to add. */
        public boolean defaultVisible() { return defaultVisible; }

        /**
         * Accepts both the lowercase layout key ("targethud") and the enum
         * constant ("TARGET_HUD") so layouts written by either form keep loading.
         */
        public static Widget byKey(String key) {
            if (key == null) return null;
            String wanted = key.trim();
            for (Widget w : values()) {
                if (w.key.equalsIgnoreCase(wanted) || w.name().equalsIgnoreCase(wanted)) return w;
            }
            return null;
        }
    }

    private final Map<Widget, BoolSetting> toggles = new EnumMap<>(Widget.class);
    private final Map<Widget, ColorSetting> colors = new EnumMap<>(Widget.class);
    private final Map<Widget, BoolSetting> backgrounds = new EnumMap<>(Widget.class);
    private final Map<Widget, DoubleSetting> widgetScales = new EnumMap<>(Widget.class);
    private final Map<Widget, int[]> positions = new EnumMap<>(Widget.class);
    private final Map<Widget, int[]> bounds = new EnumMap<>(Widget.class);

    private final DoubleSetting scale;
    private final ColorSetting textColor;
    private final StringSetting hiddenModules;
    private final StringSetting itemCounters;
    private final StringSetting potionFilter;
    private final StringSetting layout;
    private final StringSetting editPositions;

    public Hud() {
        super("Hud", "Displays the on-screen widgets.", Category.MISC);

        this.scale = new DoubleSetting("scale", "HUD Scale", 1.0, 0.5, 3.0);
        this.textColor = new ColorSetting("textColor", "Text color", 0xFF00E5FF);
        this.hiddenModules = new StringSetting("hiddenModules", "Hidden module names", "");
        this.itemCounters = new StringSetting("itemCounters", "Item IDs to count, comma separated", "");
        this.potionFilter = new StringSetting("potionFilter", "Potions to count", "");
        this.layout = new StringSetting("layout", "Widget positions", defaultLayout());
        this.editPositions = new StringSetting("editPositions", "Click to move HUD", "open");

        // Everything the renderer reads has to be a registered setting, otherwise
        // ConfigManager never writes it: an unregistered "layout" made the editor's
        // positions disappear on the next load, and unregistered filters could never
        // be edited at all. Per-widget styling stays out (it is code defaulted).
        addSetting(editPositions);
        addSetting(layout);
        addSetting(scale);
        addSetting(textColor);
        addSetting(hiddenModules);
        addSetting(itemCounters);
        addSetting(potionFilter);

        for (Widget w : Widget.values()) {
            BoolSetting toggle = new BoolSetting("show_" + w.key(), "Show " + w.title(), w.defaultVisible());
            ColorSetting color = new ColorSetting("color_" + w.key(), "Color for " + w.title(), 0xFF00E5FF);
            BoolSetting background = new BoolSetting("bg_" + w.key(), "Background for " + w.title(), false);
            DoubleSetting widgetScale = new DoubleSetting("scale_" + w.key(), "Scale for " + w.title(), 1.0, 0.5, 2.0);

            toggles.put(w, toggle);
            colors.put(w, color);
            backgrounds.put(w, background);
            widgetScales.put(w, widgetScale);

            // Registered so the editor's scoped panel can style a single widget and
            // presets carry that styling. Names share the widget key, which is how
            // the editor filters them ("show_targethud", "color_targethud", ...).
            addSetting(toggle);
            addSetting(color);
            addSetting(background);
            addSetting(widgetScale);
        }

        loadLayout(layout.get());
    }

    private static String defaultLayout() {
        return "FPS:5:5;PING:5:20;MODULES:5:35;COORDS:5:70;ARMOR:5:90;POTIONS:5:110;COUNTERS:5:130;POTIONS_COUNTER:5:150;NAMETAGS:5:190;TARGET_HUD:150:70;TRAJECTORY:150:110;KEYSTROKES:5:170";
    }

    // ---- layout ----

    /**
     * Parses "WIDGET:x:y;..." into a position per widget. Pure and static so the
     * self tests can exercise the real parser; unknown widgets and malformed
     * entries are skipped without disturbing the others.
     */
    public static Map<Widget, int[]> parseLayout(String data) {
        Map<Widget, int[]> parsed = new EnumMap<>(Widget.class);
        for (Widget w : Widget.values()) parsed.put(w, new int[]{5, 5});
        if (data == null || data.isBlank()) return parsed;

        for (String part : data.split(";")) {
            String[] fields = part.split(":");
            if (fields.length != 3) continue;
            Widget w = Widget.byKey(fields[0]);
            if (w == null) continue;
            try {
                parsed.put(w, new int[]{
                        Integer.parseInt(fields[1].trim()),
                        Integer.parseInt(fields[2].trim())
                });
            } catch (NumberFormatException ignored) {
                // A corrupt entry keeps the widget at its default instead of hiding it.
            }
        }
        return parsed;
    }

    /** Serialises positions in the canonical lowercase key form read by byKey. */
    public static String serializeLayout(Map<Widget, int[]> positions) {
        StringBuilder out = new StringBuilder();
        for (Widget w : Widget.values()) {
            int[] pos = positions.getOrDefault(w, new int[]{5, 5});
            if (out.length() > 0) out.append(';');
            out.append(w.key()).append(':').append(pos[0]).append(':').append(pos[1]);
        }
        return out.toString();
    }

    public final void loadLayout(String data) {
        positions.clear();
        positions.putAll(parseLayout(data));
    }

    public String saveLayout() {
        return serializeLayout(positions);
    }

    /** Puts every widget back where the shipped layout wants it. */
    public void resetLayout() {
        loadLayout(defaultLayout());
        layout.set(saveLayout());
    }

    public int getX(Widget w) {
        int[] pos = positions.get(w);
        return pos == null ? 5 : pos[0];
    }

    public int getY(Widget w) {
        int[] pos = positions.get(w);
        return pos == null ? 5 : pos[1];
    }

    public void setPosition(Widget w, int x, int y) {
        positions.put(w, new int[]{Math.max(0, x), Math.max(0, y)});
    }

    /** Renderer writes the drawn size here so the editor can box and drag it. */
    public void setBounds(Widget w, int x, int y, int width, int height) {
        bounds.put(w, new int[]{x, y, Math.max(6, width), Math.max(10, height)});
    }

    public int[] getBounds(Widget w) {
        return bounds.get(w);
    }

    // ---- widget visibility ----

    public boolean isWidgetEnabled(Widget w) {
        BoolSetting toggle = toggles.get(w);
        return toggle != null && toggle.get();
    }

    public void setWidgetEnabled(Widget w, boolean enabled) {
        BoolSetting toggle = toggles.get(w);
        if (toggle != null) toggle.set(enabled);
    }

    public boolean isWidgetAvailable(Widget w) {
        return toggles.containsKey(w);
    }

    /** How many widgets are on screen right now. */
    public int visibleWidgetCount() {
        int count = 0;
        for (Widget w : Widget.values()) if (isWidgetEnabled(w)) count++;
        return count;
    }

    /**
     * Adds a widget: switches it on and parks it in the first free slot so the
     * editor has something to grab instead of stacking it under another block.
     */
    public void addWidget(Widget w) {
        setWidgetEnabled(w, true);
        positions.put(w, findFreeSpot(w));
        layout.set(saveLayout());
    }

    public void removeWidget(Widget w) {
        setWidgetEnabled(w, false);
        layout.set(saveLayout());
    }

    public List<Widget> widgets() {
        return List.of(Widget.values());
    }

    private int[] findFreeSpot(Widget self) {
        int scaledW = 320, scaledH = 240;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null) {
            scaledW = mc.getWindow().getGuiScaledWidth();
            scaledH = mc.getWindow().getGuiScaledHeight();
        }
        double guiScale = Math.max(0.5, getScale());
        int limitX = (int) (scaledW / guiScale);
        int limitY = (int) (scaledH / guiScale);

        int cellW = 108, cellH = 16, pad = 3;
        for (int y = 6; y + cellH < Math.max(48, limitY - 16); y += cellH + 2) {
            for (int x = 6; x + 96 < Math.max(72, limitX - 4); x += cellW) {
                if (!overlaps(self, x, y, 96, 14, pad)) return new int[]{x, y};
            }
        }
        return new int[]{6, 6 + (visibleWidgetCount() * cellH)};
    }

    private boolean overlaps(Widget self, int x, int y, int w, int h, int pad) {
        for (Widget other : Widget.values()) {
            if (other == self || !isWidgetEnabled(other)) continue;
            int ox = getX(other);
            int oy = getY(other);
            int ow = 96, oh = 14;
            int[] b = bounds.get(other);
            if (b != null) {
                ox = b[0];
                oy = b[1];
                ow = Math.max(24, b[2]);
                oh = Math.max(10, b[3]);
            }
            boolean apart = x + w + pad <= ox || ox + ow + pad <= x
                    || y + h + pad <= oy || oy + oh + pad <= y;
            if (!apart) return true;
        }
        return false;
    }

    public int getWidgetColor(Widget w) {
        ColorSetting color = colors.get(w);
        return color == null ? getTextColor() : color.get();
    }

    public boolean hasWidgetBackground(Widget w) {
        BoolSetting background = backgrounds.get(w);
        return background != null && background.get();
    }

    public double getWidgetScale(Widget w) {
        DoubleSetting widgetScale = widgetScales.get(w);
        return widgetScale == null ? 1.0 : widgetScale.get();
    }

    public StringSetting getLayoutSetting() { return layout; }

    @Override
    public void onConfigLoaded() {
        loadLayout(layout.get());

    }

    public double getScale() { return scale.get(); }
    public int getTextColor() { return textColor.get(); }
    public StringSetting getHiddenSetting() { return hiddenModules; }
    public String getItemCounters() { return itemCounters.get(); }
    public String getPotionFilter() { return potionFilter.get(); }

    @Override
    protected void onEnable() {
        UtilityClient.LOGGER.info("[Hud] enabled");
    }

    @Override
    protected void onDisable() {
        UtilityClient.LOGGER.info("[Hud] disabled");
    }
}
