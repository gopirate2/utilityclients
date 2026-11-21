package com.baggute.cheatclient.injector;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.HashSet;

final class WindowChromeChecks {
    static void run() throws Exception {
        Rectangle bounds = new Rectangle(100, 200, 800, 600);
        Dimension minimum = new Dimension(300, 250);
        int left = WindowChrome.edgeAt(0, 300, 800, 600);
        int top = WindowChrome.edgeAt(400, 0, 800, 600);
        int bottomRight = WindowChrome.edgeAt(799, 599, 800, 600);
        require(WindowChrome.resizeBounds(bounds, new Point(-40, 0), left, minimum)
                .equals(new Rectangle(60, 200, 840, 600)), "left resize preserves right edge");
        require(WindowChrome.resizeBounds(bounds, new Point(0, -30), top, minimum)
                .equals(new Rectangle(100, 170, 800, 630)), "top resize preserves bottom edge");
        require(WindowChrome.resizeBounds(bounds, new Point(100, 60), bottomRight, minimum)
                .equals(new Rectangle(100, 200, 900, 660)), "corner expands both dimensions");
        require(WindowChrome.resizeBounds(bounds, new Point(1000, 1000), left | top, minimum)
                .equals(new Rectangle(600, 550, 300, 250)), "minimum size keeps opposite corner anchored");
        var edges = new HashSet<Integer>();
        for (Point point : new Point[]{new Point(0, 0), new Point(400, 0), new Point(799, 0),
                new Point(0, 300), new Point(799, 300), new Point(0, 599), new Point(400, 599), new Point(799, 599)})
            edges.add(WindowChrome.edgeAt(point.x, point.y, 800, 600));
        require(edges.size() == 8 && !edges.contains(0), "all eight resize directions supported");
        require(WindowChrome.edgeAt(100, 100, 800, 600) == 0, "interior does not intercept clicks");

        if (!GraphicsEnvironment.isHeadless()) {
            // Exercise actual Swing dispatch without displaying another window.
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame();
                frame.setUndecorated(true);
                try {
                    JPanel title = new JPanel(null);
                    JLabel label = new JLabel("Title");
                    title.add(label);
                    frame.setContentPane(title);
                    frame.setMinimumSize(new Dimension(300, 250));
                    Rectangle original = new Rectangle(100, 120, 800, 600);
                    frame.setBounds(original);
                    label.setBounds(80, 10, 100, 30);
                    WindowChrome chrome = new WindowChrome(frame, title);
                    chrome.toggleMaximized();
                    require(chrome.isMaximized() && frame.getBounds().equals(WindowChrome.usableBounds(frame)), "maximize uses work area");
                    chrome.toggleMaximized();
                    require(!chrome.isMaximized() && frame.getBounds().equals(original), "restore recovers exact window bounds");

                    var glass = frame.getGlassPane();
                    glass.setSize(frame.getSize());
                    require(!glass.contains(100, 100) && glass.contains(0, 100), "resize glass passes interior input through");
                    glass.dispatchEvent(mouse(glass, MouseEvent.MOUSE_PRESSED, 0, 100, 100, 220, 1));
                    glass.dispatchEvent(mouse(glass, MouseEvent.MOUSE_DRAGGED, -60, 100, 40, 220, 1));
                    glass.dispatchEvent(mouse(glass, MouseEvent.MOUSE_RELEASED, -60, 100, 40, 220, 1));
                    Rectangle resized = new Rectangle(40, 120, 860, 600);
                    require(frame.getBounds().equals(resized), "edge drag changes real frame bounds");
                    chrome.toggleMaximized();
                    chrome.toggleMaximized();
                    require(frame.getBounds().equals(resized), "restore remembers resized window");

                    Point grab = SwingUtilities.convertPoint(label, new Point(5, 5), frame);
                    label.dispatchEvent(mouse(label, MouseEvent.MOUSE_PRESSED, 5, 5, resized.x + grab.x, resized.y + grab.y, 1));
                    label.dispatchEvent(mouse(label, MouseEvent.MOUSE_DRAGGED, 5, 5, resized.x + grab.x + 45, resized.y + grab.y + 20, 1));
                    label.dispatchEvent(mouse(label, MouseEvent.MOUSE_RELEASED, 5, 5, resized.x + grab.x + 45, resized.y + grab.y + 20, 1));
                    require(frame.getLocation().equals(new Point(resized.x + 45, resized.y + 20)), "title child drag keeps correct offset");
                    label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, 5, 5, 0, 0, 2));
                    require(chrome.isMaximized(), "title double click maximizes");
                    label.dispatchEvent(mouse(label, MouseEvent.MOUSE_CLICKED, 5, 5, 0, 0, 2));
                    require(!chrome.isMaximized(), "title double click restores");
                } finally { frame.dispose(); }
            });
        }
        System.out.println("PASS: maximize/restore, remembered resize, eight edges/corners and title dragging");
    }

    private static MouseEvent mouse(java.awt.Component component, int type, int x, int y, int screenX, int screenY, int clicks) {
        return new MouseEvent(component, type, System.currentTimeMillis(), 0, x, y, screenX, screenY, clicks, false, MouseEvent.BUTTON1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
