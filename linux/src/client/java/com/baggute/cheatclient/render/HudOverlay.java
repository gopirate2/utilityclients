// language: Java, file: HudOverlay.java, target: Fabric 26.2 (JDK 25, JNA)
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.stealth.LogManager;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A second, capture-excluded window that carries the HUD, so the game itself can
 * stay fully visible in a recording.
 *
 * <p>Windows excludes <em>windows</em>, not layers of a window. Marking the
 * Minecraft window {@code WDA_EXCLUDEFROMCAPTURE} therefore blanks the whole game
 * in the capture, which is not what a stream needs. This class puts the client's
 * widgets in their own top-level window, marked excluded, floating over the game's
 * client area.
 *
 * <p><b>Not a layered window.</b> The first version used
 * {@code WS_EX_LAYERED} with {@code UpdateLayeredWindow}, which gives real
 * per-pixel transparency — and which {@code SetWindowDisplayAffinity} refuses.
 * Measured with {@code tools/CaptureProbe.java}: the same call returns true on a
 * plain window and false on a layered one, with {@code GetLastError() == 0}, so
 * there is nothing to report and nothing to retry. A window that cannot be
 * excluded cannot carry a hidden HUD, so the overlay is now a plain
 * {@code WS_POPUP} window whose visible area is a window region — the union of the
 * widget rectangles. Outside those rectangles the window does not exist at all, so
 * the game shows through; inside them the HUD is painted on a solid panel. The
 * cost is opaque widget backgrounds instead of transparent ones, which is a fair
 * trade for an overlay a capture cannot read.
 *
 * <p>Fail closed: the window is only shown if the exclusion call succeeded.
 */
public final class HudOverlay {

    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_NOACTIVATE = 0x08000000;

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;

    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int SW_HIDE = 0;

    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    private static final int BI_RGB = 0;
    private static final int DIB_RGB_COLORS = 0;

    private static final int HWND_TOPMOST = -1;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;

    private static final int RGN_OR = 2;
    private static final int SRCCOPY = 0x00CC0020;

    private static final String CLASS_NAME = "UtilityClientHudOverlay";

    private static final int FONT_SIZE = 12;
    private static final int LINE_HEIGHT = 13;

    /** Opaque panel colour; alpha is meaningless in a non-layered window. */
    private static final int PANEL = 0xFF0E1319;

    private static Pointer hwnd;
    private static Pointer memoryDc;
    private static Pointer bitmap;
    private static Pointer bits;
    private static int width;
    private static int height;

    private static BufferedImage image;
    private static Graphics2D graphics;
    private static int[] pixels;
    private static final List<int[]> rectangles = new ArrayList<>();

    private static volatile boolean excluded;
    private static volatile boolean shown;
    private static volatile boolean failed;
    private static String lastError = "not created";
    private static long lastPaint;
    private static long lastShowAttempt;

    private HudOverlay() {
    }

    /** True while the overlay window is on screen and known to be excluded. */
    public static boolean visible() {
        if (!shown || !excluded || hwnd == null) return false;
        if (CaptureGuard.readAffinity(Pointer.nativeValue(hwnd)) != WDA_EXCLUDEFROMCAPTURE) {
            excluded = false;
            failed = true;
            lastError = "overlay exclusion lost or unreadable";
            hide();
            return false;
        }
        return true;
    }

    /** True when the overlay was attempted and could not be brought up. */
    public static boolean failed() {
        return failed;
    }

    public static String lastError() {
        return lastError;
    }

    public static long lastPaintAt() {
        return lastPaint;
    }

    /** Creates the window once and shows it, excluded. */
    public static boolean show() {
        if (!CaptureGuard.isWindows()) {
            failed = true;
            lastError = "not on Windows";
            return false;
        }
        if (hwnd == null) {
            // A failed creation is retried on a timer, not every tick: the tick loop
            // calls show() twenty times a second and a broken platform would write
            // twenty stack traces a second into the log.
            long now = System.currentTimeMillis();
            if (failed && now - lastShowAttempt < 5_000L) return false;
            lastShowAttempt = now;
            if (!create()) {
                failed = true;
                return false;
            }
        }
        if (!excluded && !CaptureGuard.applyToHwnd(Pointer.nativeValue(hwnd),
                WDA_EXCLUDEFROMCAPTURE)) {
            lastError = "exclusion refused for the overlay window";
            failed = true;
            User32.INSTANCE.ShowWindow(hwnd, SW_HIDE);
            shown = false;
            return false;
        }
        int live = CaptureGuard.readAffinity(Pointer.nativeValue(hwnd));
        if (live != WDA_EXCLUDEFROMCAPTURE) {
            lastError = "exclusion did not stick (affinity=" + live + ")";
            failed = true;
            excluded = false;
            hide();
            return false;
        }
        if (!shown) {
            User32.INSTANCE.ShowWindow(hwnd, SW_SHOWNOACTIVATE);
            shown = true;
            if (CaptureGuard.readAffinity(Pointer.nativeValue(hwnd)) != WDA_EXCLUDEFROMCAPTURE) {
                excluded = false;
                failed = true;
                hide();
                lastError = "exclusion lost when showing overlay";
                return false;
            }
        }
        excluded = true;
        failed = false;
        return true;
    }

    public static void hide() {
        if (hwnd != null && shown) {
            User32.INSTANCE.ShowWindow(hwnd, SW_HIDE);
            shown = false;
        }
    }

    /** Destroys the window and frees the GDI objects. Call on shutdown. */
    public static synchronized void shutdown() {
        if (hwnd != null) {
            User32.INSTANCE.DestroyWindow(hwnd);
            hwnd = null;
        }
        if (memoryDc != null) {
            Gdi32.INSTANCE.DeleteDC(memoryDc);
            memoryDc = null;
        }
        if (bitmap != null) {
            Gdi32.INSTANCE.DeleteObject(bitmap);
            bitmap = null;
        }
        memoryDc = null;
        bitmap = null;
        bits = null;
        image = null;
        graphics = null;
        pixels = null;
        width = 0;
        height = 0;
        shown = false;
        excluded = false;
        rectangles.clear();
    }

    /**
     * Repaints the overlay over the game's client area.
     *
     * <p>Called from the client tick: twenty repaints a second is enough for text,
     * and it keeps every pixel write off the render thread's critical path.
     */
    public static void render(com.mojang.blaze3d.platform.Window window) {
        if (com.baggute.cheatclient.stealth.ScreenBypass.panicHidden()
                || UtilityClient.isUnloaded()
                || com.baggute.cheatclient.stealth.StealthConfig.get().bypassMode()) {
            hide();
            return;
        }
        if (!visible() || window == null) return;

        long gameHwnd = CaptureGuard.hwndOf(window);
        if (gameHwnd == 0L) return;

        Rect client = new Rect();
        if (!User32.INSTANCE.GetClientRect(new Pointer(gameHwnd), client)) return;
        Point origin = new Point();
        if (!User32.INSTANCE.ClientToScreen(new Pointer(gameHwnd), origin)) return;

        int w = Math.max(1, client.right - client.left);
        int h = Math.max(1, client.bottom - client.top);
        if (!ensureSurface(w, h)) return;

        paint();
        applyRegion();

        if (origin.x != lastX || origin.y != lastY || w != lastW || h != lastH) {
            User32.INSTANCE.SetWindowPos(hwnd, new Pointer(HWND_TOPMOST), origin.x, origin.y,
                    w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
            lastX = origin.x;
            lastY = origin.y;
            lastW = w;
            lastH = h;
        }
        blit();
        lastPaint = System.currentTimeMillis();
    }

    private static int lastX = Integer.MIN_VALUE;
    private static int lastY = Integer.MIN_VALUE;
    private static int lastW = -1;
    private static int lastH = -1;

    // ---- painting ----

    private static void paint() {
        Arrays.fill(pixels, PANEL);
        rectangles.clear();

        Graphics2D g = graphics;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("Consolas", Font.BOLD, FONT_SIZE));
        FontMetrics metrics = g.getFontMetrics();

        if (UtilityClient.moduleManager == null) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        double guiScale = (double) width / Math.max(1, mc.getWindow().getGuiScaledWidth());

        Module module = UtilityClient.moduleManager.getByName("Hud");
        if (!(module instanceof Hud hud) || !hud.isEnabled()) {
            // Watermark and ArrayList do not depend on the Hud module; returning
            // here hid them whenever the widget module was off.
            paintWatermark(g, metrics, mc, guiScale);
            paintArrayList(g, metrics, mc, guiScale);
            return;
        }

        for (Hud.Widget widget : Hud.Widget.values()) {
            if (!hud.isWidgetEnabled(widget)) continue;
            List<String> lines = HudRenderer.widgetLines(widget, mc, hud);
            if (lines.isEmpty()) continue;

            double originScale = guiScale * hud.getScale();
            int x = (int) Math.round(hud.getX(widget) * originScale);
            int y = (int) Math.round(hud.getY(widget) * originScale);
            double textScale = originScale * hud.getWidgetScale(widget);
            int textWidth = 0;
            for (String raw : lines) textWidth = Math.max(textWidth, metrics.stringWidth(displayText(raw)));
            int pad = 3;
            rectangles.add(new int[]{Math.max(0, x - pad), Math.max(0, y - pad),
                    Math.min(width, x + (int) Math.ceil(textWidth * textScale) + pad),
                    Math.min(height, y + (int) Math.ceil(lines.size() * LINE_HEIGHT * textScale) + pad)});
            java.awt.geom.AffineTransform previous = g.getTransform();
            try {
                g.translate(x, y);
                g.scale(textScale, textScale);
                g.setColor(new Color(hud.getWidgetColor(widget), true));
                int drawY = 0;
                for (String raw : lines) {
                    g.drawString(displayText(raw), 0, drawY + metrics.getAscent());
                    drawY += LINE_HEIGHT;
                }
            } finally { g.setTransform(previous); }
        }

        // Not widgets, but still client drawing: the game window paints neither of
        // these while the overlay is up, so leaving them out made the watermark and
        // the module list disappear from the screen entirely during a capture.
        paintWatermark(g, metrics, mc, guiScale);
        paintArrayList(g, metrics, mc, guiScale);
    }

    private static void paintWatermark(Graphics2D g, FontMetrics metrics,
                                       net.minecraft.client.Minecraft mc, double guiScale) {
        String text = WatermarkRenderer.line(mc);
        if (text == null) return;
        Module module = UtilityClient.moduleManager.getByName("Watermark");
        if (!(module instanceof com.baggute.cheatclient.module.misc.Watermark watermark)) return;

        double scale = watermark.scale() * guiScale;
        int x = (int) Math.round(watermark.x() * guiScale);
        int y = (int) Math.round(watermark.y() * guiScale);
        int w = (int) Math.ceil(metrics.stringWidth(text) * scale);
        int h = (int) Math.ceil(metrics.getHeight() * scale);

        rectangles.add(new int[]{Math.max(0, x - 2), Math.max(0, y - 2),
                Math.min(width, x + w + 2), Math.min(height, y + h + 2)});

        java.awt.geom.AffineTransform old = g.getTransform();
        g.setColor(new Color(watermark.color(), true));
        g.translate(x, y);
        g.scale(scale, scale);
        g.drawString(text, 0, metrics.getAscent());
        g.setTransform(old);
    }

    private static void paintArrayList(Graphics2D g, FontMetrics metrics,
                                       net.minecraft.client.Minecraft mc, double guiScale) {
        Module module = UtilityClient.moduleManager.getByName("ArrayList");
        if (!(module instanceof com.baggute.cheatclient.module.render.ArrayListModule list)) return;

        List<String> names = list.lines(mc);
        if (names.isEmpty()) return;

        double scale = list.listScale() * guiScale;
        g.setColor(new Color(list.listColor(), true));

        int y = (int) Math.round(4 * guiScale);
        for (String name : names) {
            int w = (int) Math.ceil(metrics.stringWidth(name) * scale);
            int x = width - w - (int) Math.round(3 * guiScale);
            rectangles.add(new int[]{Math.max(0, x - 2), Math.max(0, y - 1),
                    Math.min(width, x + w + 2), Math.min(height, y + (int) Math.ceil(metrics.getHeight() * scale))});

            java.awt.geom.AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.scale(scale, scale);
            g.drawString(name, 0, metrics.getAscent());
            g.setTransform(old);

            y += (int) Math.round(LINE_HEIGHT * scale);
        }
    }

    /** Turns the armour marker row into plain text; the overlay has no item pass. */
    private static String displayText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.charAt(0) != '\u0001') return raw;
        String payload = raw.substring(1);
        int bar = payload.indexOf('|');
        String id = bar < 0 ? payload : payload.substring(0, bar);
        String label = bar < 0 ? "" : payload.substring(bar + 1);
        String pretty = id.replace('_', ' ');
        return label.isEmpty() ? pretty : pretty + " " + label;
    }

    // ---- window, region, DIB ----

    private static synchronized boolean create() {
        try {
            WndProc proc = (h, msg, wParam, lParam) ->
                    User32.INSTANCE.DefWindowProcW(h, msg, wParam, lParam);

            WndClassEx wc = new WndClassEx();
            wc.cbSize = wc.size();
            wc.lpfnWndProc = proc;
            // GetModuleHandleW lives in kernel32, not user32. Declaring it on the
            // user32 binding made every overlay creation throw UnsatisfiedLinkError
            // before RegisterClassExW ran, so the HUD window never existed and the
            // guard suppressed the in-game HUD as well.
            wc.hInstance = Kernel32.INSTANCE.GetModuleHandleW(null);
            wc.lpszClassName = new com.sun.jna.WString(CLASS_NAME);
            if (User32.INSTANCE.RegisterClassExW(wc) == 0 && Native.getLastError() != 1410) {
                lastError = "RegisterClassExW failed: " + Native.getLastError();
                return false;
            }
            PROC = proc;

            // No WS_EX_LAYERED: see the class comment. A layered window cannot be
            // excluded from capture, and an overlay that is not excluded is a leak.
            Pointer handle = User32.INSTANCE.CreateWindowExW(
                    WS_EX_TOPMOST | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                    new com.sun.jna.WString(CLASS_NAME), new com.sun.jna.WString("UtilityClient"), WS_POPUP, 0, 0, 1, 1,
                    null, null, wc.hInstance, null);
            if (handle == null) {
                lastError = "CreateWindowExW failed";
                return false;
            }
            hwnd = handle;
            lastError = "created";
            return true;
        } catch (Throwable failure) {
            lastError = "create failed: " + failure.getClass().getSimpleName();
            LogManager.log("WARN", "hud overlay: " + lastError, failure);
            return false;
        }
    }

    /** Keeps the callback reachable; JNA collects a callback nobody references. */
    private static WndProc PROC;

    /** Builds the visible region from the widget panels painted this frame. */
    private static void applyRegion() {
        if (rectangles.isEmpty()) {
            // Empty region: the window renders nothing and shows nothing.
            Pointer empty = Gdi32.INSTANCE.CreateRectRgn(0, 0, 0, 0);
            User32.INSTANCE.SetWindowRgn(hwnd, empty, true);
            return;
        }

        Pointer combined = Gdi32.INSTANCE.CreateRectRgn(0, 0, 0, 0);
        for (int[] r : rectangles) {
            Pointer piece = Gdi32.INSTANCE.CreateRectRgn(r[0], r[1], r[2], r[3]);
            Gdi32.INSTANCE.CombineRgn(combined, combined, piece, RGN_OR);
            // CombineRgn copies the shape, so the piece is ours to delete.
            Gdi32.INSTANCE.DeleteObject(piece);
        }
        // On success the system owns the region; deleting it here would be a leak
        // or a use-after-free depending on the result, so the handle is dropped.
        User32.INSTANCE.SetWindowRgn(hwnd, combined, true);
    }

    private static boolean ensureSurface(int w, int h) {
        if (w == width && h == height && memoryDc != null) return true;

        if (memoryDc != null) {
            Gdi32.INSTANCE.DeleteDC(memoryDc);
            memoryDc = null;
        }
        if (bitmap != null) {
            Gdi32.INSTANCE.DeleteObject(bitmap);
            bitmap = null;
        }

        Pointer screen = User32.INSTANCE.GetDC(null);
        if (screen == null) return false;
        try {
            memoryDc = Gdi32.INSTANCE.CreateCompatibleDC(screen);
            if (memoryDc == null) return false;

            BitmapInfo info = new BitmapInfo();
            info.header.biSize = info.header.size();
            info.header.biWidth = w;
            info.header.biHeight = -h;
            info.header.biPlanes = 1;
            info.header.biBitCount = 32;
            info.header.biCompression = BI_RGB;
            info.header.biSizeImage = w * h * 4;
            info.colours = new int[1];

            Pointer[] outBits = new Pointer[1];
            bitmap = Gdi32.INSTANCE.CreateDIBSection(memoryDc, info, DIB_RGB_COLORS,
                    outBits, null, 0);
            if (bitmap == null || outBits[0] == null) return false;
            bits = outBits[0];
            Gdi32.INSTANCE.SelectObject(memoryDc, bitmap);
        } finally {
            User32.INSTANCE.ReleaseDC(null, screen);
        }

        width = w;
        height = h;
        image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        graphics = image.createGraphics();
        lastW = -1;
        return true;
    }

    /** Copies the DIB into the window's own DC. The region clips the rest away. */
    private static void blit() {
        if (bits == null || pixels == null) return;
        bits.write(0, pixels, 0, pixels.length);
        Pointer windowDc = User32.INSTANCE.GetDC(hwnd);
        if (windowDc == null) return;
        try {
            boolean ok = Gdi32.INSTANCE.BitBlt(windowDc, 0, 0, width, height,
                    memoryDc, 0, 0, SRCCOPY);
            if (!ok) lastError = "BitBlt failed: " + Native.getLastError();
        } finally {
            User32.INSTANCE.ReleaseDC(hwnd, windowDc);
        }
    }

    /**
     * Re-applies the exclusion and verifies it. DWM can drop the affinity when the
     * window is resized, re-regioned or recomposed, and a dropped affinity is a HUD
     * visible in a recording, so the tick loop calls this once a second.
     */
    public static void reassert() {
        if (hwnd == null) return;
        CaptureGuard.applyToHwnd(Pointer.nativeValue(hwnd), WDA_EXCLUDEFROMCAPTURE);
        excluded = CaptureGuard.readAffinity(Pointer.nativeValue(hwnd)) == WDA_EXCLUDEFROMCAPTURE;
        if (!excluded) {
            failed = true;
            lastError = "exclusion lost on the overlay window";
            hide();
        }
    }

    /** Native handle of the overlay window, 0 when it does not exist. */
    public static long hwndValue() {
        return hwnd == null ? 0L : Pointer.nativeValue(hwnd);
    }

    /** Live display affinity of the overlay window, or -1 when unreadable. */
    public static int affinity() {
        return hwnd == null ? -1 : CaptureGuard.readAffinity(Pointer.nativeValue(hwnd));
    }

    // ---- JNA bindings ----

    public interface WndProc extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        long callback(Pointer hwnd, int message, long wParam, long lParam);
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static final class Rect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    @Structure.FieldOrder({"x", "y"})
    public static final class Point extends Structure {
        public int x;
        public int y;
    }

    @Structure.FieldOrder({"biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
            "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
            "biClrUsed", "biClrImportant"})
    public static final class BitmapInfoHeader extends Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;
    }

    @Structure.FieldOrder({"header", "colours"})
    public static final class BitmapInfo extends Structure {
        public BitmapInfoHeader header = new BitmapInfoHeader();
        public int[] colours = new int[1];
    }

    @Structure.FieldOrder({"cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
            "hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName",
            "lpszClassName", "hIconSm"})
    public static final class WndClassEx extends Structure {
        public int cbSize;
        public int style;
        public WndProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public Pointer hInstance;
        public Pointer hIcon;
        public Pointer hCursor;
        public Pointer hbrBackground;
        public Pointer lpszMenuName;
        public com.sun.jna.WString lpszClassName;
        public Pointer hIconSm;
    }

    public interface User32 extends StdCallLibrary {
        // The W entry points take UTF-16; JNA only encodes String that way when told
        // to, and jna-platform is not a dependency here.
        User32 INSTANCE = Native.load("user32", User32.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-16LE"));

        int RegisterClassExW(WndClassEx wc);

        Pointer CreateWindowExW(int exStyle, com.sun.jna.WString className, com.sun.jna.WString windowName, int style,
                                int x, int y, int width, int height, Pointer parent,
                                Pointer menu, Pointer instance, Pointer param);

        long DefWindowProcW(Pointer hwnd, int message, long wParam, long lParam);

        boolean DestroyWindow(Pointer hwnd);

        boolean ShowWindow(Pointer hwnd, int command);

        boolean SetWindowPos(Pointer hwnd, Pointer after, int x, int y, int cx, int cy,
                             int flags);

        int SetWindowRgn(Pointer hwnd, Pointer region, boolean redraw);

        boolean GetClientRect(Pointer hwnd, Rect rect);

        boolean ClientToScreen(Pointer hwnd, Point point);

        Pointer GetDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer dc);
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetModuleHandleW(com.sun.jna.WString name);
    }

    public interface Gdi32 extends StdCallLibrary {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class);

        Pointer CreateCompatibleDC(Pointer dc);

        boolean DeleteDC(Pointer dc);

        Pointer CreateDIBSection(Pointer dc, BitmapInfo info, int usage,
                                 Pointer[] bits, Pointer section, int offset);

        Pointer SelectObject(Pointer dc, Pointer object);

        boolean DeleteObject(Pointer object);

        Pointer CreateRectRgn(int left, int top, int right, int bottom);

        int CombineRgn(Pointer destination, Pointer source1, Pointer source2, int mode);

        boolean BitBlt(Pointer destination, int x, int y, int width, int height,
                       Pointer source, int sourceX, int sourceY, int rop);
    }
}
