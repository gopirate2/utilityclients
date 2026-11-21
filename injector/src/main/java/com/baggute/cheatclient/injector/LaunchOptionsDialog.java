package com.baggute.cheatclient.injector;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

final class LaunchOptionsDialog {
    private LaunchOptionsDialog() {}
    static void show(JFrame owner, LaunchSettings initial, Consumer<LaunchSettings> save,
            Consumer<String> integration, Runnable reset) {
        JDialog dialog = new JDialog(owner, "UtilityClient launch options", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new GridBagLayout()); body.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        body.setBackground(new Color(0x181818));
        JCheckBox auto = check("Auto inject when Minecraft starts", initial.autoInject);
        JCheckBox repeat = check("Inject again after the game restarts", initial.reinjectOnRestart);
        JCheckBox minimized = check("Start the injector minimized", initial.startMinimized);
        JCheckBox multiple = check("Use the newest game when several match", initial.allowMultiple);
        JSpinner warmup = new JSpinner(new SpinnerNumberModel(initial.warmupSeconds, 0, 120, 1));
        JSpinner interval = new JSpinner(new SpinnerNumberModel(initial.scanIntervalMs, 500, 15000, 250));
        JTextField directory = new JTextField(initial.gameDirectory, 26);
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 8, 0);
        for (JCheckBox box : new JCheckBox[]{auto, repeat, minimized, multiple}) { body.add(box, c); c.gridy++; }
        addRow(body, c, "Game startup wait (seconds)", warmup);
        addRow(body, c, "Scan interval (milliseconds)", interval);
        JPanel location = new JPanel(new BorderLayout(8, 0)); location.setOpaque(false); location.add(directory, BorderLayout.CENTER);
        JButton browse = new JButton("Browse"); location.add(browse, BorderLayout.EAST);
        browse.addActionListener(event -> {
            JFileChooser picker = new JFileChooser(); picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (picker.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) directory.setText(picker.getSelectedFile().getAbsolutePath());
        });
        addRow(body, c, "Game folder (blank matches any)", location);
        JLabel explanation = new JLabel("Multiple games pause auto-injection unless the newest-game option is selected.");
        explanation.setForeground(new Color(0xAAAAAA)); explanation.setFont(explanation.getFont().deriveFont(11f));
        c.gridx = 0; c.gridwidth = 2; body.add(explanation, c); c.gridy++;
        JPanel links = new JPanel(new GridLayout(2, 2, 8, 8)); links.setOpaque(false);
        for (String[] option : new String[][]{{"Desktop Shortcut", "create:desktop"}, {"Start Menu Shortcut", "create:start-menu"},
                {"Enable Windows Startup", "create:startup"}, {"Disable Windows Startup", "remove:startup"}}) {
            JButton button = new JButton(option[0]); button.setEnabled(WindowsIntegration.available());
            button.addActionListener(event -> integration.accept(option[1])); links.add(button);
        }
        c.weighty = 0; body.add(links, c); c.gridy++;
        if (!WindowsIntegration.available()) {
            JLabel hint = new JLabel("Windows integration controls require the installed launcher."); hint.setForeground(new Color(0xAAAAAA));
            body.add(hint, c); c.gridy++;
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.setOpaque(false);
        JButton resetButton = new JButton("Reset Attempts"), cancel = new JButton("Close"), apply = new JButton("Save Options");
        resetButton.addActionListener(event -> reset.run()); cancel.addActionListener(event -> dialog.dispose());
        apply.addActionListener(event -> {
            LaunchSettings value = new LaunchSettings(); value.autoInject = auto.isSelected(); value.reinjectOnRestart = repeat.isSelected();
            value.startMinimized = minimized.isSelected(); value.allowMultiple = multiple.isSelected();
            value.warmupSeconds = (int) warmup.getValue(); value.scanIntervalMs = (int) interval.getValue(); value.gameDirectory = directory.getText().strip();
            save.accept(value);
        });
        actions.add(resetButton); actions.add(cancel); actions.add(apply); body.add(actions, c);
        dialog.setContentPane(body); dialog.getRootPane().setDefaultButton(apply);
        dialog.pack(); dialog.setMinimumSize(new Dimension(690, 430)); dialog.setSize(720, 470);
        dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }
    private static JCheckBox check(String text, boolean selected) {
        JCheckBox value = new JCheckBox(text, selected); value.setOpaque(false); value.setForeground(Color.WHITE); return value;
    }
    private static void addRow(JPanel panel, GridBagConstraints c, String text, JComponent value) {
        c.gridx = 0; c.gridwidth = 1; c.weightx = 0; JLabel label = new JLabel(text); label.setForeground(Color.WHITE); panel.add(label, c);
        c.gridx = 1; c.weightx = 1; panel.add(value, c); c.gridy++;
    }
}
