// language: Java, file: TerminalScreen.java, target: Fabric 26.2
package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.config.KitManager;
import com.baggute.cheatclient.config.ShareCode;
import com.baggute.cheatclient.injector.InjectorBridge;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.stealth.LogManager;
import com.baggute.cheatclient.stealth.ScreenBypass;
import com.baggute.cheatclient.stealth.StealthConfig;
import com.baggute.cheatclient.stealth.StealthNative;
import com.baggute.cheatclient.settings.Setting;
import com.baggute.cheatclient.util.ChatCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader-style terminal (tilde key): a compact command line over the game.
 *
 * <p>Everything the GUI can do to modules and the HUD is reachable here, which is
 * also the quickest way to verify a build without opening menus.
 *
 * <p>The log scrolls. Output is 200 lines deep and the window shows maybe
 * eighteen, so the view is pinned to the bottom until the wheel or page keys move
 * it back, and snaps forward again when a command writes something new.
 */
public class TerminalScreen extends Screen {

    private static final int MAX_LINES = 200;

    private final List<String> log = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private String input = "";
    private int historyIndex = -1;

    /** Lines scrolled back from the newest entry; 0 is pinned to the bottom. */
    private int logScroll;
    /** Rows the current window height can show, refreshed every frame. */
    private int logVisible = 1;

    public TerminalScreen() {
        super(Component.literal("UtilityClient Terminal"));
        say("UtilityClient terminal - type 'help' for commands");
        say("session: " + InjectorBridge.describe());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiRenderer.refreshTheme();
        g.fill(0, 0, this.width, this.height, 0x99000000);

        int w = Math.min(this.width - 40, 620);
        int h = Math.min(this.height - 40, 300);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;

        Ui.shadow(g, x, y, w, h);
        Ui.frame(g, x, y, w, h, 0xF30A0C10);

        g.text(this.font, "TERMINAL", x + 10, y + 7, Ui.TEXT, true);
        int statusColor = InjectorBridge.isInjected() ? Ui.GREEN : Ui.MUTED;
        String status = InjectorBridge.label();
        g.text(this.font, status, x + w - 10 - this.font.width(status), y + 7, statusColor, false);
        g.fill(x + 8, y + 18, x + w - 8, y + 19, Ui.SOFT);
        Ui.hGradient(g, x + 8, x + 100, y + 18, y + 19, Ui.accentFaded(0.8f), 0x0029E0FF);

        int inputY = y + h - 20;
        g.fill(x + 6, inputY - 2, x + w - 6, inputY - 1, Ui.SOFT);

        int lineHeight = 10;
        int viewTop = y + 24;
        int viewBottom = inputY - 4;
        int visible = Math.max(1, (viewBottom - viewTop) / lineHeight);
        logVisible = visible;

        int maxScroll = Math.max(0, log.size() - visible);
        if (logScroll > maxScroll) logScroll = maxScroll;
        if (logScroll < 0) logScroll = 0;

        int start = Math.max(0, log.size() - visible - logScroll);
        int end = Math.min(log.size(), start + visible);
        int ty = viewTop;
        for (int i = start; i < end; i++) {
            g.text(this.font, log.get(i), x + 10, ty, Ui.DIM, false);
            ty += lineHeight;
        }

        drawScrollbar(g, x + w - 10, viewTop, visible * lineHeight, maxScroll);

        g.text(this.font, ">", x + 10, inputY + 1, Ui.accentFaded(0.95f), false);
        String caret = input + "_";
        g.text(this.font, caret, x + 20, inputY + 1, Ui.TEXT, false);
    }

    /**
     * Two-pixel scrollbar beside the log.
     *
     * <p>The thumb sits at the bottom when {@code logScroll} is zero because the
     * view is pinned to the newest line, so the offset is subtracted from the
     * travel rather than added. Hidden entirely when the log fits, which is the
     * common case on a fresh screen.
     */
    private void drawScrollbar(GuiGraphicsExtractor g, int barX, int top, int height, int maxScroll) {
        if (maxScroll <= 0) return;
        g.fill(barX, top, barX + 2, top + height, Ui.SOFT);
        int total = log.size();
        int thumb = Math.max(12, height * logVisible / Math.max(1, total));
        int travel = Math.max(0, height - thumb);
        int offset = (int) ((long) travel * logScroll / Math.max(1, maxScroll));
        int thumbY = top + travel - offset;
        g.fill(barX, thumbY, barX + 2, thumbY + thumb, Ui.accentFaded(0.85f));
    }

    @Override
    public boolean mouseScrolled(double screenX, double screenY, double hAmount, double vAmount) {
        // Three lines a notch: one line per notch makes a 200-line log feel stuck.
        int step = (int) Math.round(vAmount) * 3;
        if (step == 0) step = vAmount > 0 ? 3 : (vAmount < 0 ? -3 : 0);
        scrollLog(step);
        return true;
    }

    private void scrollLog(int delta) {
        int maxScroll = Math.max(0, log.size() - logVisible);
        logScroll = Math.max(0, Math.min(maxScroll, logScroll + delta));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            run(input);
            input = "";
            historyIndex = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!input.isEmpty()) input = input.substring(0, input.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            if (!history.isEmpty()) {
                if (historyIndex < 0) historyIndex = history.size() - 1;
                else if (historyIndex > 0) historyIndex--;
                input = history.get(historyIndex);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            if (historyIndex >= 0 && historyIndex < history.size() - 1) {
                historyIndex++;
                input = history.get(historyIndex);
            } else {
                historyIndex = -1;
                input = "";
            }
            return true;
        }

        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            scrollLog(logVisible - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollLog(-(logVisible - 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            logScroll = Integer.MAX_VALUE;
            scrollLog(0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            logScroll = 0;
            return true;
        }

        return true;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (event.codepoint() >= 0x20 && event.codepoint() != 0x7F)
            input += event.codepointAsString();
        return true;
    }

    // ---- commands ----

    private void run(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return;
        say("> " + line);
        history.add(line);

        String[] parts = line.split("\\s+");
        String command = parts[0].toLowerCase();

        switch (command) {
            case "help" -> {
                say("modules [cat]      list modules");
                say("on|off|toggle <m>  change a module");
                say("bind <m> <keycode>  set the toggle key (-1 clears)");
                say("kit save <name>    store the inventory as a kit");
                say("kit load <name>    put a saved kit back");
                say("kit import <name>  import a kit code or JSON from the clipboard");
                say("kit list           kits on disk");
                say("hud list|add <w>|remove <w>|reset");
                say("config save|load   write or read the config");
                say("panic              disable everything");
                say("stealth [what]     status|bypass|overlay|exclude|guard|menus|diag|wipe|key <code>");
                say("status             loader + module summary");
                say("version            client, minecraft, loader, fabric api, build");
                say("clear              empty the log");
                say("scroll: wheel, page up/down, home/end");
            }
            case "modules" -> listModules(parts.length > 1 ? parts[1] : null);
            case "on", "off", "toggle" -> setModule(command, parts);
            case "bind" -> bind(parts);
            case "kit", "kits" -> kit(parts);
            case "hud" -> hud(parts);
            case "config" -> config(parts);
            case "panic" -> panic();
            case "stealth" -> stealth(parts);
            case "status" -> status();
            case "version" -> version();
            case "clear" -> {
                log.clear();
                logScroll = 0;
            }
            default -> say("unknown command: " + command + " (try 'help')");
        }
    }

    /** Same lines as {@code .version} in chat; the terminal is the other front-end. */
    private void version() {
        for (String line : com.baggute.cheatclient.util.VersionInfo.lines()) {
            say(line);
        }
    }

    private void listModules(String category) {
        if (UtilityClient.moduleManager == null) return;
        int shown = 0;
        for (Module module : UtilityClient.moduleManager.getModules()) {
            if (category != null
                    && !module.getCategory().name().equalsIgnoreCase(category)) continue;
            say((module.isEnabled() ? "[on]  " : "[off] ") + module.getName()
                    + "  (" + module.getCategory().getDisplayName() + ")");
            shown++;
        }
        say(shown + " module(s)");
    }

    private void setModule(String command, String[] parts) {
        if (parts.length < 2) { say("usage: " + command + " <module>"); return; }
        Module module = UtilityClient.moduleManager.getByName(parts[1]);
        if (module == null) { say("no module named '" + parts[1] + "'"); return; }
        switch (command) {
            case "on" -> module.setEnabled(true);
            case "off" -> module.setEnabled(false);
            default -> module.toggle();
        }
        say(module.getName() + " is now " + (module.isEnabled() ? "on" : "off"));
    }

    private void bind(String[] parts) {
        if (parts.length < 3) { say("usage: bind <module> <keycode>  (-1 clears)"); return; }
        Module module = UtilityClient.moduleManager.getByName(parts[1]);
        if (module == null) { say("no module named '" + parts[1] + "'"); return; }
        int code;
        try {
            code = Integer.parseInt(parts[2]);
        } catch (NumberFormatException failure) {
            say("keycode must be a number, e.g. 82 for R");
            return;
        }
        for (Setting setting : module.getSettings()) {
            if (setting instanceof KeybindSetting keybind && "keybind".equals(keybind.getName())) {
                keybind.setKey(code);
                say(module.getName() + " bind = " + code);
                return;
            }
        }
        say("module has no toggle bind");
    }

    /**
     * Kits from the terminal.
     *
     * <p>Names may contain spaces - {@code kit save pvp loadout} stores
     * {@code pvp_loadout}, the same sanitisation the GUI field goes through - so
     * everything past the verb is joined rather than read as one token.
     *
     * <p>Save and load also write the {@code [UtilityClient]} line to chat, which
     * is the record the terminal log cannot provide once the screen is closed.
     */
    private void kit(String[] parts) {
        if (parts.length < 2) {
            say("usage: kit save|load|import|list <name>");
            return;
        }
        String action = parts[1].toLowerCase();

        if (action.equals("list")) {
            List<String> names = KitManager.list();
            if (names.isEmpty()) {
                say("no kits saved");
                return;
            }
            for (String name : names) say("  " + name);
            say(names.size() + " kit(s)");
            return;
        }

        if (parts.length < 3) {
            say("usage: kit " + action + " <name>");
            return;
        }

        StringBuilder joined = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(parts[i]);
        }
        String name = joined.toString();

        switch (action) {
            case "import" -> {
                if (KitManager.exists(name)) {
                    say("kit already exists; use a different name");
                    return;
                }
                boolean ok = KitManager.importCode(name, ShareCode.readClipboard());
                say(ok ? "kit imported: " + name : "import failed: copy a valid kit code or JSON and use a valid name");
            }
            case "save", "store" -> {
                boolean ok = KitManager.saveCurrent(name);
                if (ok) {
                    say("kit saved: " + name);
                    ChatCommands.say("[UtilityClient] Kit saved: " + name, ChatFormatting.AQUA);
                } else {
                    say("kit save failed: " + name + " (no world loaded)");
                }
            }
            case "load", "equip" -> {
                int missing = KitManager.load(name);
                if (missing < 0) {
                    say("kit not found: " + name);
                    return;
                }
                say(missing == 0
                        ? "kit loaded: " + name
                        : "kit loaded: " + name + " (" + missing + " slots unfilled)");
                ChatCommands.say("[UtilityClient] Kit loaded: " + name, ChatFormatting.AQUA);
            }
            default -> say("usage: kit save|load|import|list <name>");
        }
    }

    private void hud(String[] parts) {
        Hud hud = UtilityClient.moduleManager.getHud();
        if (hud == null) { say("hud unavailable"); return; }
        if (parts.length < 2) { say("usage: hud list|add <widget>|remove <widget>|reset"); return; }

        switch (parts[1].toLowerCase()) {
            case "list" -> {
                for (Hud.Widget widget : Hud.Widget.values()) {
                    say((hud.isWidgetEnabled(widget) ? "[on]  " : "[off] ") + widget.key()
                            + "  " + widget.title());
                }
            }
            case "add" -> {
                if (parts.length < 3) { say("usage: hud add <widget>"); return; }
                Hud.Widget widget = Hud.Widget.byKey(parts[2]);
                if (widget == null) { say("unknown widget '" + parts[2] + "'"); return; }
                hud.addWidget(widget);
                say("added " + widget.title());
            }
            case "remove" -> {
                if (parts.length < 3) { say("usage: hud remove <widget>"); return; }
                Hud.Widget widget = Hud.Widget.byKey(parts[2]);
                if (widget == null) { say("unknown widget '" + parts[2] + "'"); return; }
                hud.removeWidget(widget);
                say("removed " + widget.title());
            }
            case "reset" -> {
                hud.resetLayout();
                for (Hud.Widget widget : Hud.Widget.values()) hud.removeWidget(widget);
                say("hud reset (all widgets hidden)");
            }
            default -> say("usage: hud list|add <widget>|remove <widget>|reset");
        }
    }

    private void config(String[] parts) {
        if (parts.length < 2) { say("usage: config save|load"); return; }
        ConfigManager manager = UtilityClient.configManager();
        if (manager == null) { say("config unavailable"); return; }
        switch (parts[1].toLowerCase()) {
            case "save" -> {
                manager.save();
                say("config saved");
            }
            case "load" -> {
                manager.load();
                say("config loaded");
            }
            default -> say("usage: config save|load");
        }
    }

    private void panic() {
        int disabled = 0;
        for (Module module : UtilityClient.moduleManager.getModules()) {
            if (module.isEnabled()) {
                module.setEnabled(false);
                disabled++;
            }
        }
        UtilityClient.saveConfig();
        say("panic: " + disabled + " module(s) off");
    }

    /**
     * The stealth layer's switches, which live outside the module system.
     *
     * <p>Read-only except for the toggles: whatever is set here is what
     * {@code StealthConfig} persists, so a session can be corrected from the
     * terminal without editing a file by hand.
     */
    private void stealth(String[] parts) {
        StealthConfig config = StealthConfig.get();
        String what = parts.length > 1 ? parts[1].toLowerCase() : "status";

        switch (what) {
            case "status" -> {
                say("bypass mode:  " + (config.bypassMode() ? "on (hud never drawn)" : "off"));
                say("defer overlay: " + (config.screenBypass() ? "on" : "off (suppress instead)"));
                say("wipe on exit:  " + (config.wipeOnExit() ? "on" : "off"));
                say("panic key:     " + config.panicKey());
                say("capture now:   " + (ScreenBypass.captureActive()
                        ? "detected (" + ScreenBypass.captureName() + ")" : "none"));
                say("hud window:    " + (ScreenBypass.overlayVisible()
                        ? "ACTIVE (game stays in the capture, HUD is yours alone)"
                        : (config.captureExclusion()
                                ? "whole-window mode (capture sees black)"
                                : "idle (opens when a recorder starts)")));
                say("overlay state: " + com.baggute.cheatclient.render.HudOverlay.lastError());
                say("game excluded: " + ScreenBypass.exclusionApplied()
                        + "   (" + ScreenBypass.diagnostic() + ")");
                say("excl. detail:  " + ScreenBypass.diagnostic());
                say("menus hidden:  " + (StealthConfig.get().hideClientScreens() ? "on" : "off")
                        + ", excluded now: " + ScreenBypass.screenExcluded()
                        + "   (" + ScreenBypass.screenDiagnostic() + ")");
                say("native hook:   " + (StealthNative.ready() ? "loaded" : "absent")
                        + " (" + StealthNative.lastError() + ")");
                say("hidden now:    " + (ScreenBypass.panicHidden() ? "yes" : "no"));
                say("log:           " + LogManager.logDir());
            }
            case "bypass" -> {
                config.setBypassMode(!config.bypassMode());
                say("bypass mode " + (config.bypassMode() ? "on" : "off"));
            }
            case "overlay" -> {
                config.setScreenBypass(!config.screenBypass());
                say("deferred overlay " + (config.screenBypass() ? "on" : "off"));
            }
            case "exclude" -> {
                config.setCaptureExclusion(!config.captureExclusion());
                say("window exclusion " + (config.captureExclusion() ? "on" : "off")
                        + " (applies next tick)");
            }
            case "wipe" -> {
                config.setWipeOnExit(!config.wipeOnExit());
                say("wipe on exit " + (config.wipeOnExit() ? "on" : "off"));
            }
            case "key" -> {
                if (parts.length < 3) { say("usage: stealth key <glfw keycode>"); return; }
                int code;
                try {
                    code = Integer.parseInt(parts[2]);
                } catch (NumberFormatException failure) {
                    say("keycode must be a number, e.g. 344 for right shift");
                    return;
                }
                if (code < 0 || code > GLFW.GLFW_KEY_LAST) { say("keycode out of range"); return; }
                config.setPanicKey(code);
                say("panic key = " + code);
            }
            case "menus" -> {
                // Default is now off: hiding a menu blacks out the entire game in the
                // capture, which is worse than the menu being visible for most users.
                boolean next = !config.hideClientScreens();
                config.setHideClientScreens(next);
                say("client menus in capture: " + (next
                        ? "hidden (the whole game goes black while one is open)"
                        : "visible"));
            }
            case "guard" -> {
                config.setCaptureGuard(!config.captureGuard());
                say("capture guard " + (config.captureGuard() ? "on" : "off")
                        + (config.captureGuard()
                                ? " (hud moves to the excluded window even without a detected recorder)"
                                : ""));
            }
            case "diag" -> {
                long gameHwnd = com.baggute.cheatclient.render.CaptureGuard
                        .hwndOf(Minecraft.getInstance().getWindow());
                say("windows api:   " + (com.baggute.cheatclient.render.CaptureGuard.supported()
                        ? "available" : "unavailable"));
                say("game hwnd:     " + Long.toHexString(gameHwnd)
                        + "  affinity=" + com.baggute.cheatclient.render.CaptureGuard
                                .readAffinity(gameHwnd)
                        + " (0x11 = excluded)");
                say("overlay hwnd:  " + Long.toHexString(
                                com.baggute.cheatclient.render.HudOverlay.hwndValue())
                        + "  affinity=" + com.baggute.cheatclient.render.HudOverlay.affinity());
                say("overlay state: " + com.baggute.cheatclient.render.HudOverlay.lastError()
                        + ", visible=" + com.baggute.cheatclient.render.HudOverlay.visible());
                say("capture:       " + (ScreenBypass.captureActive()
                        ? "detected (" + ScreenBypass.captureName() + ")" : "not detected")
                        + ", guard=" + config.captureGuard());
                say("menus:         hide=" + config.hideClientScreens()
                        + ", excluded=" + ScreenBypass.screenExcluded());
                say("whole window:  " + config.captureExclusion());
            }
            case "hide" -> {
                ScreenBypass.togglePanic();
                say("hidden: " + (ScreenBypass.panicHidden() ? "yes" : "no"));
            }
            default -> say("usage: stealth [status|bypass|overlay|exclude|guard|menus|diag|wipe|key <code>|hide]");
        }
    }

    private void status() {
        say("loader: " + InjectorBridge.describe());
        say("modules: " + UtilityClient.moduleManager.getModules().size());
        say("binds: " + UtilityClient.moduleManager.getBoundModules().size());
        Hud hud = UtilityClient.moduleManager.getHud();
        if (hud != null) say("hud widgets: " + hud.visibleWidgetCount() + "/" + Hud.Widget.values().length);
        say("fps: " + Minecraft.getInstance().getFps());
    }

    private void say(String text) {
        log.add(text);
        while (log.size() > MAX_LINES) log.remove(0);
        // New output pulls the view back to the newest line; a log that keeps
        // writing while you are reading three pages up is unreadable.
        logScroll = 0;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
