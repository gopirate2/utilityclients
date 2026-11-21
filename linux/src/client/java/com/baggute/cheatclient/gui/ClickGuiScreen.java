// language: Java, file: ClickGuiScreen.java, target: Fabric 26.2
package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.config.KitManager;
import com.baggute.cheatclient.config.ShareCode;
import com.baggute.cheatclient.friends.FriendsManager;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.apex.ApexModule;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.settings.Setting;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.util.ChatCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The sheet: sidebar, module list, settings column, footer.
 *
 * <p>Everything is drawn in a fixed 840x470 design space and mapped to the real
 * window by a single matrix push. The click handlers run the identical transform
 * backwards, so a hitbox can never drift from the pixels it belongs to, whatever
 * the GUI scale or window size.
 *
 * <p>The sheet is pinned centre-screen and never moves. It used to be draggable,
 * which meant a stray click while aiming at a slider carried the whole window
 * with it and every stored offset had to be clamped back into view on resize.
 *
 * <p>The three columns scroll independently and all of them are driven by content
 * height in pixels rather than by row count â€” a module with twenty settings and
 * a category with fifty modules both behave the same way.
 */
public class ClickGuiScreen extends Screen {

    // ---- design box ----

    private static final int W = 840;
    private static final int H = 470;

    private static final int HEADER_H = 34;
    private static final int PILL_H = 26;
    private static final int FOOTER_H = 26;

    /**
     * The rail holds only the four utility panels, so it can stay narrow; the
     * width it gives up goes to the two columns that hold rows of controls.
     */
    private static final int SIDEBAR_W = 120;
    private static final int SETTINGS_W = 320;
    private static final int PAD = 10;

    private static final int MODULE_ROW_H = 26;
    private static final int COLUMN_H = 20;
    private static final int SETTING_ROW_H = 22;
    private static final int SLIDER_ROW_H = 30;

    /** Fixed rail row height: four rows never need squeezing, and a moving target drifts hits. */
    private static final int SIDEBAR_ROW_H = 26;

    /**
     * Hover wash, half-alpha black.
     *
     * <p>Explicitly translucent rather than a solid swatch: a list row that turns
     * opaque on hover erases whatever was drawn under it, and on the sidebar the
     * rows sit on the panel's recess colour, so the row visibly changes shade
     * instead of turning into a slab.
     */
    private static final int HOVER_SCRIM = 0x80000000;

    // ---- kit preview grid ----

    /** 9x4 is the shape of the player's own inventory screen. */
    private static final int KIT_COLS = 9;
    private static final int KIT_ROWS = 4;
    private static final int KIT_SLOT = 18;
    private static final int KIT_GAP = 2;
    /** Helmet, chest, legs, boots, offhand: everything the 36-cell grid cannot hold. */
    private static final int KIT_GEAR_SLOTS = 5;
    /** Player inventory index of the first gear slot (armour 36-39, offhand 40). */
    private static final int KIT_GEAR_FIRST_SLOT = 36;

    /** Longest accepted friend or kit name, so a held key cannot grow a string forever. */
    private static final int MAX_INPUT = 48;

    // ---- centre column modes ----

    private static final int MODE_LIST = 0;
    private static final int MODE_FRIENDS = 1;
    private static final int MODE_KITS = 2;
    private static final int MODE_PRESETS = 3;
    private static final int MODE_PICKER = 4;

    // ---- window state ----

    private int x;
    private int y;
    private float uiScale = 1f;

    // ---- selection ----

    private Category selectedCategory = Category.COMBAT;
    /**
     * True while the list is unfiltered.
     *
     * <p>Set by the ALL pill and by the rail's Modules row, which are the same state
     * reached from two places: {@link #selectCategory} turns it on for Modules, and a
     * category row turns it off again.
     */
    private boolean allCategories;
    private Module selectedModule;
    private Setting listeningKeybind;
    private Setting numericInputTarget;
    private StringSetting stringInputTarget;
    private String stringInputValue = "";
    private String numericInputValue = "";
    private Setting colorPickerTarget;

    // ---- text ----

    private String searchQuery = "";
    private boolean searchFocused;
    private int textInputMode;
    private String friendsInput = "";
    private String kitInput = "kit";

    // ---- scroll, in design pixels ----

    private int centreScroll;
    private int settingsScroll;
    private int friendsScroll;
    private int kitScroll;
    private int presetScroll;
    private int pickerScroll;

    // ---- list picker ----

    private boolean listMode;
    private String listSettingName;
    private String listSearchQuery = "";
    private boolean listSearchFocused;
    private List<String> listIds;
    private String listCacheTag;

    // ---- colour picker ----

    private float pickerHue;
    private float pickerSat = 1f;
    private float pickerVal = 1f;
    private int pickerAlpha = 255;
    private int pickerDragMode;

    // ---- slider drag ----

    private Setting mouseDownSetting;
    private int mouseDownX;
    private double mouseDownValue;
    private int sliderRowW = 1;
    private boolean sliderDidDrag;

    // ---- misc ----

    private boolean presetMode;
    private boolean returnToEditor;
    private String hoverHint = "";
    private List<String> kitCache;

    /**
     * Limits the settings column to rows whose name contains this string.
     *
     * <p>Only ever set by the deep link from the HUD editor, which opens the sheet
     * on one widget's settings; the top search bar filters modules instead, so the
     * two never fight over the same field.
     */
    private String settingsFilter = "";

    /** Decoded slots behind the KIT preview grid; every entry starts empty. */
    private ItemStack[] previewStacks = emptyPreview();
    /** Kit currently shown in the preview, or "" when nothing has been selected. */
    private String previewName = "";

    private static ItemStack[] emptyPreview() {
        ItemStack[] out = new ItemStack[KitManager.INVENTORY_SLOTS];
        Arrays.fill(out, ItemStack.EMPTY);
        return out;
    }

    public ClickGuiScreen() {
        this(null, null);
    }

    /** Opens straight into a module, and remembers to hand control back to the HUD editor. */
    public ClickGuiScreen(Module focus, String query) {
        super(Component.literal("UtilityClient"));
        if (focus != null) {
            this.selectedModule = focus;
            this.selectedCategory = focus.getCategory();
        }
        if (query != null) this.settingsFilter = query;
        this.returnToEditor = focus instanceof Hud;
    }

    // ---- layout ----

    private int headerTop()     { return y; }
    private int pillTop()       { return y + HEADER_H; }
    private int bodyTop()       { return y + HEADER_H + PILL_H; }
    private int bodyBottom()    { return y + H - FOOTER_H; }
    private int sidebarRight()  { return x + SIDEBAR_W; }
    private int centreLeft()    { return x + SIDEBAR_W; }
    private int centreW()       { return W - SIDEBAR_W - SETTINGS_W; }
    private int settingsLeft()  { return x + W - SETTINGS_W; }
    private int contentTop()    { return bodyTop() + 1; }

    private int searchX()     { return x + SIDEBAR_W + 10; }
    private int searchY()     { return headerTop() + 8; }
    private int searchH()     { return 18; }
    private int searchW()     { return (x + W - 46) - searchX(); }

    private int closeX()  { return x + W - 34; }
    private int closeY()  { return headerTop() + 4; }
    private int closeW()  { return 26; }
    private int closeH()  { return 26; }

    private int footerTop() { return bodyBottom(); }

    private int settingsListTop()    { return contentTop() + 26; }
    private int settingsListBottom() { return bodyBottom() - 28; }
    private int settingsBodyW()      { return SETTINGS_W - 24; }

    /**
     * Pins the sheet to the exact centre of the window, every frame.
     *
     * <p>The fit scale is recomputed on each call because a window resize changes
     * it mid-session; the origin is then derived from the current dimensions
     * rather than stored, so there is no offset left to be dragged, saved or
     * clamped back into view.
     */
    private void ensurePosition() {
        float fit = Math.min(width / (float) (W + 32), height / (float) (H + 32));
        uiScale = Math.max(0.05f, Math.min(1f, fit));
        x = (width / 2) - (W / 2);
        y = (height / 2) - (H / 2);
    }

    // The scale is centred on the window, so screen = origin + design * scale.

    private float originX() { return width * (1f - uiScale) * 0.5f; }

    private float originY() { return height * (1f - uiScale) * 0.5f; }

    private double designX(double screenX) { return (screenX - originX()) / uiScale; }

    private double designY(double screenY) { return (screenY - originY()) / uiScale; }

    private static boolean inRect(double mx, double my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    private int textY(int rowY, int rowH) { return rowY + (rowH - 8) / 2; }

    // ---- data ----

    private boolean isFiltering() {
        return !searchQuery.trim().isEmpty();
    }

    private void setSearchQuery(String value) {
        searchQuery = value;
        centreScroll = 0;
    }

    private int centreMode() {
        if (presetMode) return MODE_PRESETS;
        if (listMode) return MODE_PICKER;
        if (!isFiltering()) {
            if (selectedCategory == Category.FRIENDS) return MODE_FRIENDS;
            if (selectedCategory == Category.KIT) return MODE_KITS;
        }
        return MODE_LIST;
    }

    /** Every row the centre list can show, in display order. */
    private List<Module> visibleModules() {
        if (isFiltering()) {
            String q = searchQuery.trim().toLowerCase();
            List<Module> out = new ArrayList<>();
            for (Module m : UtilityClient.moduleManager.getVisibleModules()) {
                if (m.getName().toLowerCase().contains(q)) out.add(m);
            }
            out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return out;
        }
        if (selectedCategory == Category.MODULES) return everyModule();
        if (selectedCategory == Category.ENABLED) return enabledModules();
        if (selectedCategory == Category.BINDS) return UtilityClient.moduleManager.getBoundModules();
        if (selectedCategory.isVirtual()) return Collections.emptyList();
        if (allCategories) return everyModule();
        return UtilityClient.moduleManager.getByCategory(selectedCategory);
    }

    /** Every module on the client, alphabetically - the unfiltered list. */
    private List<Module> everyModule() {
        List<Module> out = new ArrayList<>(UtilityClient.moduleManager.getVisibleModules());
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private List<Module> enabledModules() {
        List<Module> out = new ArrayList<>();
        for (Module m : UtilityClient.moduleManager.getVisibleModules()) {
            if (m.isEnabled()) out.add(m);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private List<Module> boundModules() {
        return UtilityClient.moduleManager.getBoundModules();
    }

    private int countFor(Category cat) {
        if (cat == Category.MODULES) return everyModule().size();
        if (cat == Category.ENABLED) return enabledModules().size();
        if (cat == Category.BINDS) return boundModules().size();
        if (cat == Category.FRIENDS) return FriendsManager.size();
        if (cat == Category.KIT) return kitNames().size();
        if (cat.isVirtual()) return 0;
        return UtilityClient.moduleManager.getByCategory(cat).size();
    }

    /** Short label for the narrow sidebar and the pill strip. */
    private String shortLabel(Category cat) {
        return cat == Category.APEX ? "Apex" : cat.getDisplayName();
    }

    /**
     * The rail: the unfiltered list first, then the four utility panels.
     *
     * <p>Categories used to be mirrored here as well, which put two controls on
     * one piece of state and made the narrow column the widest thing on the
     * sheet. Filtering by category is the pill strip's job; the rail is where
     * the panels that are not categories live - plus Modules, which is not a
     * category either, it is the list with no filter at all.
     */
    private List<Category> sidebarTabs() {
        return List.of(Category.MODULES, Category.ENABLED, Category.BINDS,
                Category.FRIENDS, Category.KIT);
    }

    private List<Category> pillTabs() {
        List<Category> out = new ArrayList<>();
        for (Category cat : Category.values()) {
            // Modules is every category at once; it is the ALL pill, not a pill of
            // its own, and drawing both would put two identical filters side by side.
            if (cat == Category.MODULES) continue;
            if (cat.isVirtual()) continue;
            if (countFor(cat) > 0) out.add(cat);
        }
        return out;
    }

    /** Every key bound to a module, primary or secondary. */
    private List<String> allKeyLabels(Module m) {
        List<String> out = new ArrayList<>();
        for (KeybindSetting k : UtilityClient.moduleManager.getBoundKeys(m)) out.add(keyLabel(k));
        return out;
    }

    private String primaryKeyLabel(Module m) {
        for (Setting s : m.getSettings()) {
            if (s instanceof KeybindSetting k && "keybind".equals(k.getName())) return keyLabel(k);
        }
        return "none";
    }

    private String keyLabel(Setting s) {
        if (listeningKeybind == s) return "...";
        int key = s.getValue() instanceof Number n ? n.intValue() : -1;
        if (key == -1 || key == GLFW.GLFW_KEY_UNKNOWN) return "none";
        try {
            return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                    .getOrCreate(key).getDisplayName().getString().toUpperCase();
        } catch (Exception ignored) {
            return "KEY" + key;
        }
    }

    private List<String> kitNames() {
        if (kitCache == null) kitCache = KitManager.list();
        return kitCache;
    }

    // ---- list picker data ----

    private StringSetting findStringSetting(Module m, String name) {
        if (m == null || name == null) return null;
        for (Setting s : m.getSettings()) {
            if (s instanceof StringSetting ss && ss.getName().equals(name)) return ss;
        }
        return null;
    }

    private Set<String> parseCsv(String csv) {
        Set<String> out = new HashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private Set<String> enabledPickSet() {
        StringSetting ss = findStringSetting(selectedModule, listSettingName);
        return ss == null ? Collections.emptySet() : parseCsv(ss.get());
    }

    private List<String> pickIds() {
        if (listIds != null && listSettingName != null && listSettingName.equals(listCacheTag)) return listIds;
        List<String> out = new ArrayList<>();
        if ("hiddenModules".equals(listSettingName)) {
            for (Module m : UtilityClient.moduleManager.getVisibleModules()) out.add(m.getName());
        } else if ("potionFilter".equals(listSettingName)) {
            for (net.minecraft.world.item.alchemy.Potion potion : BuiltInRegistries.POTION) {
                Identifier id = BuiltInRegistries.POTION.getKey(potion);
                if (id != null) out.add(id.toString());
            }
        } else if ("blockFilter".equals(listSettingName)) {
            for (Block block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null) out.add(id.toString());
            }
        } else {
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (id != null) out.add(id.toString());
            }
        }
        Collections.sort(out);
        listIds = out;
        listCacheTag = listSettingName;
        return out;
    }

    private List<String> filteredPickIds() {
        String q = listSearchQuery.toLowerCase().trim();
        List<String> all = pickIds();
        if (q.isEmpty()) return all;
        List<String> out = new ArrayList<>();
        for (String id : all) if (id.toLowerCase().contains(q)) out.add(id);
        return out;
    }

    private void openPicker(String settingName) {
        listMode = true;
        listSettingName = settingName;
        listIds = null;
        listCacheTag = null;
        listSearchQuery = "";
        listSearchFocused = true;
        pickerScroll = 0;
    }

    private void closePicker() {
        listMode = false;
        listSettingName = null;
        listIds = null;
        listCacheTag = null;
        listSearchFocused = false;
    }

    private void togglePick(String id) {
        StringSetting ss = findStringSetting(selectedModule, listSettingName);
        if (ss == null) return;
        Set<String> current = new HashSet<>(parseCsv(ss.get()));
        if (!current.remove(id)) current.add(id);
        List<String> sorted = new ArrayList<>(current);
        Collections.sort(sorted);
        ss.set(String.join(",", sorted));
    }

    private static String trimNamespace(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    // ---- settings layout ----

    private int settingHeight(Setting s) {
        return (s instanceof IntSetting || s instanceof DoubleSetting) ? SLIDER_ROW_H : SETTING_ROW_H;
    }

    private int settingsContentHeight() {
        int total = 0;
        for (Setting s : settingsList()) total += settingHeight(s);
        return total;
    }

    /** Settings the column shows: all of them, or the HUD deep link's filter. */
    private List<Setting> settingsList() {
        if (selectedModule == null) return Collections.emptyList();
        if (settingsFilter.isBlank()) return selectedModule.getSettings();
        String q = settingsFilter.toLowerCase();
        List<Setting> out = new ArrayList<>();
        for (Setting s : selectedModule.getSettings()) {
            if (s.getName().toLowerCase().contains(q)) out.add(s);
        }
        return out;
    }

    private int settingsViewHeight() {
        return Math.max(0, settingsListBottom() - settingsListTop());
    }

    private int clampScroll(int value, int content, int view) {
        return Math.max(0, Math.min(Math.max(0, content - view), value));
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiRenderer.refreshTheme();
        ensurePosition();
        hoverHint = "";

        // Backdrop is drawn unscaled so it reaches the window edges whatever the
        // sheet scale ends up being.
        g.fill(0, 0, this.width, this.height, ThemePalette.current().style().equals("Glass") ? 0x66060606 : 0xC8060606);

        int mx = (int) Math.round(designX(mouseX));
        int my = (int) Math.round(designY(mouseY));

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(originX(), originY());
        pose.scale(uiScale, uiScale);
        try {
            drawSheet(g, mx, my);
        } finally {
            pose.popMatrix();
        }
    }

    private void drawSheet(GuiGraphicsExtractor g, int mx, int my) {
        // Shell: graphite body with a single hairline.
        UiRenderer.fill(g, x, y, W, H, UiRenderer.GRAPHITE);
        UiRenderer.outline(g, x, y, W, H, UiRenderer.BORDER);

        drawHeader(g, mx, my);
        drawPills(g, mx, my);

        // Three columns of equal tone; only the hairlines between them separate.
        UiRenderer.fill(g, x + 1, bodyTop(), W - 2, bodyBottom() - bodyTop(), UiRenderer.PANEL);
        UiRenderer.vLine(g, centreLeft(), bodyTop(), bodyBottom() - bodyTop(), UiRenderer.BORDER);
        UiRenderer.vLine(g, settingsLeft(), bodyTop(), bodyBottom() - bodyTop(), UiRenderer.BORDER);

        drawSidebar(g, mx, my);
        drawCentre(g, mx, my);
        drawSettings(g, mx, my);
        drawFooter(g, mx, my);
    }

    // ---- header ----

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        UiRenderer.fill(g, x + 1, headerTop(), W - 2, HEADER_H, UiRenderer.STRIP);
        UiRenderer.hLine(g, x + 1, headerTop() + HEADER_H - 1, W - 2, UiRenderer.BORDER);

        // Brand tile: a 12x12 accent square with the initial cut out of it.
        int lx = x + 12;
        int ly = headerTop() + 11;
        UiRenderer.fill(g, lx, ly, 12, 12, UiRenderer.ACCENT);
        g.text(this.font, "U", lx + 4, ly + 2, UiRenderer.GRAPHITE, false);
        g.text(this.font, "UtilityClient", lx + 20, headerTop() + 12, UiRenderer.TEXT, false);
        String version = "v1.0";
        g.text(this.font, version, lx + 20 + this.font.width("UtilityClient") + 8,
                headerTop() + 13, UiRenderer.TEXT_MUTED, false);

        drawSearch(g, mx, my);

        boolean closeHover = inRect(mx, my, closeX(), closeY(), closeW(), closeH());
        if (closeHover) UiRenderer.fill(g, closeX(), closeY(), closeW(), closeH(), 0x33E05A5A);
        UiRenderer.textCentre(g, this.font, "x", closeX() + closeW() / 2, headerTop() + 13,
                closeHover ? UiRenderer.DANGER : UiRenderer.TEXT_MUTED);
    }

    private void drawSearch(GuiGraphicsExtractor g, int mx, int my) {
        int sx = searchX();
        int sy = searchY();
        int sw = searchW();
        int sh = searchH();
        boolean hover = inRect(mx, my, sx, sy, sw, sh);

        UiRenderer.fill(g, sx, sy, sw, sh, searchFocused ? UiRenderer.INPUT : UiRenderer.RECESS);
        UiRenderer.outline(g, sx, sy, sw, sh,
                searchFocused ? UiRenderer.ACCENT : (hover ? UiRenderer.SWATCH : UiRenderer.BORDER));

        UiRenderer.textLeft(g, this.font, "/", sx + 7, sy + 5,
                searchFocused ? UiRenderer.ACCENT : UiRenderer.TEXT_MUTED);

        String hint = "search modules";
        String shown = searchQuery;
        int colour = UiRenderer.TEXT;
        if (shown.isEmpty() && !searchFocused) {
            shown = hint;
            colour = UiRenderer.TEXT_MUTED;
        }
        // Text is clipped to the box, so the caret is placed on the clipped width
        // rather than the full string: a long query used to push the marker out
        // through the right edge of the field.
        int textRoom = Math.max(0, sw - 34);
        int textW = Math.min(this.font.width(shown), textRoom);
        UiRenderer.textLeft(g, this.font,
                UiRenderer.trim(this.font, shown, textRoom), sx + 18, sy + 5, colour);

        // Blinking caret: a real block, hardware-timed, so a focused field is
        // visibly focused even before the first key lands.
        if (searchFocused && ((System.currentTimeMillis() / 450L) & 1L) == 0L) {
            UiRenderer.fill(g, sx + 18 + textW + 1, sy + 4, 1, sh - 8, UiRenderer.ACCENT);
        }

        if (isFiltering()) {
            String count = String.valueOf(visibleModules().size());
            UiRenderer.textRight(g, this.font, count, sx + sw - 8, sy + 5, UiRenderer.ACCENT_TEXT);
        }
    }

    // ---- pill strip ----

    private record Pill(int x, int width, Category category) {}

    private List<Pill> layoutPills() {
        List<Pill> out = new ArrayList<>();
        int cursor = x + PAD + 2;
        int limit = x + W - PAD;
        int gap = 6;
        // ALL first, then one pill per non-virtual category that actually holds modules.
        String allLabel = "ALL";
        int allW = this.font.width(allLabel) + 18;
        if (cursor + allW <= limit) {
            out.add(new Pill(cursor, allW, null));
            cursor += allW + gap;
        }
        for (Category cat : pillTabs()) {
            String label = UiRenderer.caps(shortLabel(cat));
            int w = this.font.width(label) + 18;
            if (cursor + w > limit) break;
            out.add(new Pill(cursor, w, cat));
            cursor += w + gap;
        }
        return out;
    }

    private void drawPills(GuiGraphicsExtractor g, int mx, int my) {
        int top = pillTop();
        UiRenderer.fill(g, x + 1, top, W - 2, PILL_H, UiRenderer.STRIP);
        UiRenderer.hLine(g, x + 1, top + PILL_H - 1, W - 2, UiRenderer.BORDER);

        // A live search outranks the pills: the list is every module on the client
        // regardless of the category selected, so showing one pill as active would
        // be a lie about what the list contains.
        boolean searching = isFiltering();

        int py = top + (PILL_H - 18) / 2;
        for (Pill pill : layoutPills()) {
            boolean active = !searching && (pill.category() == null
                    ? allCategories
                    : (!allCategories && pill.category() == selectedCategory));
            boolean hover = inRect(mx, my, pill.x(), py, pill.width(), 18);
            String label = pill.category() == null ? "ALL" : UiRenderer.caps(shortLabel(pill.category()));

            int fill = searching ? UiRenderer.RECESS
                    : (active ? UiRenderer.ACCENT : (hover ? UiRenderer.HOVER : UiRenderer.PANEL));
            int border = searching ? UiRenderer.BORDER_SOFT
                    : (active ? UiRenderer.ACCENT : (hover ? UiRenderer.SWATCH : UiRenderer.BORDER));
            int text = searching ? UiRenderer.TEXT_GHOST
                    : (active ? UiRenderer.GRAPHITE : (hover ? UiRenderer.TEXT : UiRenderer.TEXT_DIM));

            UiRenderer.fill(g, pill.x(), py, pill.width(), 18, fill);
            UiRenderer.outline(g, pill.x(), py, pill.width(), 18, border);
            UiRenderer.textCentre(g, this.font, label, pill.x() + pill.width() / 2, py + 5, text);

            if (hover) hoverHint = searching
                    ? "searching every module - clicking a pill clears the search"
                    : (pill.category() == null
                        ? "every module on the client"
                        : pill.category().getDisplayName() + " modules");
        }
    }

    // ---- sidebar ----

    private int sidebarListTop() { return contentTop() + 6; }

    /**
     * The preview panel starts under the last rail row and takes every pixel
     * below it.
     *
     * <p>With the categories gone there are five rows where there used to be a
     * dozen, so anchoring the panel to a fixed height would leave a dead band in
     * the middle of the rail. Letting it grow keeps the column solid.
     */
    private int previewTop() {
        return sidebarListTop() + sidebarTabs().size() * SIDEBAR_ROW_H + 8;
    }

    /**
     * True when the centre column is in this row's state.
     *
     * <p>Modules is the exception to the {@code !allCategories} rule the categories
     * follow: its whole point is that no category is selected, so it is the one row
     * that stays lit while the list is unfiltered.
     */
    private boolean railActive(Category cat) {
        if (cat == Category.MODULES) return selectedCategory == Category.MODULES;
        return !allCategories && cat == selectedCategory;
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        int rowH = SIDEBAR_ROW_H;
        int rowY = sidebarListTop();
        int index = 1;

        for (Category cat : sidebarTabs()) {
            boolean active = railActive(cat) && (selectedModule != null || !isFiltering());
            boolean hover = inRect(mx, my, x, rowY, SIDEBAR_W, rowH - 2);

            if (active) {
                UiRenderer.fill(g, x + 1, rowY, SIDEBAR_W - 1, rowH - 2, UiRenderer.ACCENT_WASH);
                UiRenderer.fill(g, x + 1, rowY, 2, rowH - 2, UiRenderer.ACCENT);
            } else if (hover) {
                UiRenderer.fill(g, x + 1, rowY, SIDEBAR_W - 1, rowH - 2, UiRenderer.HOVER);
            }

            String number = String.format("%02d", index);
            UiRenderer.textLeft(g, this.font, number, x + 9, textY(rowY, rowH - 2),
                    active ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_GHOST);

            int labelX = x + 26;
            int count = countFor(cat);
            String countText = String.valueOf(count);
            int room = SIDEBAR_W - 26 - 9 - this.font.width(countText) - 6;
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, shortLabel(cat), Math.max(20, room)),
                    labelX, textY(rowY, rowH - 2),
                    active ? UiRenderer.TEXT : UiRenderer.TEXT_DIM);
            UiRenderer.textRight(g, this.font, countText, x + SIDEBAR_W - 9, textY(rowY, rowH - 2),
                    active ? UiRenderer.ACCENT : UiRenderer.TEXT_GHOST);

            if (hover) {
                hoverHint = cat == Category.MODULES
                        ? "every module on the client"
                        : cat.getDisplayName();
            }
            rowY += rowH;
            index++;
        }

        drawPreview(g, mx, my);
    }

    // ---- enabled list geometry ----
    //
    // Shared by the painter and the hit test, and that sharing is the fix: the
    // previous build computed the row positions inline while drawing and had no hit
    // test at all, so the rows were inert. Two copies of a layout drift the moment
    // the header height changes - one copy cannot.

    private int previewLeft() { return x + 8; }

    private int previewWidth() { return SIDEBAR_W - 16; }

    private int previewHeight() { return bodyBottom() - 8 - previewTop(); }

    /** First row of the list, under the "ENABLED" caption and its rule. */
    private int previewRowTop() { return previewTop() + 24; }

    private int previewRowH() { return 11; }

    /** Rows that fit before the panel runs out of height. */
    private int previewFits() {
        int room = (previewTop() + previewHeight() - 6) - previewRowTop();
        return Math.max(0, room / previewRowH());
    }

    /** Rows actually painted: the listed modules, capped by the panel height. */
    private int previewDrawn() {
        if (previewHeight() < 40) return 0;
        return Math.min(enabledModules().size(), previewFits());
    }

    private int previewRowY(int index) { return previewRowTop() + index * previewRowH(); }

    /**
     * Index of the enabled row under the cursor, or -1.
     *
     * <p>Only painted rows count. The "+N more" tail is a caption, not a module, so
     * clicking it must do nothing rather than toggle whichever module happens to sit
     * at that arithmetic offset.
     */
    private int previewRowAt(double mx, double my) {
        if (previewHeight() < 40) return -1;
        int drawn = previewDrawn();
        if (drawn <= 0) return -1;
        if (!inRect(mx, my, previewLeft(), previewRowTop(), previewWidth(), drawn * previewRowH())) {
            return -1;
        }
        int index = (int) ((my - previewRowTop()) / previewRowH());
        return index >= 0 && index < drawn ? index : -1;
    }

    /** True when the point is anywhere inside the preview panel. */
    private boolean inPreviewPanel(double mx, double my) {
        return inRect(mx, my, previewLeft(), previewTop(), previewWidth(),
                Math.max(0, previewHeight()));
    }

    /**
     * Bottom-left HUD preview: the enabled list exactly as the overlay would stack
     * it, and the quickest way to turn something off without opening its category.
     */
    private void drawPreview(GuiGraphicsExtractor g, int mx, int my) {
        int top = previewTop();
        int left = previewLeft();
        int w = previewWidth();
        int h = previewHeight();
        if (h < 40) return;

        UiRenderer.panel(g, left, top, w, h, UiRenderer.RECESS);

        UiRenderer.textLeft(g, this.font, "ENABLED", left + 8, top + 7, UiRenderer.TEXT_MUTED);
        List<Module> on = enabledModules();
        UiRenderer.textRight(g, this.font, String.valueOf(on.size()), left + w - 8, top + 7,
                UiRenderer.ACCENT_TEXT);
        UiRenderer.hLine(g, left + 1, top + 19, w - 2, UiRenderer.BORDER_SOFT);

        int rowY = previewRowTop();
        int rowH = previewRowH();

        if (on.isEmpty()) {
            UiRenderer.textLeft(g, this.font, "nothing running", left + 8, rowY, UiRenderer.TEXT_GHOST);
            return;
        }

        int drawn = previewDrawn();
        for (int i = 0; i < drawn; i++) {
            int y2 = previewRowY(i);
            boolean hover = inRect(mx, my, left + 2, y2, w - 4, rowH);

            if (hover) {
                UiRenderer.fill(g, left + 2, y2, w - 4, rowH, HOVER_SCRIM);
            }

            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, on.get(i).getName(), w - 16),
                    left + 8, y2, hover ? UiRenderer.TEXT : UiRenderer.ACCENT_TEXT);

            if (hover) hoverHint = "left-click disables " + on.get(i).getName()
                    + ", right-click opens its settings";
        }
        if (drawn < on.size()) {
            int tail = previewRowY(drawn);
            if (tail + 8 < top + h) {
                UiRenderer.textLeft(g, this.font, "+" + (on.size() - drawn) + " more", left + 8, tail,
                        UiRenderer.TEXT_GHOST);
            }
        }
    }

    // ---- centre column ----

    private void drawCentre(GuiGraphicsExtractor g, int mx, int my) {
        int mode = centreMode();
        int left = centreLeft();
        int w = centreW();

        if (mode == MODE_LIST) {
            drawModuleList(g, left, w, mx, my);
        } else if (mode == MODE_FRIENDS) {
            drawFriends(g, left, w, mx, my);
        } else if (mode == MODE_KITS) {
            drawKits(g, left, w, mx, my);
        } else if (mode == MODE_PRESETS) {
            drawPresets(g, left, w, mx, my);
        } else {
            drawPicker(g, left, w, mx, my);
        }
    }

    private String centreTitle() {
        return switch (centreMode()) {
            case MODE_FRIENDS -> "FRIENDS";
            case MODE_KITS -> "KITS";
            case MODE_PRESETS -> "PRESETS";
            case MODE_PICKER -> UiRenderer.caps(listSettingName == null ? "filter" : listSettingName);
            default -> isFiltering() ? "RESULTS" : listTitle();
        };
    }

    /**
     * What the list under this header actually holds, in the header's own words.
     *
     * <p>The rail's filter rows are not categories, so their names do not read as
     * categories either: "Enabled" above a list of modules says nothing about the
     * filter, "ENABLED MODULES" says exactly what it is. A category keeps its short
     * name because the pills already taught the reader what "Combat" means here.
     */
    private String listTitle() {
        if (selectedCategory == Category.MODULES || allCategories) return "ALL MODULES";
        return switch (selectedCategory) {
            case ENABLED -> "ENABLED MODULES";
            case BINDS -> "BOUND MODULES";
            default -> UiRenderer.caps(shortLabel(selectedCategory));
        };
    }

    private void drawCentreHeader(GuiGraphicsExtractor g, int left, int w, String right) {
        UiRenderer.fill(g, left + 1, contentTop(), w - 1, 24, UiRenderer.PANEL);
        UiRenderer.textLeft(g, this.font, centreTitle(), left + 12, contentTop() + 8, UiRenderer.TEXT);
        if (right != null && !right.isEmpty()) {
            UiRenderer.textRight(g, this.font, right, left + w - 12, contentTop() + 8, UiRenderer.TEXT_MUTED);
        }
        UiRenderer.hLine(g, left + 1, contentTop() + 24, w - 1, UiRenderer.BORDER);
    }

    private void drawModuleList(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        List<Module> mods = visibleModules();
        drawCentreHeader(g, left, w, String.valueOf(mods.size()));

        // Column header.
        int colY = contentTop() + 24;
        UiRenderer.fill(g, left + 1, colY, w - 1, COLUMN_H, UiRenderer.STRIP);
        int ty = textY(colY, COLUMN_H);
        UiRenderer.textLeft(g, this.font, "ON", left + 12, ty, UiRenderer.TEXT_GHOST);
        UiRenderer.textLeft(g, this.font, "MODULE", left + 32, ty, UiRenderer.TEXT_GHOST);
        UiRenderer.textRight(g, this.font, "BIND", left + w - 12, ty, UiRenderer.TEXT_GHOST);
        UiRenderer.textRight(g, this.font, "CATEGORY", left + w - 78, ty, UiRenderer.TEXT_GHOST);
        UiRenderer.hLine(g, left, colY + COLUMN_H, w, UiRenderer.BORDER);

        int listTop = colY + COLUMN_H;
        int listBottom = bodyBottom();
        int view = listBottom - listTop;
        int content = mods.size() * MODULE_ROW_H;
        centreScroll = clampScroll(centreScroll, content, view);

        if (mods.isEmpty()) {
            UiRenderer.textLeft(g, this.font, "no modules here", left + 14, listTop + 14, UiRenderer.TEXT_GHOST);
        }

        g.enableScissor(left + 1, listTop, left + w, listBottom);
        try {
        for (int i = 0; i < mods.size(); i++) {
            int rowY = listTop + i * MODULE_ROW_H - centreScroll;
            if (rowY + MODULE_ROW_H <= listTop || rowY >= listBottom) continue;

            Module m = mods.get(i);
            boolean on = m.isEnabled();
            boolean selected = m == selectedModule;
            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, w, MODULE_ROW_H);

            if (selected) {
                UiRenderer.fill(g, left + 1, rowY, w - 1, MODULE_ROW_H, UiRenderer.ACCENT_WASH);
                UiRenderer.fill(g, left + 1, rowY, 2, MODULE_ROW_H, UiRenderer.ACCENT);
            }
            // The wash is drawn on top of the selection so a hovered row always reads
            // as hovered; the whole row is the target, so the whole row lights up.
            if (hover) {
                UiRenderer.fill(g, left + 1, rowY, w - 1, MODULE_ROW_H, HOVER_SCRIM);
            }
            UiRenderer.hLine(g, left + 1, rowY + MODULE_ROW_H - 1, w - 1, UiRenderer.BORDER_SOFT);

            int nameX = left + 32;
            int nameRoom = w - 32 - 78 - 12;
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, m.getName(), nameRoom),
                    nameX, textY(rowY, MODULE_ROW_H),
                    on ? UiRenderer.TEXT : UiRenderer.TEXT_DIM);

            // Category is only interesting when the list spans several of them.
            if (isFiltering() || allCategories || selectedCategory == Category.MODULES
                    || selectedCategory == Category.ENABLED
                    || selectedCategory == Category.BINDS) {
                UiRenderer.textRight(g, this.font, m.getCategory().getDisplayName(),
                        left + w - 78, textY(rowY, MODULE_ROW_H), UiRenderer.TEXT_MUTED);
            }

            if (m instanceof ApexModule apex && !apex.isImplemented()) {
                UiRenderer.checkbox(g, left + 12, rowY + 8, false, false);
                UiRenderer.textRight(g, this.font, "label", left + w - 12,
                        textY(rowY, MODULE_ROW_H), UiRenderer.TEXT_GHOST);
            } else if (selectedCategory == Category.BINDS) {
                String keys = String.join(" + ", allKeyLabels(m));
                UiRenderer.textRight(g, this.font,
                        UiRenderer.trim(this.font, keys, 90),
                        left + w - 12, textY(rowY, MODULE_ROW_H), UiRenderer.ACCENT_TEXT);
            } else {
                UiRenderer.checkbox(g, left + 12, rowY + 8, on, hover);
                String key = primaryKeyLabel(m);
                if (!key.equals("none")) {
                    UiRenderer.textRight(g, this.font, key, left + w - 12,
                            textY(rowY, MODULE_ROW_H), on ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
                }
            }

            if (hover) hoverHint = m.getDescription();
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + w - 4, listTop, view, centreScroll, content);
    }

    // ---- friends ----

    private int friendsInputY() { return contentTop() + 30; }

    private void drawFriends(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        List<String> entries = FriendsManager.entries();
        drawCentreHeader(g, left, w, String.valueOf(entries.size()));

        int ix = left + 12;
        int iw = w - 12 - 12 - 42;
        int iy = friendsInputY();
        int ih = 18;
        boolean typing = textInputMode == 1;
        UiRenderer.fill(g, ix, iy, iw, ih, UiRenderer.INPUT);
        UiRenderer.outline(g, ix, iy, iw, ih, typing ? UiRenderer.ACCENT : UiRenderer.BORDER);
        String shown = friendsInput.isEmpty() ? (typing ? "_" : "name or uuid") : friendsInput + (typing ? "_" : "");
        UiRenderer.textLeft(g, this.font,
                UiRenderer.trim(this.font, shown, iw - 14), ix + 7, iy + 5,
                friendsInput.isEmpty() && !typing ? UiRenderer.TEXT_GHOST : UiRenderer.TEXT);

        int ax = ix + iw + 6;
        boolean addHover = inRect(mx, my, ax, iy, 36, ih);
        UiRenderer.chip(g, this.font, "ADD", ax, iy, 36, ih, false, addHover);
        if (addHover) hoverHint = "add a player by name or uuid";

        int listTop = iy + ih + 8;
        int listBottom = bodyBottom();
        int view = listBottom - listTop;
        int rowH = 20;
        int content = entries.size() * rowH;
        friendsScroll = clampScroll(friendsScroll, content, view);

        UiRenderer.hLine(g, left + 1, listTop - 4, w - 1, UiRenderer.BORDER_SOFT);

        if (entries.isEmpty()) {
            UiRenderer.textLeft(g, this.font, "nobody listed yet", left + 14, listTop + 10, UiRenderer.TEXT_GHOST);
        }

        g.enableScissor(left, listTop, left + w, listBottom);
        try {
        for (int i = 0; i < entries.size(); i++) {
            int rowY = listTop + i * rowH - friendsScroll;
            if (rowY + rowH <= listTop || rowY >= listBottom) continue;

            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, w, rowH);
            if (hover) UiRenderer.fill(g, left + 1, rowY, w - 1, rowH, UiRenderer.HOVER);

            UiRenderer.fill(g, left + 14, rowY + 8, 4, 4, FriendsManager.FRIEND_COLOR);
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, entries.get(i), w - 70),
                    left + 26, textY(rowY, rowH), UiRenderer.TEXT);

            boolean delHover = hover && inRect(mx, my, left + w - 26, rowY, 18, rowH);
            UiRenderer.textCentre(g, this.font, "x", left + w - 17, textY(rowY, rowH),
                    delHover ? UiRenderer.DANGER : UiRenderer.TEXT_GHOST);

            if (hover) hoverHint = "left-click the x to remove " + entries.get(i);
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + w - 4, listTop, view, friendsScroll, content);
    }

    // ---- kits ----

    private int kitInputY() { return contentTop() + 30; }

    private int kitButtonY() { return kitInputY() + 18 + 4; }

    /** Width of one preview panel: the grid plus the 12 px gutter on either side. */
    private int kitGridW() { return KIT_COLS * KIT_SLOT + (KIT_COLS - 1) * KIT_GAP; }

    private int kitGridH() { return KIT_ROWS * KIT_SLOT + (KIT_ROWS - 1) * KIT_GAP; }

    private int kitGearW() { return KIT_GEAR_SLOTS * KIT_SLOT + (KIT_GEAR_SLOTS - 1) * KIT_GAP; }

    /** Panel under the action row holding the item grid; 20 px caption plus the rows. */
    private int kitPreviewTop() { return kitButtonY() + 40 + 8; }

    private int kitPreviewH() { return 20 + kitGridH() + 6 + KIT_SLOT + 8; }

    private int kitListTop() { return kitPreviewTop() + kitPreviewH() + 8; }

    private int kitGridTop() { return kitPreviewTop() + 20; }

    /**
     * Preview cell to player inventory slot.
     *
     * <p>Cell 0 is the top-left of the picture, and the vanilla inventory screen
     * puts the 27 main slots above the hotbar; the player reads the picture that
     * way round, so the mapping is not the identity.
     */
    private static int inventorySlotFor(int cell) {
        return cell < 27 ? cell + 9 : cell - 27;
    }

    private void refreshKitPreview(String name) {
        previewStacks = KitManager.read(name);
        previewName = name == null ? "" : name;
    }

    private void drawKits(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        List<String> kits = kitNames();
        drawCentreHeader(g, left, w, String.valueOf(kits.size()));

        int ix = left + 12;
        int iw = w - 24;
        int iy = kitInputY();
        int ih = 18;
        boolean typing = textInputMode == 2;
        UiRenderer.fill(g, ix, iy, iw, ih, UiRenderer.INPUT);
        UiRenderer.outline(g, ix, iy, iw, ih, typing ? UiRenderer.ACCENT : UiRenderer.BORDER);
        String shown = kitInput.isEmpty() ? (typing ? "_" : "kit name") : kitInput + (typing ? "_" : "");
        UiRenderer.textLeft(g, this.font,
                UiRenderer.trim(this.font, shown, iw - 14), ix + 7, iy + 5,
                kitInput.isEmpty() && !typing ? UiRenderer.TEXT_GHOST : UiRenderer.TEXT);

        int by = kitButtonY();
        int bw = (iw - 4) / 2;
        String[] labels = {"SAVE", "LOAD", "SHARE", "IMPORT"};
        String[] hints = {
                "save the current inventory under this name",
                "put the selected kit back into the inventory",
                "copy a share code for the selected kit to the clipboard",
                "import the clipboard kit code or JSON under the name above"
        };
        for (int i = 0; i < labels.length; i++) {
            int bx = ix + (i % 2) * (bw + 4);
            int buttonY = by + (i / 2) * 22;
            boolean hover = inRect(mx, my, bx, buttonY, bw, 18);
            UiRenderer.chip(g, this.font, labels[i], bx, buttonY, bw, 18, false, hover);
            if (hover) hoverHint = hints[i];
        }

        g.enableScissor(left, contentTop() + 26, left + w, bodyBottom());
        try { drawKitPreview(g, left, w, mx, my); }
        finally { g.disableScissor(); }

        int listTop = kitListTop();
        int listBottom = bodyBottom();
        int view = listBottom - listTop;
        int rowH = 20;
        int content = kits.size() * rowH;
        kitScroll = clampScroll(kitScroll, content, view);

        UiRenderer.hLine(g, left + 1, listTop - 4, w - 1, UiRenderer.BORDER_SOFT);

        if (kits.isEmpty()) {
            UiRenderer.textLeft(g, this.font, "no kits saved", left + 14, listTop + 10, UiRenderer.TEXT_GHOST);
        }

        g.enableScissor(left, Math.min(listTop, listBottom), left + w, listBottom);
        try {
        for (int i = 0; i < kits.size(); i++) {
            int rowY = listTop + i * rowH - kitScroll;
            if (rowY + rowH <= listTop || rowY >= listBottom) continue;

            String name = kits.get(i);
            boolean selected = name.equals(previewName);
            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, w, rowH);
            if (selected) UiRenderer.fill(g, left + 1, rowY, w - 1, rowH, UiRenderer.ACCENT_WASH);
            else if (hover) UiRenderer.fill(g, left + 1, rowY, w - 1, rowH, UiRenderer.HOVER);

            UiRenderer.fill(g, left + 1, rowY, 2, rowH,
                    selected ? UiRenderer.ACCENT : (hover ? UiRenderer.ACCENT_EDGE : UiRenderer.BORDER));
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, name, w - 116), left + 14, textY(rowY, rowH),
                    selected ? UiRenderer.TEXT : UiRenderer.TEXT_DIM);

            boolean loadHover = hover && inRect(mx, my, left + w - 96, rowY, 32, rowH);
            UiRenderer.textRight(g, this.font, "load", left + w - 64, textY(rowY, rowH),
                    loadHover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_GHOST);

            boolean shareHover = hover && inRect(mx, my, left + w - 62, rowY, 36, rowH);
            UiRenderer.textRight(g, this.font, "share", left + w - 30, textY(rowY, rowH),
                    shareHover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_GHOST);

            boolean delHover = hover && inRect(mx, my, left + w - 26, rowY, 18, rowH);
            UiRenderer.textCentre(g, this.font, "x", left + w - 17, textY(rowY, rowH),
                    delHover ? UiRenderer.DANGER : UiRenderer.TEXT_GHOST);

            if (hover) hoverHint = "click " + name + " to preview it, load to equip it";
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + w - 4, listTop, view, kitScroll, content);
    }

    /**
     * The 9x4 inventory picture plus the five slots that are not in the grid.
     *
     * <p>Rendered with {@code g.item}, which takes the pushed matrix, so the
     * icons scale and move with the sheet exactly like the text around them.
     * Empty cells draw a recessed placeholder with a dim pip, because a blank
     * region reads as a broken panel rather than an empty inventory.
     */
    private void drawKitPreview(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        int top = kitPreviewTop();
        int panelW = Math.max(kitGridW() + 24, kitGearW() + 24);
        int panelX = left + (w - panelW) / 2;

        UiRenderer.panel(g, panelX, top, panelW, kitPreviewH(), UiRenderer.RECESS);

        String caption = previewName.isEmpty() ? "no kit selected" : UiRenderer.caps(previewName);
        int captionX = panelX + 12;
        int captionRoom = panelW - 24 - this.font.width("PREVIEW") - 6;
        UiRenderer.textLeft(g, this.font, "PREVIEW", captionX, top + 7, UiRenderer.TEXT_GHOST);
        UiRenderer.textLeft(g, this.font,
                UiRenderer.trim(this.font, caption, Math.max(20, captionRoom)),
                captionX + this.font.width("PREVIEW") + 6, top + 7,
                previewName.isEmpty() ? UiRenderer.TEXT_GHOST : UiRenderer.TEXT_MUTED);

        int gx = left + (w - kitGridW()) / 2;
        int gy = kitGridTop();
        for (int cell = 0; cell < KIT_COLS * KIT_ROWS; cell++) {
            int col = cell % KIT_COLS;
            int row = cell / KIT_COLS;
            drawKitSlot(g, previewStacks[inventorySlotFor(cell)],
                    gx + col * (KIT_SLOT + KIT_GAP),
                    gy + row * (KIT_SLOT + KIT_GAP), mx, my);
        }

        int ax = left + (w - kitGearW()) / 2;
        int ay = gy + kitGridH() + 6;
        for (int i = 0; i < KIT_GEAR_SLOTS; i++) {
            int slot = KIT_GEAR_FIRST_SLOT + i;
            if (slot >= KitManager.INVENTORY_SLOTS) break;
            drawKitSlot(g, previewStacks[slot], ax + i * (KIT_SLOT + KIT_GAP), ay, mx, my);
        }
    }

    private void drawKitSlot(GuiGraphicsExtractor g, ItemStack stack, int sx, int sy, int mx, int my) {
        boolean hover = my >= contentTop() + 26 && my < bodyBottom()
                && inRect(mx, my, sx, sy, KIT_SLOT, KIT_SLOT);
        UiRenderer.fill(g, sx, sy, KIT_SLOT, KIT_SLOT, hover ? UiRenderer.HOVER : UiRenderer.INPUT);
        UiRenderer.outline(g, sx, sy, KIT_SLOT, KIT_SLOT,
                hover ? UiRenderer.ACCENT_EDGE : UiRenderer.BORDER_SOFT);

        if (stack == null || stack.isEmpty()) {
            UiRenderer.fill(g, sx + 8, sy + 8, 2, 2, UiRenderer.TEXT_GHOST);
            return;
        }
        g.item(stack, sx + 1, sy + 1);
        g.itemDecorations(this.font, stack, sx + 1, sy + 1);
        if (hover) hoverHint = stack.getHoverName().getString() + " x" + stack.getCount();
    }

    // ---- presets ----

    private void drawPresets(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        List<String> presets = ConfigManager.listPresets();
        drawCentreHeader(g, left, w, "click to compare");

        int listTop = contentTop() + 26;
        int listBottom = bodyBottom();
        int view = listBottom - listTop;
        int rowH = 22;
        int total = presets.size() + 1;
        int content = total * rowH;
        presetScroll = clampScroll(presetScroll, content, view);

        g.enableScissor(left, listTop, left + w, listBottom);
        try {
        for (int i = 0; i < total; i++) {
            int rowY = listTop + i * rowH - presetScroll;
            if (rowY + rowH <= listTop || rowY >= listBottom) continue;

            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, w, rowH);
            if (hover) UiRenderer.fill(g, left + 1, rowY, w - 1, rowH, UiRenderer.HOVER);

            if (i == 0) {
                UiRenderer.fill(g, left + 1, rowY, 2, rowH, UiRenderer.ACCENT);
                String label = "SAVE AS '" + (searchQuery.isBlank() ? "quick" : searchQuery.trim().toUpperCase()) + "'";
                UiRenderer.textLeft(g, this.font,
                        UiRenderer.trim(this.font, label, w - 24),
                        left + 14, textY(rowY, rowH), UiRenderer.ACCENT_TEXT);
                if (hover) hoverHint = "store the current config under the search text";
                continue;
            }

            String name = presets.get(i - 1);
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, name, w - 90), left + 14, textY(rowY, rowH), UiRenderer.TEXT);

            String meta = ConfigManager.describePreset(name);
            if (!meta.isEmpty()) {
                UiRenderer.textRight(g, this.font, meta, left + w - 34, textY(rowY, rowH), UiRenderer.TEXT_MUTED);
            }
            boolean delHover = hover && inRect(mx, my, left + w - 26, rowY, 18, rowH);
            UiRenderer.textCentre(g, this.font, "x", left + w - 17, textY(rowY, rowH),
                    delHover ? UiRenderer.DANGER : UiRenderer.TEXT_GHOST);
            if (hover) hoverHint = "compare preset " + name + ", or press x to delete";
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + w - 4, listTop, view, presetScroll, content);
    }

    // ---- list picker ----

    private void drawPicker(GuiGraphicsExtractor g, int left, int w, int mx, int my) {
        List<String> ids = filteredPickIds();
        Set<String> enabled = enabledPickSet();
        drawCentreHeader(g, left, w, enabled.size() + " on");

        int ix = left + 12;
        int iw = w - 24;
        int iy = contentTop() + 30;
        int ih = 18;
        UiRenderer.fill(g, ix, iy, iw, ih, UiRenderer.INPUT);
        UiRenderer.outline(g, ix, iy, iw, ih,
                listSearchFocused ? UiRenderer.ACCENT : UiRenderer.BORDER);
        String shown = listSearchQuery.isEmpty()
                ? (listSearchFocused ? "_" : "filter entries")
                : listSearchQuery + (listSearchFocused ? "_" : "");
        UiRenderer.textLeft(g, this.font,
                UiRenderer.trim(this.font, shown, iw - 14), ix + 7, iy + 5,
                listSearchQuery.isEmpty() && !listSearchFocused ? UiRenderer.TEXT_GHOST : UiRenderer.TEXT);

        int listTop = iy + ih + 8;
        int listBottom = bodyBottom();
        int view = listBottom - listTop;
        int rowH = 18;
        int content = ids.size() * rowH;
        pickerScroll = clampScroll(pickerScroll, content, view);

        UiRenderer.hLine(g, left + 1, listTop - 4, w - 1, UiRenderer.BORDER_SOFT);

        if (ids.isEmpty()) {
            UiRenderer.textLeft(g, this.font, "nothing matches", left + 14, listTop + 10, UiRenderer.TEXT_GHOST);
        }

        g.enableScissor(left + 1, listTop, left + w, listBottom);
        try {
        for (int i = 0; i < ids.size(); i++) {
            int rowY = listTop + i * rowH - pickerScroll;
            if (rowY + rowH <= listTop || rowY >= listBottom) continue;

            String id = ids.get(i);
            boolean on = enabled.contains(id);
            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, w, rowH);
            if (hover) UiRenderer.fill(g, left + 1, rowY, w - 1, rowH, UiRenderer.HOVER);

            UiRenderer.checkbox(g, left + 12, rowY + 4, on, hover);
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, trimNamespace(id), w - 42),
                    left + 30, textY(rowY, rowH),
                    on ? UiRenderer.TEXT : UiRenderer.TEXT_DIM);
            if (hover) hoverHint = (on ? "remove " : "add ") + id;
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + w - 4, listTop, view, pickerScroll, content);
    }

    // ---- settings column ----

    private void drawSettings(GuiGraphicsExtractor g, int mx, int my) {
        int left = settingsLeft();

        // Header strip.
        UiRenderer.fill(g, left + 1, contentTop(), SETTINGS_W - 1, 24, UiRenderer.PANEL);
        String title = selectedModule == null
                ? "SETTINGS"
                : UiRenderer.trim(this.font, selectedModule.getName(), SETTINGS_W - 80);
        UiRenderer.textLeft(g, this.font, title, left + 12, contentTop() + 8,
                selectedModule == null ? UiRenderer.TEXT_MUTED : UiRenderer.TEXT);
        if (selectedModule != null) {
            boolean on = selectedModule.isEnabled();
            UiRenderer.checkbox(g, left + SETTINGS_W - 52, contentTop() + 7, on,
                    inRect(mx, my, left + SETTINGS_W - 52, contentTop() + 7, 10, 10));
            UiRenderer.textRight(g, this.font, on ? "ON" : "OFF", left + SETTINGS_W - 60,
                    contentTop() + 8, on ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
        }
        UiRenderer.hLine(g, left + 1, contentTop() + 24, SETTINGS_W - 1, UiRenderer.BORDER);

        if (selectedModule == null) {
            UiRenderer.textLeft(g, this.font, "right-click a module", left + 12,
                    settingsListTop() + 2, UiRenderer.TEXT_GHOST);
            drawSettingsFooter(g, left, mx, my, false);
            return;
        }

        // A colour picker takes the whole body: three stacked bars need the room.
        if (colorPickerTarget != null) {
            drawColorPicker(g, left, mx, my);
            drawSettingsFooter(g, left, mx, my, true);
            return;
        }

        List<Setting> settings = settingsList();
        int listTop = settingsListTop();
        int view = settingsViewHeight();
        int content = settingsContentHeight();
        settingsScroll = clampScroll(settingsScroll, content, view);
        int listBottom = settingsListBottom();

        if (settings.isEmpty()) {
            UiRenderer.textLeft(g, this.font,
                    settingsFilter.isBlank() ? "no settings" : "no setting matches that widget",
                    left + 12, listTop + 4, UiRenderer.TEXT_GHOST);
        }

        g.enableScissor(left + 1, listTop, left + SETTINGS_W, listBottom);
        try {
        int cursor = listTop - settingsScroll;
        for (int i = 0; i < settings.size(); i++) {
            Setting s = settings.get(i);
            int rowH = settingHeight(s);
            int rowY = cursor;
            cursor += rowH;
            if (rowY + rowH <= listTop || rowY >= listBottom) continue;

            boolean hover = my >= listTop && my < listBottom && inRect(mx, my, left, rowY, SETTINGS_W, rowH - 1);
            boolean isInput = numericInputTarget == s;

            if (isInput) UiRenderer.fill(g, left + 1, rowY, SETTINGS_W - 1, rowH - 1, UiRenderer.RECESS);
            else if (hover) UiRenderer.fill(g, left + 1, rowY, SETTINGS_W - 1, rowH - 1, UiRenderer.HOVER);

            int labelY = rowY + (rowH >= SLIDER_ROW_H ? 4 : (rowH - 8) / 2);
            UiRenderer.textLeft(g, this.font,
                    UiRenderer.trim(this.font, s.getDisplayName(), SETTINGS_W - 120),
                    left + 12, labelY, UiRenderer.TEXT_DIM);

            drawSettingWidget(g, s, left, rowY, rowH, hover, isInput);

            if (hover) hoverHint = s.getDescription();
        }

        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, left + SETTINGS_W - 4, listTop, view, settingsScroll, content);
        drawSettingsFooter(g, left, mx, my, true);
    }

    private void drawSettingWidget(GuiGraphicsExtractor g, Setting s, int left, int rowY, int rowH,
                                   boolean hover, boolean isInput) {
        int rightEdge = left + SETTINGS_W - 14;

        if (s instanceof BoolSetting b) {
            UiRenderer.checkbox(g, rightEdge - 10, rowY + (rowH - 10) / 2, b.get(), hover);
            return;
        }

        if (s instanceof ColorSetting c) {
            UiRenderer.swatch(g, rightEdge - 40, rowY + (rowH - 10) / 2, 14, 10, c.get());
            UiRenderer.textRight(g, this.font, "edit", rightEdge - 46, rowY + (rowH - 8) / 2,
                    hover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
            return;
        }

        if (s instanceof KeybindSetting k) {
            String label = keyLabel(s) + " " + k.activationLabel();
            boolean conflict = selectedModule != null
                    && (!UtilityClient.moduleManager.getKeyConflicts(k.getKey(), selectedModule).isEmpty()
                    || UtilityClient.moduleManager.hasInternalKeyConflict(selectedModule, k));
            int colour = listeningKeybind == s ? UiRenderer.ACCENT
                    : (conflict ? UiRenderer.DANGER : UiRenderer.TEXT);
            int bw = UiRenderer.badgeWidth(this.font, label);
            UiRenderer.badge(g, this.font, label, rightEdge - bw, rowY + (rowH - 12) / 2,
                    colour, UiRenderer.INPUT, listeningKeybind == s ? UiRenderer.ACCENT : UiRenderer.BORDER);
            return;
        }

        if (s instanceof IntSetting is) {
            String value = isInput ? numericInputValue + "_" : String.valueOf(is.get());
            UiRenderer.textRight(g, this.font, value, rightEdge, rowY + 4,
                    isInput ? UiRenderer.ACCENT : UiRenderer.TEXT);
            double range = is.getMax() - is.getMin();
            double f = range > 0 ? (is.get() - is.getMin()) / range : 0;
            UiRenderer.slider(g, left + 12, rowY + 16, SETTINGS_W - 26, f, hover || isInput);
            return;
        }

        if (s instanceof DoubleSetting ds) {
            String value = isInput ? numericInputValue + "_" : String.format("%.2f", ds.get());
            UiRenderer.textRight(g, this.font, value, rightEdge, rowY + 4,
                    isInput ? UiRenderer.ACCENT : UiRenderer.TEXT);
            double range = ds.getMax() - ds.getMin();
            double f = range > 0 ? (ds.get() - ds.getMin()) / range : 0;
            UiRenderer.slider(g, left + 12, rowY + 16, SETTINGS_W - 26, f, hover || isInput);
            return;
        }

        if (s instanceof com.baggute.cheatclient.settings.ChoiceSetting choice) {
            UiRenderer.textRight(g, this.font, "< " + choice.get() + " >", rightEdge, rowY + (rowH - 8) / 2,
                    hover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_DIM);
            return;
        }
        if (s instanceof StringSetting str) {
            String name = str.getName();
            if (name.equals("editPositions")) {
                UiRenderer.textRight(g, this.font, "open", rightEdge, rowY + (rowH - 8) / 2,
                        hover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
                return;
            }
            boolean isList = name.equals("entityFilter") || name.equals("blockFilter")
                    || name.equals("hiddenModules") || name.equals("potionFilter");
            String value;
            if (isList) {
                int count = 0;
                for (String part : str.get().split(",")) if (!part.trim().isEmpty()) count++;
                value = count + " set";
            } else {
                String raw = stringInputTarget == str ? stringInputValue + "_" : str.get();
                value = raw.length() > 18 ? raw.substring(0, 15) + "..." : raw;
            }
            UiRenderer.textRight(g, this.font, value, rightEdge, rowY + (rowH - 8) / 2,
                    isList && hover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
        }
    }

    private void drawSettingsFooter(GuiGraphicsExtractor g, int left, int mx, int my, boolean active) {
        int top = bodyBottom() - 28;
        UiRenderer.hLine(g, left + 1, top, SETTINGS_W - 1, UiRenderer.BORDER);

        int bw = 62;
        int resetX = left + SETTINGS_W - 12 - bw;
        int bindX = resetX - 8 - bw;

        if (active) {
            boolean bindHover = inRect(mx, my, bindX, top + 5, bw, 18);
            UiRenderer.chip(g, this.font, "BIND KEY", bindX, top + 5, bw, 18, listeningKeybind != null, bindHover);
            if (bindHover) hoverHint = "click, then press a key to bind this module";

            boolean resetHover = inRect(mx, my, resetX, top + 5, bw, 18);
            UiRenderer.chip(g, this.font, "RESET", resetX, top + 5, bw, 18, false, resetHover);
            if (resetHover) hoverHint = "restore every setting on this module to its default";

            if (!settingsFilter.isBlank()) {
                UiRenderer.textLeft(g, this.font,
                        UiRenderer.trim(this.font, "filter: " + settingsFilter,
                                Math.max(30, bindX - (left + 12) - 8)),
                        left + 12, top + 10, UiRenderer.ACCENT_TEXT);
            }
        } else {
            UiRenderer.textLeft(g, this.font, "nothing selected", left + 12, top + 10, UiRenderer.TEXT_GHOST);
        }
    }

    // ---- colour picker ----

    private int pickerSquareTop() { return settingsListTop() + 8; }
    private int pickerSquareLeft() { return settingsLeft() + 12; }
    private int pickerSquareW() { return SETTINGS_W - 24; }
    private int pickerSquareH() { return 118; }
    private int pickerHueTop() { return pickerSquareTop() + pickerSquareH() + 10; }
    private int pickerHueH() { return 10; }
    private int pickerAlphaTop() { return pickerHueTop() + pickerHueH() + 8; }
    private int pickerAlphaH() { return 8; }

    private void drawColorPicker(GuiGraphicsExtractor g, int left, int mx, int my) {
        int sx = pickerSquareLeft();
        int sy = pickerSquareTop();
        int sw = pickerSquareW();
        int sh = pickerSquareH();

        int hueRgb = 0xFF000000 | (Color.HSBtoRGB(pickerHue, 1f, 1f) & 0xFFFFFF);
        UiRenderer.fill(g, sx, sy, sw, sh, hueRgb);
        // White ramp left to right, then black ramp top to bottom: the standard
        // saturation/value square, built from two one-pixel gradient sweeps.
        for (int i = 0; i < sw; i++) {
            int a = 255 - (255 * i / Math.max(1, sw - 1));
            UiRenderer.fill(g, sx + i, sy, 1, sh, (a << 24) | 0xFFFFFF);
        }
        for (int j = 0; j < sh; j++) {
            int a = 255 * j / Math.max(1, sh - 1);
            UiRenderer.fill(g, sx, sy + j, sw, 1, (a << 24));
        }
        UiRenderer.outline(g, sx - 1, sy - 1, sw + 2, sh + 2, UiRenderer.BORDER);

        int cx = sx + (int) (pickerSat * (sw - 1));
        int cy = sy + (int) ((1f - pickerVal) * (sh - 1));
        UiRenderer.outline(g, cx - 3, cy - 3, 7, 7, 0xFFFFFFFF);
        UiRenderer.outline(g, cx - 4, cy - 4, 9, 9, 0xFF000000);

        int hy = pickerHueTop();
        for (int i = 0; i < sw; i++) {
            int rgb = Color.HSBtoRGB((float) i / Math.max(1, sw - 1), 1f, 1f) & 0xFFFFFF;
            UiRenderer.fill(g, sx + i, hy, 1, pickerHueH(), 0xFF000000 | rgb);
        }
        int hx = sx + (int) (pickerHue * (sw - 1));
        UiRenderer.fill(g, hx - 1, hy - 2, 3, pickerHueH() + 4, 0xFFFFFFFF);

        int ay = pickerAlphaTop();
        for (int i = 0; i < sw; i++) {
            UiRenderer.fill(g, sx + i, ay, 1, pickerAlphaH(), (pickerAlpha << 24) | 0xFFFFFF);
        }
        UiRenderer.outline(g, sx - 1, ay - 1, sw + 2, pickerAlphaH() + 2, UiRenderer.BORDER);
        int ax = sx + (int) ((pickerAlpha / 255f) * (sw - 1));
        UiRenderer.fill(g, ax - 1, ay - 2, 3, pickerAlphaH() + 4, UiRenderer.ACCENT_TEXT);

        int rgb = Color.HSBtoRGB(pickerHue, pickerSat, pickerVal) & 0xFFFFFF;
        UiRenderer.textLeft(g, this.font, String.format("#%06X", rgb), sx, ay + pickerAlphaH() + 8,
                UiRenderer.TEXT_DIM);
        String alpha = "a " + pickerAlpha;
        UiRenderer.textRight(g, this.font, alpha, sx + sw, ay + pickerAlphaH() + 8, UiRenderer.TEXT_MUTED);

        boolean backHover = inRect(mx, my, left + SETTINGS_W - 52, contentTop() + 6, 40, 14);
        UiRenderer.textRight(g, this.font, "done", left + SETTINGS_W - 12, contentTop() + 8,
                backHover ? UiRenderer.ACCENT_TEXT : UiRenderer.TEXT_MUTED);
        if (backHover) hoverHint = "close the colour picker";
    }

    private void applyPicker(double mx, double my) {
        if (!(colorPickerTarget instanceof ColorSetting cs)) return;
        int sx = pickerSquareLeft();
        int sw = pickerSquareW();
        if (pickerDragMode == 1) {
            double t = (mx - sx) / Math.max(1, sw - 1);
            pickerSat = (float) Math.max(0.0, Math.min(1.0, t));
            double v = 1.0 - (my - pickerSquareTop()) / Math.max(1, pickerSquareH() - 1);
            pickerVal = (float) Math.max(0.0, Math.min(1.0, v));
        } else if (pickerDragMode == 2) {
            double t = (mx - sx) / Math.max(1, sw - 1);
            pickerHue = (float) Math.max(0.0, Math.min(1.0, t));
        } else if (pickerDragMode == 3) {
            double t = (mx - sx) / Math.max(1, sw - 1);
            pickerAlpha = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * 255);
        } else {
            return;
        }
        int rgb = Color.HSBtoRGB(pickerHue, pickerSat, pickerVal) & 0xFFFFFF;
        cs.set((pickerAlpha << 24) | rgb);
    }

    // ---- footer ----

    private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
        int top = footerTop();
        UiRenderer.fill(g, x + 1, top, W - 2, FOOTER_H, UiRenderer.STRIP);
        UiRenderer.hLine(g, x + 1, top, W - 2, UiRenderer.BORDER);

        int ty = top + 9;

        // Right-hand actions, laid out from the right edge inwards.
        int cursor = x + W - PAD;
        cursor = footerChip(g, "IMPORT", cursor, top, mx, my, centreMode() == MODE_KITS
                ? "import the clipboard kit under the name in the Kit tab"
                : "import a config from the clipboard, right-click for share.txt");
        cursor -= 6;
        cursor = footerChip(g, "EXPORT", cursor, top, mx, my, "copy a config share code, right-click writes share.txt");
        cursor -= 6;
        cursor = footerChip(g, "PRESETS", cursor, top, mx, my, "save, load and delete named configs") - 6;
        cursor = footerChip(g, "THEMES", cursor, top, mx, my, "choose a style, palette and custom colors") - 6;
        cursor = footerChip(g, "MODULES", cursor, top, mx, my, "open the custom module JAR folder; restart to load additions");

        String hint = hoverHint;
        if (hint.isEmpty()) {
            hint = switch (centreMode()) {
                case MODE_FRIENDS -> "type a name and press add";
                case MODE_KITS -> "click a kit to preview it, load equips it";
                case MODE_PRESETS -> "left-click compares a preset, x deletes it";
                case MODE_PICKER -> "left-click toggles an entry, scroll for more";
                default -> "left-click toggles, right-click opens settings, type to search";
            };
        }
        int room = cursor - (x + PAD) - 24;
        UiRenderer.textLeft(g, this.font, UiRenderer.trim(this.font, hint, Math.max(40, room)),
                x + PAD, ty, UiRenderer.TEXT_MUTED);
    }

    /** Draws a footer chip ending at {@code rightX}; returns the new cursor. */
    private int footerChip(GuiGraphicsExtractor g, String label, int rightX, int top,
                           int mx, int my, String hint) {
        int w = UiRenderer.chipWidth(this.font, label);
        int cx = rightX - w;
        int cy = top + 5;
        boolean hover = inRect(mx, my, cx, cy, w, 16);
        UiRenderer.chip(g, this.font, label, cx, cy, w, 16, false, hover);
        if (hover) hoverHint = hint;
        return cx;
    }

    private int footerChipX(String label) {
        int cursor = x + W - PAD;
        for (String name : new String[]{"IMPORT", "EXPORT", "PRESETS", "THEMES", "MODULES"}) {
            int width = UiRenderer.chipWidth(this.font, name);
            if (name.equals(label)) return cursor - width;
            cursor -= width + 6;
        }
        return cursor;
    }

    private int footerChipW(String label) {
        return UiRenderer.chipWidth(this.font, label);
    }

    // ---- input ----

    /**
     * Printable characters, in the only place they are inserted.
     *
     * <p>26.2 delivers a {@code charTyped} event per character in addition to the
     * {@code keyPressed} event for the physical key, so a field that inserts from
     * both paths inserts twice - "abb" for one "b". Key codes handle navigation
     * (escape, enter, backspace) and numeric entry; everything a human types as
     * text lands here, where the codepoint is already resolved for layout and
     * modifiers.
     */
    @Override
    public boolean charTyped(CharacterEvent event) {
        String typed = event.codepointAsString();
        if (typed == null || typed.isEmpty()) return super.charTyped(event);
        int codepoint = event.codepoint();
        if (codepoint < 0x20 || codepoint == 0x7F) return true;

        // Numeric entry is key-code driven in keyPressed; handling digits here
        // as well would type each one twice.
        if (numericInputTarget != null) return true;
        if (listeningKeybind != null) return true;
        if (stringInputTarget != null) {
            if (stringInputValue.length() + typed.length() <= 8192) stringInputValue += typed;
            return true;
        }

        if (textInputMode != 0) {
            String current = textInputMode == 1 ? friendsInput : kitInput;
            if (current.length() < MAX_INPUT) setTextInput(current + typed);
            return true;
        }
        if (searchFocused) {
            if (searchQuery.length() < MAX_INPUT) setSearchQuery(searchQuery + typed);
            return true;
        }
        if (listSearchFocused && listMode) {
            if (listSearchQuery.length() < MAX_INPUT) {
                listSearchQuery += typed;
                pickerScroll = 0;
            }
            return true;
        }
        return super.charTyped(event);
    }

    private void setTextInput(String value) {
        if (textInputMode == 1) friendsInput = value;
        else if (textInputMode == 2) kitInput = value;
    }

    private void submitFriend() {
        String value = friendsInput.trim();
        if (value.isEmpty()) {
            textInputMode = 0;
            return;
        }
        boolean added = FriendsManager.add(value);
        Notifications.push(added ? "friend added: " + value : "already listed or unreadable: " + value, added);
        if (added) {
            friendsInput = "";
            friendsScroll = 0;
        }
        textInputMode = 0;
    }

    private void kitSave() {
        String name = kitInput.trim();
        if (name.isEmpty()) {
            Notifications.push("name the kit first", false);
            return;
        }
        boolean ok = KitManager.saveCurrent(name);
        if (ok) {
            kitCache = null;
            kitScroll = 0;
            refreshKitPreview(name);
            chat("Kit saved: " + name);
        }
        Notifications.push(ok ? "kit saved: " + name : "kit save failed - no world loaded", ok);
    }

    /**
     * Loads the kit named in the field, or the one the preview is showing.
     *
     * <p>Kept separate from the row click so the LOAD chip still works when the
     * list is empty but the name is typed, and so a row click can preview without
     * silently rewriting the inventory.
     */
    private void kitLoad() {
        if (!kitLoadNamed(selectedKitName())) {
            Notifications.push("name the kit first", false);
        }
    }

    /** The kit the LOAD and SHARE chips act on: whatever is typed, else the previewed one. */
    private String selectedKitName() {
        String typed = kitInput.trim();
        return typed.isEmpty() ? previewName : typed;
    }

    /** @return false when there was no name to work with. */
    private boolean kitLoadNamed(String name) {
        if (name == null || name.isBlank()) return false;
        int missing = KitManager.load(name);
        if (missing < 0) {
            Notifications.push("kit not found: " + name, false);
            return true;
        }
        chat("Kit loaded: " + name);
        Notifications.push(missing == 0 ? "kit loaded: " + name
                : "kit loaded: " + name + " (" + missing + " slots unfilled)", true);
        refreshKitPreview(name);
        return true;
    }

    private void kitShare(String name) {
        if (name == null || name.isBlank()) {
            Notifications.push("name the kit first", false);
            return;
        }
        String code = KitManager.share(name);
        boolean ok = !code.isEmpty() && ShareCode.copyToClipboard(code);
        Notifications.push(ok ? "kit code copied: " + name : "kit share failed: " + name, ok);
    }

    private void kitImport() {
        String name = kitInput.trim();
        if (name.isEmpty()) {
            Notifications.push("enter a name for the imported kit first", false);
            return;
        }
        if (KitManager.exists(name)) {
            Notifications.push("kit already exists; enter a different name", false);
            return;
        }
        String code = ShareCode.readClipboard();
        if (code.isBlank()) {
            Notifications.push("copy a kit share code or kit JSON first", false);
            return;
        }
        boolean ok = KitManager.importCode(name, code);
        if (ok) {
            kitCache = null;
            kitScroll = 0;
            textInputMode = 0;
            refreshKitPreview(name);
            chat("Kit imported: " + name + ". Select LOAD to equip it.");
        }
        Notifications.push(ok ? "kit imported: " + name : "kit import failed: invalid name, kit data or storage error", ok);
    }

    /** Chat line alongside the toast: the toast fades, the chat line is the record. */
    private void chat(String text) {
        ChatCommands.say("[UtilityClient] " + text, ChatFormatting.AQUA);
    }

    // ---- click ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        ensurePosition();
        double mx = designX(event.x());
        double my = designY(event.y());
        int button = event.button();

        if (numericInputTarget != null) {
            commitNumericInput();
            return true;
        }
        listeningKeybind = null;

        if (inRect(mx, my, closeX(), closeY(), closeW(), closeH())) {
            onClose();
            return true;
        }
        // Search field FIRST.
        //
        // The field is drawn inside the header band (y+8, height 18, header 34), so
        // testing the header dead space before it made the box unclickable: every
        // press inside the search rect was swallowed by the "header is dead" return
        // below and focus never arrived. Hitbox order is the bug, not the focus code.
        if (inRect(mx, my, searchX(), searchY(), searchW(), searchH())) {
            searchFocused = true;
            textInputMode = 0;
            listSearchFocused = false;
            numericInputTarget = null;
            return true;
        }
        searchFocused = false;

        // Everything else in the header is dead space: the sheet is pinned, so a
        // click on the title bar must not start a drag or fall through to a control.
        if (my >= headerTop() && my < headerTop() + HEADER_H) return true;

        // Pills.
        int py = pillTop() + (PILL_H - 18) / 2;
        for (Pill pill : layoutPills()) {
            if (!inRect(mx, my, pill.x(), py, pill.width(), 18)) continue;
            if (pill.category() == null) {
                // ALL and the rail's Modules row are one state, not two: selecting
                // either lands on the unfiltered list, and writing the category here
                // is what keeps the two from disagreeing about which is lit.
                allCategories = true;
                selectedCategory = Category.MODULES;
            } else {
                allCategories = false;
                selectedCategory = pill.category();
            }
            presetMode = false;
            closePicker();
            colorPickerTarget = null;
            textInputMode = 0;
            setSearchQuery("");
            centreScroll = 0;
            return true;
        }

        // Footer chips.
        int fy = footerTop() + 5;
        if (inRect(mx, my, footerChipX("THEMES"), fy, footerChipW("THEMES"), 16)) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ThemesScreen(this)); return true;
        }
        if (inRect(mx, my, footerChipX("MODULES"), fy, footerChipW("MODULES"), 16)) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(new CustomModulesScreen(this));
            return true;
        }
        if (inRect(mx, my, footerChipX("PRESETS"), fy, footerChipW("PRESETS"), 16)) {
            presetMode = !presetMode;
            presetScroll = 0;
            closePicker();
            colorPickerTarget = null;
            textInputMode = 0;
            if (presetMode) selectedModule = null;
            return true;
        }
        if (inRect(mx, my, footerChipX("EXPORT"), fy, footerChipW("EXPORT"), 16)) {
            if (button == 1) {
                boolean ok = ShareCode.saveToFile();
                Notifications.push(ok ? "config saved to share.txt" : "config save failed", ok);
            } else {
                String code = ShareCode.exportCode();
                boolean ok = !code.isEmpty() && ShareCode.copyToClipboard(code);
                Notifications.push(ok ? "config share code copied" : "export produced nothing", ok);
            }
            return true;
        }
        if (inRect(mx, my, footerChipX("IMPORT"), fy, footerChipW("IMPORT"), 16)) {
            if (centreMode() == MODE_KITS) {
                kitImport();
                return true;
            }
            try {
                var payload = button == 1 ? ShareCode.prepareFile() : ShareCode.prepareCode(ShareCode.readClipboard());
                Minecraft.getInstance().gui.setScreen(new ProfileReviewScreen(this,
                        button == 1 ? "share.txt" : "Clipboard profile", payload));
            } catch (Exception invalid) {
                Notifications.push("import failed: " + invalid.getMessage(), false);
            }
            return true;
        }

        // Sidebar.
        int rowH = SIDEBAR_ROW_H;
        int rowY = sidebarListTop();
        for (Category cat : sidebarTabs()) {
            if (inRect(mx, my, x, rowY, SIDEBAR_W, rowH - 2)) {
                selectCategory(cat);
                return true;
            }
            rowY += rowH;
        }

        // Enabled list under the rail. Handled before the body guard because the
        // preview panel starts below the rail and the guard below would hand the
        // click to the screen instead of the panel.
        if (mx >= x && mx < x + SIDEBAR_W && inPreviewPanel(mx, my)) {
            int previewIndex = previewRowAt(mx, my);
            if (previewIndex >= 0) {
                List<Module> on = enabledModules();
                if (previewIndex < on.size()) {
                    Module target = on.get(previewIndex);
                    if (button == 0) {
                        // Left: off, the one action this panel exists for.
                        target.setEnabled(false);
                        Notifications.push(target.getName() + " disabled", true);
                    } else if (button == 1) {
                        // Right: same contract as the centre list - open the module's
                        // settings in the right column. The row is the same row.
                        selectModule(target);
                    }
                }
            }
            // Swallow either way: the panel is a control surface, not a drag origin.
            return true;
        }

        if (my < bodyTop() || my >= bodyBottom()) return super.mouseClicked(event, doubled);

        // Centre column.
        if (mx >= centreLeft() && mx < settingsLeft()) {
            return handleCentreClick(mx, my, centreLeft(), centreW(), button);
        }

        // Settings column.
        if (mx >= settingsLeft() && mx < x + W) {
            return handleSettingsClick(mx, my, button);
        }
        return true;
    }

    /**
     * Opens one module's settings in the right column.
     *
     * <p>The single place that owns "selecting a module", because two lists now
     * select: the centre module list and the enabled list under the rail. When
     * both wrote the six fields themselves, the second copy eventually dropped
     * one - the colour picker target in this case - and a stale picker stayed
     * pinned over the settings of a module it did not belong to.
     */
    private void clearInputFocus() {
        textInputMode = 0; searchFocused = false; listSearchFocused = false;
        listeningKeybind = null; numericInputTarget = null; stringInputTarget = null;
        stringInputValue = numericInputValue = "";
    }

    private void selectModule(Module module) {
        clearInputFocus();
        selectedModule = module;
        settingsScroll = 0;
        settingsFilter = "";
        colorPickerTarget = null;
        presetMode = false;
        closePicker();
        searchFocused = false;
    }

    private void selectCategory(Category cat) {
        selectedCategory = cat;
        // Modules is the unfiltered list, so it is the one rail row that keeps the
        // ALL state on. Every other row clears it: a category row and the ALL pill
        // must never both look selected.
        allCategories = cat == Category.MODULES;
        selectedModule = null;
        settingsFilter = "";
        presetMode = false;
        closePicker();
        colorPickerTarget = null;
        textInputMode = 0;
        searchFocused = false;
        settingsScroll = 0;
        centreScroll = 0;
        setSearchQuery("");
        if (cat == Category.KIT) {
            kitCache = null;
            // Opening the tab should show something rather than an empty panel
            // when the name in the field already exists on disk.
            String typed = kitInput.trim();
            if (previewName.isEmpty() && !typed.isEmpty() && kitNames().contains(typed)) {
                refreshKitPreview(typed);
            }
        }
    }

    private boolean handleCentreClick(double mx, double my, int left, int w, int button) {
        int mode = centreMode();

        if (mode == MODE_FRIENDS) {
            int iy = friendsInputY();
            int iw = w - 12 - 12 - 42;
            if (inRect(mx, my, left + 12, iy, iw, 18)) {
                textInputMode = 1;
                return true;
            }
            if (inRect(mx, my, left + 12 + iw + 6, iy, 36, 18)) {
                submitFriend();
                return true;
            }
            List<String> entries = FriendsManager.entries();
            int listTop = iy + 18 + 8;
            if (my >= listTop && my < bodyBottom()) {
                int index = (int) ((my - listTop + friendsScroll) / 20);
                if (index >= 0 && index < entries.size()) {
                    if (mx >= left + w - 26) {
                        String name = entries.get(index);
                        boolean removed = FriendsManager.remove(name);
                        Notifications.push(removed ? "friend removed: " + name : "not listed: " + name, removed);
                    }
                    return true;
                }
            }
            textInputMode = 0;
            return true;
        }

        if (mode == MODE_KITS) {
            int iy = kitInputY();
            int iw = w - 24;
            if (inRect(mx, my, left + 12, iy, iw, 18)) {
                textInputMode = 2;
                return true;
            }
            int by = kitButtonY();
            int bw = (iw - 4) / 2;
            for (int i = 0; i < 4; i++) {
                if (inRect(mx, my, left + 12 + (i % 2) * (bw + 4), by + (i / 2) * 22, bw, 18)) {
                    if (i == 0) kitSave();
                    else if (i == 1) kitLoad();
                    else if (i == 2) kitShare(selectedKitName());
                    else kitImport();
                    return true;
                }
            }
            List<String> kits = kitNames();
            int listTop = kitListTop();
            if (my >= listTop && my < bodyBottom()) {
                int index = (int) ((my - listTop + kitScroll) / 20);
                if (index >= 0 && index < kits.size()) {
                    String name = kits.get(index);
                    if (mx >= left + w - 26) {
                        boolean ok = KitManager.delete(name);
                        kitCache = null;
                        if (name.equals(previewName)) {
                            previewStacks = emptyPreview();
                            previewName = "";
                        }
                        Notifications.push(ok ? "kit deleted: " + name : "delete failed: " + name, ok);
                    } else if (mx >= left + w - 62) {
                        kitShare(name);
                    } else if (mx >= left + w - 96) {
                        kitInput = name;
                        kitLoadNamed(name);
                    } else {
                        // Selecting is a preview, not a load: reading a kit should
                        // never rewrite an inventory by accident.
                        kitInput = name;
                        refreshKitPreview(name);
                    }
                    return true;
                }
            }
            textInputMode = 0;
            return true;
        }

        if (mode == MODE_PRESETS) {
            int listTop = contentTop() + 26;
            if (my < listTop || my >= bodyBottom()) return true;
            ConfigManager manager = UtilityClient.configManager();
            if (manager == null) return true;

            List<String> presets = ConfigManager.listPresets();
            int index = (int) ((my - listTop + presetScroll) / 22);
            int total = presets.size() + 1;
            if (index < 0 || index >= total) return true;

            if (index == 0) {
                String name = searchQuery.isBlank() ? "quick" : searchQuery.trim();
                if (manager.savePreset(name)) {
                    Notifications.push("saved preset: " + name, true);
                    setSearchQuery("");
                } else {
                    Notifications.push("preset save failed", false);
                }
                return true;
            }

            String name = presets.get(index - 1);
            if (mx >= left + w - 26) {
                if (manager.deletePreset(name)) Notifications.push("deleted: " + name, true);
                else Notifications.push("delete failed: " + name, false);
                return true;
            }
            try {
                var payload = new com.google.gson.JsonObject();
                payload.add("modules", manager.readPreset(name));
                Minecraft.getInstance().gui.setScreen(new ProfileReviewScreen(this, "Preset: " + name, payload));
            } catch (Exception invalid) {
                Notifications.push("preset review failed: " + invalid.getMessage(), false);
            }
            return true;
        }

        if (mode == MODE_PICKER) {
            int iy = contentTop() + 30;
            if (inRect(mx, my, left + 12, iy, w - 24, 18)) {
                listSearchFocused = true;
                return true;
            }
            listSearchFocused = false;
            List<String> ids = filteredPickIds();
            int listTop = iy + 18 + 8;
            if (my >= listTop && my < bodyBottom()) {
                int index = (int) ((my - listTop + pickerScroll) / 18);
                if (index >= 0 && index < ids.size()) {
                    togglePick(ids.get(index));
                    return true;
                }
            }
            if (button == 1) {
                closePicker();
                return true;
            }
            return true;
        }

        // Module list.
        int listTop = contentTop() + 24 + COLUMN_H;
        if (my < listTop || my >= bodyBottom()) return true;
        List<Module> mods = visibleModules();
        int index = (int) ((my - listTop + centreScroll) / MODULE_ROW_H);
        if (index < 0 || index >= mods.size()) return true;

        Module m = mods.get(index);
        if (button == 0) {
            if (!(m instanceof ApexModule apex) || apex.isImplemented()) m.toggle();
        } else if (button == 1) {
            selectModule(m);
        }
        return true;
    }

    private boolean handleSettingsClick(double mx, double my, int button) {
        int left = settingsLeft();

        if (colorPickerTarget != null && inRect(mx, my, left + SETTINGS_W - 54, contentTop() + 6, 42, 16)) {
            colorPickerTarget = null;
            pickerDragMode = 0;
            return true;
        }

        // Header checkbox toggles the module.
        if (selectedModule != null && my >= contentTop() && my < contentTop() + 24) {
            if (mx >= left + SETTINGS_W - 60 && mx < left + SETTINGS_W - 8) {
                if (button == 0 && (!(selectedModule instanceof ApexModule apex) || apex.isImplemented())) selectedModule.toggle();
            }
            return true;
        }

        if (selectedModule == null) return true;

        int top = bodyBottom() - 28;
        int bw = 62;
        int resetX = left + SETTINGS_W - 12 - bw;
        int bindX = resetX - 8 - bw;

        // Footer actions.
        if (my >= top + 5 && my < top + 23) {
            if (mx >= bindX && mx < bindX + bw) {
                beginBindListen();
                return true;
            }
            if (mx >= resetX && mx < resetX + bw) {
                int restored = selectedModule.resetSettings();
                Notifications.push(restored > 0
                        ? "reset " + restored + " settings on " + selectedModule.getName()
                        : "nothing to reset", restored > 0);
                return true;
            }
            if (!settingsFilter.isBlank() && mx < bindX - 6) {
                settingsFilter = "";
                settingsScroll = 0;
                return true;
            }
        }

        if (colorPickerTarget != null) {
            if (inRect(mx, my, left + SETTINGS_W - 54, contentTop() + 6, 42, 16)) {
                colorPickerTarget = null;
                pickerDragMode = 0;
                return true;
            }
            if (button == 1) {
                colorPickerTarget = null;
                pickerDragMode = 0;
                return true;
            }
            int sx = pickerSquareLeft();
            int sw = pickerSquareW();
            if (inRect(mx, my, sx, pickerSquareTop(), sw, pickerSquareH())) {
                pickerDragMode = 1;
                applyPicker(mx, my);
                return true;
            }
            if (inRect(mx, my, sx, pickerHueTop() - 2, sw, pickerHueH() + 4)) {
                pickerDragMode = 2;
                applyPicker(mx, my);
                return true;
            }
            if (inRect(mx, my, sx, pickerAlphaTop() - 2, sw, pickerAlphaH() + 4)) {
                pickerDragMode = 3;
                applyPicker(mx, my);
                return true;
            }
            return true;
        }

        // Setting rows.
        int listTop = settingsListTop();
        if (my < listTop || my >= settingsListBottom()) return true;
        int cursor = listTop - settingsScroll;
        for (Setting s : settingsList()) {
            int rowH = settingHeight(s);
            if (my >= cursor && my < cursor + rowH - 1) {
                handleSettingClick(s, mx, button);
                return true;
            }
            cursor += rowH;
        }
        return true;
    }

    private void beginBindListen() {
        clearInputFocus();
        for (Setting s : selectedModule.getSettings()) {
            if (s instanceof KeybindSetting && "keybind".equals(s.getName())) {
                listeningKeybind = s;
                return;
            }
        }
        Notifications.push("no bind on " + selectedModule.getName(), false);
    }

    private void handleSettingClick(Setting s, double mx, int button) {
        if (s instanceof com.baggute.cheatclient.settings.ChoiceSetting choice) {
            clearInputFocus(); choice.cycle(button == 1 ? -1 : 1); UtilityClient.saveConfig(); return;
        }
        if (button == 1) {
            clearInputFocus();
            if (s instanceof KeybindSetting key && "keybind".equals(key.getName())) key.setHeld(!key.isHeld());
            return;
        }
        clearInputFocus();
        if (s instanceof BoolSetting b) {
            b.toggle();
        } else if (s instanceof IntSetting is) {
            beginSlider(is, mx, is.get());
        } else if (s instanceof DoubleSetting ds) {
            beginSlider(ds, mx, ds.get());
        } else if (s instanceof ColorSetting cs) {
            colorPickerTarget = s;
            pickerAlpha = (cs.get() >>> 24) & 0xFF;
            if (pickerAlpha == 0) pickerAlpha = 255;
            float[] hsv = Color.RGBtoHSB((cs.get() >> 16) & 0xFF, (cs.get() >> 8) & 0xFF, cs.get() & 0xFF, null);
            pickerHue = hsv[0];
            pickerSat = hsv[1];
            pickerVal = hsv[2];
            pickerDragMode = 0;
        } else if (s instanceof KeybindSetting) {
            listeningKeybind = s;
        } else if (s instanceof StringSetting ss) {
            String name = ss.getName();
            if (name.equals("entityFilter") || name.equals("blockFilter")
                    || name.equals("hiddenModules") || name.equals("potionFilter")) {
                openPicker(name);
            } else if (name.equals("editPositions")) {
                if (selectedModule instanceof Hud hud) {
                    Minecraft.getInstance().gui.setScreen(new HudEditorScreen(hud));
                }
            } else {
                stringInputTarget = ss;
                stringInputValue = ss.get();
            }
        }
    }

    private void beginSlider(Setting s, double mx, double value) {
        mouseDownSetting = s;
        mouseDownX = (int) mx;
        mouseDownValue = value;
        sliderRowW = Math.max(1, SETTINGS_W - 26);
        sliderDidDrag = false;
    }

    private void applySliderDrag(double mx) {
        if (mouseDownSetting == null) return;
        double delta = (mx - mouseDownX) / (double) sliderRowW;
        if (mouseDownSetting instanceof IntSetting is) {
            double lo = is.getMin(), hi = is.getMax();
            is.set((int) Math.round(Math.max(lo, Math.min(hi, mouseDownValue + delta * (hi - lo)))));
        } else if (mouseDownSetting instanceof DoubleSetting ds) {
            double lo = ds.getMin(), hi = ds.getMax();
            ds.set(Math.max(lo, Math.min(hi, mouseDownValue + delta * (hi - lo))));
        }
    }

    private void commitNumericInput() {
        if (numericInputTarget == null) return;
        try {
            if (numericInputTarget instanceof IntSetting i) {
                i.set((int) Double.parseDouble(numericInputValue));
            } else if (numericInputTarget instanceof DoubleSetting d) {
                d.set(Double.parseDouble(numericInputValue));
            }
        } catch (NumberFormatException ignored) {
            // A half-typed number is dropped, not applied as zero.
        }
        numericInputTarget = null;
        numericInputValue = "";
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        ensurePosition();
        double mx = designX(event.x());
        double my = designY(event.y());
        if (colorPickerTarget != null && pickerDragMode != 0) {
            applyPicker(mx, my);
            return true;
        }
        if (mouseDownSetting != null) {
            if (Math.abs(mx - mouseDownX) > 2) sliderDidDrag = true;
            if (sliderDidDrag) applySliderDrag(mx);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        pickerDragMode = 0;
        if (mouseDownSetting != null) {
            if (!sliderDidDrag) {
                clearInputFocus();
                numericInputTarget = mouseDownSetting;
                if (mouseDownSetting instanceof IntSetting is) {
                    numericInputValue = String.valueOf(is.get());
                } else if (mouseDownSetting instanceof DoubleSetting ds) {
                    numericInputValue = String.format("%.2f", ds.get());
                }
            }
            mouseDownSetting = null;
            sliderDidDrag = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double screenX, double screenY, double hAmount, double vAmount) {
        ensurePosition();
        double mx = designX(screenX);
        double my = designY(screenY);
        if (mx < x || mx >= x + W || my < bodyTop() || my >= bodyBottom()) return true;

        int step = (int) Math.round(vAmount) * 18;
        if (step == 0) step = vAmount > 0 ? 18 : (vAmount < 0 ? -18 : 0);

        if (mx >= settingsLeft()) {
            int content = settingsContentHeight();
            int view = settingsViewHeight();
            settingsScroll = clampScroll(settingsScroll - step, content, view);
            return true;
        }

        if (mx < centreLeft()) return true;

        switch (centreMode()) {
            case MODE_FRIENDS -> friendsScroll = clampScroll(friendsScroll - step,
                    FriendsManager.entries().size() * 20, bodyBottom() - (friendsInputY() + 26));
            case MODE_KITS -> {
                int view = bodyBottom() - kitListTop();
                kitScroll = clampScroll(kitScroll - step, kitNames().size() * 20, view);
            }
            case MODE_PRESETS -> presetScroll = clampScroll(presetScroll - step,
                    (ConfigManager.listPresets().size() + 1) * 22, bodyBottom() - (contentTop() + 26));
            case MODE_PICKER -> pickerScroll = clampScroll(pickerScroll - step,
                    filteredPickIds().size() * 18, bodyBottom() - (contentTop() + 30 + 26));
            default -> centreScroll = clampScroll(centreScroll - step,
                    visibleModules().size() * MODULE_ROW_H,
                    bodyBottom() - (contentTop() + 24 + COLUMN_H));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();

        if (stringInputTarget != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { stringInputTarget = null; stringInputValue = ""; return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                stringInputTarget.set(stringInputValue);
                if (selectedModule != null) selectedModule.onConfigLoaded();
                stringInputTarget = null; stringInputValue = "";
                UtilityClient.saveConfig();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!stringInputValue.isEmpty()) stringInputValue = stringInputValue.substring(0,
                        stringInputValue.offsetByCodePoints(stringInputValue.length(), -1));
                return true;
            }
            if (event.hasControlDown() && keyCode == GLFW.GLFW_KEY_A) { stringInputValue = ""; return true; }
            if (event.hasControlDown() && keyCode == GLFW.GLFW_KEY_V) {
                String paste = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (paste != null) stringInputValue = (stringInputValue + paste.replace("\r", "").replace("\n", ""))
                        .substring(0, Math.min(8192, stringInputValue.length() + paste.replace("\r", "").replace("\n", "").length()));
                return true;
            }
            return true;
        }

        if (textInputMode != 0) {
            String current = textInputMode == 1 ? friendsInput : kitInput;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                textInputMode = 0;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (textInputMode == 1) submitFriend();
                else textInputMode = 0;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                setTextInput(current.isEmpty() ? current : current.substring(0, current.length() - 1));
                return true;
            }
            // Printable characters arrive through charTyped, not from the key code:
            // one path, so shift, punctuation and dead keys all type once instead
            // of twice.
            return true;
        }

        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (isFiltering()) setSearchQuery("");
                else searchFocused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchFocused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) setSearchQuery(searchQuery.substring(0, searchQuery.length() - 1));
                return true;
            }
            return true;
        }

        if (numericInputTarget != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                numericInputTarget = null;
                numericInputValue = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitNumericInput();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!numericInputValue.isEmpty()) {
                    numericInputValue = numericInputValue.substring(0, numericInputValue.length() - 1);
                }
                return true;
            }
            if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
                numericInputValue += (char) ('0' + (keyCode - GLFW.GLFW_KEY_0));
                return true;
            }
            if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
                numericInputValue += (char) ('0' + (keyCode - GLFW.GLFW_KEY_KP_0));
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_PERIOD || keyCode == GLFW.GLFW_KEY_KP_DECIMAL)
                    && !numericInputValue.contains(".")) {
                if (numericInputValue.isEmpty()) numericInputValue = "0";
                numericInputValue += ".";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS) {
                numericInputValue = numericInputValue.startsWith("-")
                        ? numericInputValue.substring(1) : "-" + numericInputValue;
                return true;
            }
            return true;
        }

        if (listSearchFocused && listMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (!listSearchQuery.isEmpty()) listSearchQuery = "";
                else listSearchFocused = false;
                pickerScroll = 0;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!listSearchQuery.isEmpty()) {
                    listSearchQuery = listSearchQuery.substring(0, listSearchQuery.length() - 1);
                }
                pickerScroll = 0;
                return true;
            }
            return true;
        }

        if (colorPickerTarget != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            colorPickerTarget = null;
            pickerDragMode = 0;
            return true;
        }

        if (listeningKeybind instanceof KeybindSetting k) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) k.setKey(-1);
            else k.setKey(keyCode);
            listeningKeybind = null;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        UtilityClient.saveConfig();
        if (returnToEditor && UtilityClient.moduleManager != null
                && UtilityClient.moduleManager.getHud() != null) {
            Minecraft.getInstance().gui.setScreen(new HudEditorScreen(UtilityClient.moduleManager.getHud()));
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
