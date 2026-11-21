package com.baggute.cheatclient.injector;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BorderLayout;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A separate operation display; capture and process-status messages cannot overwrite it. */
final class OperationProgress extends JPanel {
    private final JLabel label = new JLabel("Ready to install or inject");
    private final JProgressBar bar = new JProgressBar(0, 5);

    OperationProgress() {
        super(new BorderLayout(10, 4));
        setOpaque(false);
        label.setForeground(new java.awt.Color(0xABB9C9));
        bar.setPreferredSize(new java.awt.Dimension(140, 8));
        add(label, BorderLayout.CENTER);
        add(bar, BorderLayout.EAST);
        setBorder(new javax.swing.border.EmptyBorder(8, 12, 8, 12));
    }

    void step(int number, String message) {
        bar.setIndeterminate(false);
        bar.setValue(number);
        label.setText(number + "/5  " + message);
        label.setToolTipText(message);
    }

    void finish(boolean ok, String message) {
        bar.setIndeterminate(false);
        if (ok) bar.setValue(5);
        label.setText((ok ? "Complete: " : "Failed: ") + message);
        label.setToolTipText(message);
    }

    /** Correlated target events prevent an older JVM's logs from completing a new operation. */
    static final class Session {
        final String id = java.util.UUID.randomUUID().toString();
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile Event result;
        private int lastStep;

        synchronized Event accept(String line) {
            if (result != null) return null;
            String prefix = "UC_STAGE " + id + " ";
            if (!line.startsWith(prefix)) return null;
            String[] fields = line.substring(prefix.length()).split(" ", 2);
            if (fields.length != 2) return null;
            int step = switch (fields[0]) {
                case "LOADING" -> 3;
                case "INITIALIZING" -> 4;
                case "READY", "AGENT_LOADED", "UNLOADED" -> 5;
                case "FAILED" -> 0;
                default -> -1;
            };
            if (step < 0 || (step > 0 && step < lastStep)) return null;
            String detail;
            try { detail = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) { return null; }
            Event event = new Event(step, !fields[0].equals("FAILED"), step == 0 || step == 5, detail);
            lastStep = Math.max(lastStep, step);
            if (event.terminal()) { result = event; completed.countDown(); }
            return event;
        }

        Event await(long timeoutMillis) throws InterruptedException {
            return completed.await(timeoutMillis, TimeUnit.MILLISECONDS) ? result : null;
        }
    }

    record Event(int step, boolean ok, boolean terminal, String message) { }
}
