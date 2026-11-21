// language: Java, file: HudEditorScreen.java, target: Fabric 26.2
package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.render.HudRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Drag any HUD widget. Each widget is boxed from the bounds the renderer recorded
 * last frame, so what you grab is exactly what is drawn.
 *
 * <p>Toolbar: add a widget from the picker, toggle the snap grid, reset the
 * shipped layout, jump back to the settings panel, or save and leave. Hovering a
 * widget shows a small x that removes it.
 */
public class HudEditorScreen extends Screen {

    private static final int TOP = 22;
    private static final int BOTTOM = 16;
    private static final int GRID = 10;
    private static final int ROW_H = 15;
    /** The HUD is previewed below the toolbar so the chrome never hides a widget. */
    private static final int CANVAS_Y = TOP;

    private final Hud hud;
    private Hud.Widget dragWidget = null;
    private int dragOffX, dragOffY;

    private boolean grid = true;
    private boolean pickerOpen = false;
    private int pickerScroll = 0;

    private static final String[] BUTTONS = {"+ Add widget", "Grid", "Reset", "Back", "Done"};

    public HudEditorScreen(Hud hud) {
        super(Component.literal("Hud Editor"));
        this.hud = hud;
    }

    // ---- toolbar geometry ----

    private int buttonWidth(int index) {
        return this.font.width(BUTTONS[index]) + 14;
    }

    private int[] buttonRect(int index) {
        int right = this.width - 10;
        for (int i = BUTTONS.length - 1; i > index; i--) {
            right -= buttonWidth(i) + 6;
        }
        return new int[]{right - buttonWidth(index), 4, buttonWidth(index), 14};
    }

    private boolean hit(double mx, double my, int[] rect) {
        return mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3];
    }

    // ---- picker geometry ----

    private int[] pickerRect() {
        int w = 252;
        int rows = hud.widgets().size();
        int wanted = 30 + rows * ROW_H + 18;
        int h = Math.min(wanted, this.height - 40);
        return new int[]{(this.width - w) / 2, (this.height - h) / 2, w, h};
    }

    private int pickerListTop() { return pickerRect()[1] + 30; }

    private int pickerListBottom() { return pickerRect()[1] + pickerRect()[3] - 18; }

    private int pickerVisibleRows() {
        return Math.max(1, (pickerListBottom() - pickerListTop()) / ROW_H);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiRenderer.refreshTheme();
        // Backdrop: deep dim with a soft top lift, then the grid.
        g.fill(0, 0, this.width, this.height, 0xC8060709);
        Ui.vGradient(g, 0, 0, this.width, 70, 0x40000000, 0x00000000);
        Ui.vGradient(g, 0, this.height - 90, this.width, this.height, 0x00000000, 0x60000000);
        if (grid) drawGrid(g);

        // The real HUD, so boxes match what is drawn. Shifted below the toolbar so
        // a widget parked at y=5 is still visible while editing.
        g.pose().pushMatrix();
        try {
            g.pose().translate(0f, CANVAS_Y);
            HudRenderer.renderPreview(g);
        } finally { g.pose().popMatrix(); }
        drawWidgetBoxes(g, mouseX, mouseY);
        drawToolbar(g, mouseX, mouseY);
        drawFooter(g);
        if (pickerOpen) drawPicker(g, mouseX, mouseY);
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        // Thin rails, not thousands of dots: a full-screen dot cloud at 10 px is
        // far too many fills for a GUI frame.
        int faint = 0x0AFFFFFF;
        for (int gx = 0; gx < this.width; gx += GRID) {
            g.fill(gx, CANVAS_Y, gx + 1, this.height - BOTTOM, faint);
        }
        for (int gy = CANVAS_Y; gy < this.height - BOTTOM; gy += GRID) {
            g.fill(0, gy, this.width, gy + 1, faint);
        }
        // Stronger rails every five cells make alignment readable.
        for (int gx = 0; gx < this.width; gx += GRID * 5) {
            g.fill(gx, CANVAS_Y, gx + 1, this.height - BOTTOM, 0x14FFFFFF);
        }
        for (int gy = CANVAS_Y; gy < this.height - BOTTOM; gy += GRID * 5) {
            g.fill(0, gy, this.width, gy + 1, 0x14FFFFFF);
        }
    }

    private void drawWidgetBoxes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        double s = hud.getScale();
        for (Hud.Widget widget : Hud.Widget.values()) {
            if (!hud.isWidgetEnabled(widget)) continue;
            int[] b = hud.getBounds(widget);
            if (b == null) continue;

            int x = (int) (b[0] * s);
            int y = CANVAS_Y + (int) (b[1] * s);
            int w = Math.max(24, (int) (b[2] * s));
            int h = Math.max(10, (int) (b[3] * s));

            boolean hover = mouseX >= x - 4 && mouseX <= x + w + 4
                    && mouseY >= y - 4 && mouseY <= y + h + 4;
            boolean active = dragWidget == widget;
            int border = hover || active ? Ui.accent() : 0x50FFFFFF;
            int fill = hover ? Ui.WASH : 0x0CFFFFFF;

            g.fill(x - 3, y - 3, x + w + 3, y + h + 3, fill);
            g.fill(x - 3, y - 3, x + w + 3, y - 2, border);
            g.fill(x - 3, y + h + 2, x + w + 3, y + h + 3, border);
            g.fill(x - 3, y - 3, x - 2, y + h + 3, border);
            g.fill(x + w + 2, y - 3, x + w + 3, y + h + 3, border);
            Ui.corners(g, x - 3, y - 3, w + 6, h + 6, 6, hover ? Ui.accent() : 0x35FFFFFF);

            int labelY = y - 13;
            if (labelY < CANVAS_Y + 1) labelY = y + h + 3;
            g.text(this.font, widget.title(), x - 2, labelY, hover || active ? Ui.TEXT : Ui.DIM, true);

            if (hover) {
                int[] remove = removeRect(x, y, w);
                g.fill(remove[0], remove[1], remove[0] + remove[2], remove[1] + remove[3], 0x40FF6B81);
                g.text(this.font, "x", remove[0] + 3, remove[1] + 2, Ui.RED, false);
            }
        }
    }

    private int[] removeRect(int x, int y, int w) {
        int ry = y - 14;
        if (ry < CANVAS_Y + 1) ry = y + 2; // keep it on-canvas for top-row widgets
        return new int[]{x + w - 9, ry, 12, 12};
    }

    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, TOP, Ui.HEADER);
        g.fill(0, TOP - 1, this.width, TOP, Ui.SOFT);
        Ui.hGradient(g, 0, this.width, TOP, TOP + 1, Ui.fade(Ui.ACCENT, 0.75f), Ui.fade(Ui.ACCENT2, 0.75f));

        g.fill(10, 4, 14, 17, Ui.accent());
        g.fill(14, 4, 18, 17, Ui.ACCENT2);
        g.text(this.font, "HUD", 24, 4, Ui.TEXT, true);

        // Keep the left title clear of the buttons on narrow GUI scales.
        int buttonsLeft = buttonRect(0)[0];
        if (24 + this.font.width("HUD editor") + 10 < buttonsLeft) {
            g.text(this.font, "editor", 24 + this.font.width("HUD ") + 2, 4, Ui.MUTED, false);
        }
        String subtitle = hud.visibleWidgetCount() + " widgets";
        if (24 + this.font.width(subtitle) + 10 < buttonsLeft) {
            g.text(this.font, subtitle, 24, 13, Ui.DIM, false);
        }

        for (int i = 0; i < BUTTONS.length; i++) {
            int[] r = buttonRect(i);
            boolean hover = hit(mouseX, mouseY, r);
            boolean on = i == 1 && grid;
            int border = hover || on ? Ui.accentFaded(0.9f) : Ui.BORDER;
            int body = hover ? 0x2A29E0FF : (on ? 0x1A29E0FF : Ui.INPUT);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], border);
            g.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, body);
            g.text(this.font, BUTTONS[i], r[0] + 7, r[1] + 4,
                    i == 4 ? Ui.GREEN : (hover || on ? Ui.TEXT : Ui.DIM), false);
        }
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int fy = this.height - BOTTOM;
        g.fill(0, fy, this.width, this.height, Ui.HEADER);
        g.fill(0, fy, this.width, fy + 1, Ui.SOFT);
        String hint = "drag to move · shift = no snap · right-click a widget for its settings · x removes it";
        String reason = HudRenderer.visibilityReason();
        if (reason != null) hint = reason + " · drag to edit the preview";
        g.text(this.font, hint, 10, fy + 5, Ui.MUTED, false);
        String gridText = "grid " + (grid ? "on" : "off");
        g.text(this.font, gridText, this.width - 10 - this.font.width(gridText), fy + 5,
                grid ? Ui.accentFaded(0.9f) : Ui.MUTED, false);
    }

    private void drawPicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, this.height, 0x99000000);
        int[] card = pickerRect();
        Ui.shadow(g, card[0], card[1], card[2], card[3]);
        Ui.frame(g, card[0], card[1], card[2], card[3], 0xF80B0D12);

        g.text(this.font, "ADD WIDGET", card[0] + 10, card[1] + 8, Ui.TEXT, true);
        String count = hud.visibleWidgetCount() + " shown";
        g.text(this.font, count, card[0] + card[2] - 10 - this.font.width(count), card[1] + 8, Ui.MUTED, false);
        g.fill(card[0] + 8, card[1] + 21, card[0] + card[2] - 8, card[1] + 22, Ui.SOFT);

        List<Hud.Widget> widgets = hud.widgets();
        int top = pickerListTop();
        int bottom = pickerListBottom();
        int rows = pickerVisibleRows();
        int maxScroll = Math.max(0, widgets.size() - rows);
        if (pickerScroll > maxScroll) pickerScroll = maxScroll;
        if (pickerScroll < 0) pickerScroll = 0;

        for (int i = pickerScroll; i < widgets.size() && i < pickerScroll + rows; i++) {
            Hud.Widget widget = widgets.get(i);
            int rowY = top + (i - pickerScroll) * ROW_H;
            boolean hover = mouseX >= card[0] + 6 && mouseX < card[0] + card[2] - 6
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean on = hud.isWidgetEnabled(widget);

            if (hover) {
                g.fill(card[0] + 6, rowY, card[0] + card[2] - 6, rowY + ROW_H - 1,
                        on ? 0x22FF6B81 : Ui.WASH);
            }
            String mark = on ? "-" : "+";
            int markColor = on ? Ui.RED : Ui.GREEN;
            g.fill(card[0] + 10, rowY + 4, card[0] + 21, rowY + ROW_H - 3, 0x18FFFFFF);
            g.text(this.font, mark, card[0] + 14, rowY + 5, markColor, true);

            g.text(this.font, widget.title(), card[0] + 28, rowY + 5, on ? Ui.TEXT : Ui.DIM, false);
            String state = on ? "shown" : "hidden";
            g.text(this.font, state, card[0] + card[2] - 12 - this.font.width(state), rowY + 5,
                    on ? Ui.accentFaded(0.9f) : Ui.MUTED, false);
        }

        if (maxScroll > 0) {
            int trackX = card[0] + card[2] - 5;
            g.fill(trackX, top, trackX + 2, bottom, Ui.SOFT);
            int thumbH = Math.max(12, (bottom - top) * rows / widgets.size());
            int thumbY = top + (bottom - top - thumbH) * pickerScroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, Ui.ACCENT);
        }

        String footer = "click a row to add or remove · esc closes";
        g.text(this.font, footer, card[0] + 10, card[1] + card[3] - 13, Ui.MUTED, false);
    }

    // ---- interaction ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();

        if (pickerOpen) {
            int[] card = pickerRect();
            if (!hit(mx, my, card)) {
                pickerOpen = false;
                return true;
            }
            int top = pickerListTop();
            if (my >= top && my < pickerListBottom()) {
                int index = pickerScroll + (int) ((my - top) / ROW_H);
                List<Hud.Widget> widgets = hud.widgets();
                if (index >= 0 && index < widgets.size()) {
                    Hud.Widget widget = widgets.get(index);
                    if (hud.isWidgetEnabled(widget)) hud.removeWidget(widget);
                    else hud.addWidget(widget);
                    save();
                }
            }
            return true;
        }

        for (int i = 0; i < BUTTONS.length; i++) {
            if (!hit(mx, my, buttonRect(i))) continue;
            switch (i) {
                case 0 -> pickerOpen = true;
                case 1 -> grid = !grid;
                case 2 -> {
                    hud.resetLayout();
                    save();
                }
                case 3 -> {
                    save();
                    net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ClickGuiScreen(hud, null));
                }
                default -> this.onClose();
            }
            return true;
        }

        double s = hud.getScale();
        for (Hud.Widget widget : Hud.Widget.values()) {
            if (!hud.isWidgetEnabled(widget)) continue;
            int[] b = hud.getBounds(widget);
            if (b == null) continue;

            int x = (int) (b[0] * s);
            int y = CANVAS_Y + (int) (b[1] * s);
            int w = Math.max(24, (int) (b[2] * s));
            int h = Math.max(10, (int) (b[3] * s));

            if (hit(mx, my, removeRect(x, y, w))) {
                hud.removeWidget(widget);
                save();
                return true;
            }
            if (mx >= x - 4 && mx <= x + w + 4 && my >= y - 4 && my <= y + h + 4) {
                if (event.button() == 1) {
                    save();
                    // Scope the settings panel to this widget's own rows
                    // ("show_<key>", "color_<key>", ...) instead of the whole Hud list.
                    net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                            new ClickGuiScreen(hud, widget.key()));
                    return true;
                }
                dragWidget = widget;
                dragOffX = (int) (mx - x);
                dragOffY = (int) (my - y);
                return true;
            }
        }

        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragWidget == null) return super.mouseDragged(event, dx, dy);

        double s = hud.getScale();
        int newX = (int) ((event.x() - dragOffX) / s);
        // The preview is shifted down by the toolbar; positions are stored in HUD
        // space, so remove the offset again before writing the position.
        int newY = (int) ((event.y() - dragOffY - CANVAS_Y) / s);

        boolean shift = (event.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
        if (grid && !shift) {
            newX = Math.round(newX / (float) GRID) * GRID;
            newY = Math.round(newY / (float) GRID) * GRID;
        }

        int[] b = hud.getBounds(dragWidget);
        int w = b == null ? 90 : b[2];
        int h = b == null ? 12 : b[3];
        int maxX = (int) (this.width / s) - w - 2;
        int maxY = (int) ((this.height - BOTTOM - CANVAS_Y) / s) - h - 2;
        hud.setPosition(dragWidget, Math.max(2, Math.min(maxX, newX)), Math.max(2, Math.min(maxY, newY)));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragWidget != null) save();
        dragWidget = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (pickerOpen) {
            int max = Math.max(0, hud.widgets().size() - pickerVisibleRows());
            pickerScroll = (int) Math.max(0, Math.min(max, pickerScroll - vAmount));
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (pickerOpen) {
                pickerOpen = false;
                return true;
            }
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void save() {
        hud.getLayoutSetting().set(hud.saveLayout());
        com.baggute.cheatclient.UtilityClient.saveConfig();
    }

    @Override
    public void onClose() {
        save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
