package com.baggute.cheatclient.injector;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Drag, maximize/restore and edge resizing for the injector's custom title bar. */
final class WindowChrome {
    static final int EDGE_WIDTH = 7;
    private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    private final JFrame frame;
    private Rectangle windowedBounds;
    private boolean maximized;

    WindowChrome(JFrame frame, JComponent titleBar) {
        this.frame = frame;
        frame.setResizable(true);
        installDrag(titleBar);
        installResize();
    }

    static Rectangle usableBounds(JFrame frame) {
        var configuration = frame.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    static void sizeInitially(JFrame frame) {
        Rectangle work = usableBounds(frame);
        Dimension minimum = new Dimension(Math.min(960, work.width), Math.min(640, work.height));
        frame.setMinimumSize(minimum);
        int width = Math.max(minimum.width, Math.min(1440, work.width * 9 / 10));
        int height = Math.max(minimum.height, Math.min(900, work.height * 9 / 10));
        frame.setBounds(work.x + (work.width - width) / 2, work.y + (work.height - height) / 2, width, height);
    }

    boolean isMaximized() { return maximized; }

    void toggleMaximized() {
        if (maximized) {
            maximized = false;
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(windowedBounds);
        } else {
            windowedBounds = frame.getBounds();
            maximized = true;
            // Undecorated native peers do not reliably retain restore geometry.
            frame.setExtendedState(JFrame.NORMAL);
            frame.setBounds(usableBounds(frame));
        }
        frame.setCursor(Cursor.getDefaultCursor());
        frame.getGlassPane().setCursor(Cursor.getDefaultCursor());
    }

    private void installDrag(JComponent titleBar) {
        MouseAdapter drag = new MouseAdapter() {
            private Point offset;

            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event))
                    offset = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), frame);
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (offset == null) return;
                Point screen = event.getLocationOnScreen();
                if (maximized) {
                    double fraction = offset.x / (double) Math.max(1, frame.getWidth());
                    toggleMaximized();
                    offset = new Point((int) (frame.getWidth() * fraction), Math.min(offset.y, 40));
                }
                frame.setLocation(screen.x - offset.x, screen.y - offset.y);
            }

            @Override public void mouseReleased(MouseEvent event) { offset = null; }

            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2) toggleMaximized();
            }
        };
        attachDrag(titleBar, drag);
    }

    private static void attachDrag(Component component, MouseAdapter drag) {
        if (component instanceof AbstractButton || component instanceof JTextComponent
                || component instanceof JComboBox<?> || component instanceof JTable) return;
        component.addMouseListener(drag);
        component.addMouseMotionListener(drag);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) attachDrag(child, drag);
    }

    static int edgeAt(int x, int y, int width, int height) {
        int edge = 0;
        if (x < 0 || y < 0 || x >= width || y >= height) return edge;
        if (x < EDGE_WIDTH) edge |= LEFT;
        else if (x >= width - EDGE_WIDTH) edge |= RIGHT;
        if (y < EDGE_WIDTH) edge |= TOP;
        else if (y >= height - EDGE_WIDTH) edge |= BOTTOM;
        return edge;
    }

    static Rectangle resizeBounds(Rectangle original, Point delta, int edge, Dimension minimum) {
        Rectangle result = new Rectangle(original);
        if ((edge & LEFT) != 0) {
            result.width = Math.max(minimum.width, original.width - delta.x);
            result.x = original.x + original.width - result.width;
        } else if ((edge & RIGHT) != 0) result.width = Math.max(minimum.width, original.width + delta.x);
        if ((edge & TOP) != 0) {
            result.height = Math.max(minimum.height, original.height - delta.y);
            result.y = original.y + original.height - result.height;
        } else if ((edge & BOTTOM) != 0) result.height = Math.max(minimum.height, original.height + delta.y);
        return result;
    }

    private static int cursorFor(int edge) {
        return switch (edge) {
            case LEFT -> Cursor.W_RESIZE_CURSOR;
            case RIGHT -> Cursor.E_RESIZE_CURSOR;
            case TOP -> Cursor.N_RESIZE_CURSOR;
            case BOTTOM -> Cursor.S_RESIZE_CURSOR;
            case LEFT | TOP -> Cursor.NW_RESIZE_CURSOR;
            case RIGHT | TOP -> Cursor.NE_RESIZE_CURSOR;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE_CURSOR;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        };
    }

    private void installResize() {
        JComponent edges = new JComponent() {
            @Override public boolean contains(int x, int y) {
                // Interior clicks pass through to the existing controls.
                return frame.isResizable() && !maximized && edgeAt(x, y, getWidth(), getHeight()) != 0;
            }
        };
        edges.setOpaque(false);
        MouseAdapter resize = new MouseAdapter() {
            private Point origin;
            private Rectangle bounds;
            private int edge;

            @Override public void mouseMoved(MouseEvent event) {
                edges.setCursor(Cursor.getPredefinedCursor(cursorFor(edgeAt(event.getX(), event.getY(), edges.getWidth(), edges.getHeight()))));
            }

            @Override public void mouseExited(MouseEvent event) {
                if (origin == null) edges.setCursor(Cursor.getDefaultCursor());
            }

            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || maximized) return;
                edge = edgeAt(event.getX(), event.getY(), edges.getWidth(), edges.getHeight());
                if (edge == 0) return;
                origin = event.getLocationOnScreen();
                bounds = frame.getBounds();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (origin == null || maximized) return;
                Point now = event.getLocationOnScreen();
                frame.setBounds(resizeBounds(bounds, new Point(now.x - origin.x, now.y - origin.y), edge, frame.getMinimumSize()));
            }

            @Override public void mouseReleased(MouseEvent event) { origin = null; }
        };
        edges.addMouseListener(resize);
        edges.addMouseMotionListener(resize);
        frame.setGlassPane(edges);
        edges.setVisible(true);
    }
}
