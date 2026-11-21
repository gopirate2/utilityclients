package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.config.ProfileComparison;
import com.baggute.cheatclient.config.ShareCode;
import com.baggute.cheatclient.friends.FriendsManager;
import com.baggute.cheatclient.module.misc.Notifications;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/** A detached import review. Returning to the parent without Apply changes nothing. */
public final class ProfileReviewScreen extends Screen {
    private final Screen parent;
    private final String source;
    private final JsonObject payload;
    private List<ProfileComparison.Change> changes;
    private int scroll;
    private String notice = "Review current → incoming values. Click a row to copy its full detail.";
    private static final int ROW = 37;

    public ProfileReviewScreen(Screen parent, String source, JsonObject payload) {
        super(Component.literal("Profile comparison"));
        this.parent = parent; this.source = source; this.payload = payload.deepCopy();
        this.changes = currentChanges();
    }

    private List<ProfileComparison.Change> currentChanges() {
        var manager = UtilityClient.configManager();
        if (manager == null) throw new IllegalStateException("Client configuration unavailable");
        List<ProfileComparison.Change> result = new ArrayList<>(manager.compareSnapshot(payload.getAsJsonObject("modules")));
        result.addAll(ProfileComparison.friends(FriendsManager.toJson(), payload.getAsJsonObject("friends")));
        return List.copyOf(result);
    }

    private int left() { return Math.max(4, (width - Math.min(730, width - 8)) / 2); }
    private int panelWidth() { return width - left() * 2; }
    private int listTop() { return 62; }
    private int listBottom() { return Math.max(listTop() + 1, height - 55); }
    private int maximumScroll() { return Math.max(0, changes.size() * ROW - (listBottom() - listTop())); }
    private int buttonWidth() { return Math.min(112, Math.max(62, (panelWidth() - 24) / 3)); }
    private int buttonX(int index) { return left() + 8 + index * (buttonWidth() + 6); }
    private long effectiveChanges() { return changes.stream().filter(c -> !c.ignored()).count(); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        UiRenderer.refreshTheme();
        UiRenderer.fill(g, 0, 0, width, height, 0xEA121212);
        int x = left(), w = panelWidth();
        UiRenderer.panel(g, x, 7, w, height - 14, UiRenderer.PANEL);
        UiRenderer.textLeft(g, font, "PROFILE COMPARISON", x + 10, 17, UiRenderer.ACCENT_TEXT);
        UiRenderer.textLeft(g, font, UiRenderer.trim(font, source, w - 20), x + 10, 31, UiRenderer.TEXT_DIM);
        UiRenderer.textLeft(g, font, effectiveChanges() + " changes · " + (changes.size() - effectiveChanges()) + " ignored", x + 10, 46, UiRenderer.TEXT_DIM);
        scroll = Math.max(0, Math.min(maximumScroll(), scroll));
        g.enableScissor(x + 1, listTop(), x + w - 1, listBottom());
        try {
            if (changes.isEmpty()) UiRenderer.textLeft(g, font, "This profile matches your current settings.", x + 10, listTop() + 8, UiRenderer.TEXT_DIM);
            int half = Math.max(1, (w - 32) / 2);
            for (int i = 0; i < changes.size(); i++) {
                int y = listTop() + i * ROW - scroll;
                if (y + ROW <= listTop() || y >= listBottom()) continue;
                var c = changes.get(i);
                boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW && my >= listTop() && my < listBottom();
                if (hover) UiRenderer.fill(g, x + 1, y, w - 2, ROW, UiRenderer.HOVER);
                int color = c.ignored() ? UiRenderer.TEXT_MUTED : UiRenderer.TEXT;
                UiRenderer.textLeft(g, font, UiRenderer.trim(font, c.module() + " / " + c.setting() + (c.ignored() ? " (ignored)" : ""), w - 20), x + 10, y + 4, color);
                UiRenderer.textLeft(g, font, UiRenderer.trim(font, "Current: " + c.current(), half), x + 10, y + 18, UiRenderer.TEXT_DIM);
                UiRenderer.textLeft(g, font, UiRenderer.trim(font, "Incoming: " + c.incoming(), half), x + 18 + half, y + 18, c.ignored() ? UiRenderer.TEXT_MUTED : UiRenderer.ACCENT_TEXT);
                UiRenderer.hLine(g, x + 8, y + ROW - 1, w - 16, UiRenderer.BORDER_SOFT);
            }
        } finally { g.disableScissor(); }
        UiRenderer.scrollbar(g, x + w - 5, listTop(), listBottom() - listTop(), scroll, changes.size() * ROW);
        String[] buttons = {"APPLY", "CANCEL", "COPY DIFF"};
        for (int i = 0; i < buttons.length; i++) {
            boolean hover = hit(mx, my, i);
            UiRenderer.panel(g, buttonX(i), height - 45, buttonWidth(), 19, hover ? UiRenderer.HOVER : UiRenderer.INPUT);
            UiRenderer.textLeft(g, font, buttons[i], buttonX(i) + 7, height - 39, i == 0 && effectiveChanges() == 0 ? UiRenderer.TEXT_MUTED : UiRenderer.TEXT);
        }
        UiRenderer.textLeft(g, font, UiRenderer.trim(font, notice, w - 20), x + 10, height - 19, UiRenderer.TEXT_DIM);
    }

    private boolean hit(double mx, double my, int index) {
        return mx >= buttonX(index) && mx < buttonX(index) + buttonWidth() && my >= height - 45 && my < height - 26;
    }

    private String detail(ProfileComparison.Change change) {
        return change.module() + " / " + change.setting() + (change.ignored() ? " (ignored)" : "")
                + "\nCurrent: " + change.current() + "\nIncoming: " + change.incoming();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) return true;
        for (int i = 0; i < 3; i++) if (hit(event.x(), event.y(), i)) {
            if (i == 1) onClose();
            else if (i == 2) {
                StringBuilder report = new StringBuilder(source + "\n");
                for (var change : changes) report.append(detail(change)).append("\n\n");
                notice = ShareCode.copyToClipboard(report.toString()) ? "Full comparison copied." : "Clipboard unavailable.";
            } else if (effectiveChanges() > 0) {
                List<ProfileComparison.Change> refreshed = currentChanges();
                if (!refreshed.equals(changes)) {
                    changes = refreshed; scroll = 0; notice = "Current settings changed. Review the refreshed comparison and Apply again.";
                } else if (ShareCode.applyPrepared(payload)) {
                    Notifications.push("profile applied: " + source, true); onClose();
                } else notice = "Profile could not be applied. Inspect the client log for the error and recovery details.";
            }
            return true;
        }
        if (event.x() >= left() && event.x() < left() + panelWidth() && event.y() >= listTop() && event.y() < listBottom()) {
            int index = (int) ((event.y() - listTop() + scroll) / ROW);
            if (index >= 0 && index < changes.size()) notice = ShareCode.copyToClipboard(detail(changes.get(index))) ? "Full row copied." : "Clipboard unavailable.";
        }
        return true;
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll = (int) Math.max(0, Math.min(maximumScroll(), scroll - vertical * ROW)); return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> { onClose(); return true; }
            case GLFW.GLFW_KEY_DOWN -> scroll += ROW;
            case GLFW.GLFW_KEY_UP -> scroll -= ROW;
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll += listBottom() - listTop();
            case GLFW.GLFW_KEY_PAGE_UP -> scroll -= listBottom() - listTop();
            case GLFW.GLFW_KEY_HOME -> scroll = 0;
            case GLFW.GLFW_KEY_END -> scroll = maximumScroll();
            default -> { return super.keyPressed(event); }
        }
        scroll = Math.max(0, Math.min(maximumScroll(), scroll)); return true;
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
