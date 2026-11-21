package com.baggute.cheatclient.injector;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class AppIcons {
    private static final List<Image> IMAGES = load();
    private AppIcons() {}
    private static List<Image> load() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) {
            try (var stream = AppIcons.class.getResourceAsStream("/icons/utilityclient-" + size + ".png")) {
                if (stream != null) { var image = ImageIO.read(stream); if (image != null) images.add(image); }
            } catch (java.io.IOException ignored) { }
        }
        return List.copyOf(images);
    }
    static void apply(JFrame frame) {
        if (IMAGES.isEmpty()) return;
        frame.setIconImages(IMAGES);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                Taskbar.getTaskbar().setIconImage(IMAGES.getLast());
        } catch (RuntimeException ignored) { }
    }
    static Icon preview() {
        return IMAGES.isEmpty() ? null : new ImageIcon(IMAGES.getLast().getScaledInstance(32, 32, Image.SCALE_SMOOTH));
    }
}
