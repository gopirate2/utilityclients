package com.baggute.cheatclient.injector;

import com.baggute.cheatclient.injector.agent.HookSpec;
import com.sun.jna.Pointer;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * The window from the launcher.
 *
 * <p>Two ways in, one shell: {@code Running game} attaches to a live JVM and is
 * gone when the game closes; {@code Into a mod} edits a jar in the mods folder so
 * the client loads at every launch, and can put the original back.
 *
 * <p>Painted by hand, flat: graphite panels, one-pixel rules, no rounded corners
 * and no drop shadows. Every platform look and feel injects rounded corners,
 * gradients and inset bevels into menus, scrollbars and tables, and fighting them
 * one UIManager key at a time leaves seams. The components below are local, small
 * and drawn from primitives, so the window reads as the design instead of as a
 * theme that mostly took.
 */
final class InjectorFrame extends JFrame {

    // ---- palette ----

    private static final Color BG = new Color(0x121212);
    private static final Color PANEL = new Color(0x1E1E1E);
    private static final Color PANEL_RAISED = new Color(0x181818);
    private static final Color ROW_SEL = new Color(0x1E1E1E);
    private static final Color BORDER = new Color(0x2A2A2A);
    private static final Color BORDER_SOFT = new Color(0x232323);
    private static final Color CONSOLE_BG = new Color(0x0E0E0E);
    private static final Color FIELD_BG = new Color(0x151515);
    private static final Color TEXT = new Color(0xFFFFFF);
    private static final Color MUTED = new Color(0xAAAAAA);
    private static final Color DIM = new Color(0x8A8A8A);
    private static final Color ACCENT = new Color(0x00E5FF);
    private static final Color ACCENT_HOVER = new Color(0x4DEEFF);
    private static final Color ACCENT_DIM = new Color(0x0E3A40);
    private static final Color WARN = new Color(0xFFC24B);
    private static final Color OK = new Color(0x7BE495);

    // ---- fonts ----

    /**
     * Font families, resolved once against what this machine actually has.
     *
     * <p>{@code new Font("Segoe UI", ...)} does not fail when the family is absent:
     * it silently becomes {@code Dialog}, and {@code Dialog} is what the window was
     * drawing with before. The logical fonts carry their own ascent, hinting and
     * default size per platform, which is why the same label read taller on one row
     * than on the next. Asking for a family that has been checked against the
     * installed list means the name in the source and the face on screen are the
     * same thing, and a machine without Segoe UI gets a real sans instead of Dialog.
     */
    private static final String SANS = firstAvailable(
            "Segoe UI", "Segoe UI Variable Text", "Inter", "Helvetica Neue",
            "Noto Sans", "DejaVu Sans", Font.SANS_SERIF);

    /** One monospace family for the numbers and paths that have to line up. */
    private static final String MONO_FAMILY = firstAvailable(
            "Consolas", "Cascadia Mono", "JetBrains Mono", "Menlo",
            "DejaVu Sans Mono", Font.MONOSPACED);

    private static String firstAvailable(String... candidates) {
        Set<String> installed = new HashSet<>();
        try {
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                installed.add(family.toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException headless) {
            // No font server: the logical families still resolve.
            return Font.SANS_SERIF;
        }
        for (String candidate : candidates) {
            if (candidate.equals(Font.SANS_SERIF) || candidate.equals(Font.MONOSPACED)) {
                return candidate;
            }
            if (installed.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return Font.SANS_SERIF;
    }

    private static final Font UI = new Font(SANS, Font.PLAIN, 12);
    private static final Font SMALL = new Font(SANS, Font.PLAIN, 11);
    private static final Font SMALL_BOLD = new Font(SANS, Font.BOLD, 11);
    private static final Font SECTION = tracked(SMALL_BOLD, 0.10f);
    private static final Font WORDMARK = tracked(new Font(SANS, Font.BOLD, 21), 0.02f);
    private static final Font PID = tracked(new Font(MONO_FAMILY, Font.BOLD, 27), 0.01f);
    private static final Font ACTION = tracked(new Font(SANS, Font.BOLD, 14), 0.22f);
    private static final Font MONO = new Font(MONO_FAMILY, Font.PLAIN, 12);
    private static final Font MONO_BOLD = new Font(MONO_FAMILY, Font.BOLD, 12);

    private static final int LOG_LIMIT = 40_000;
    private static final int AUTO_REFRESH_MS = 5_000;
    private static final Preferences PREFS = Preferences.userNodeForPackage(InjectorFrame.class);

    /** Letter-spacing needs a font attribute; there is no Swing setter for it. */
    private static Font tracked(Font base, float amount) {
        Map<TextAttribute, Object> attributes = new LinkedHashMap<>();
        attributes.put(TextAttribute.TRACKING, amount);
        return base.deriveFont(attributes);
    }

    /**
     * Text smoothing for everything this window draws by hand.
     *
     * <p>Without it a {@code Graphics2D} renders glyph masks with a 1-bit raster:
     * every curve becomes a stair-step, and at 11-12px that is the "jagged" text on
     * the section labels. Fractional metrics are on as well so a Microsoft face
     * keeps its own advances instead of being rounded to whole pixels - rounding is
     * what made two labels of the same size sit at different heights.
     *
     * <p>Deliberately no {@code KEY_STROKE_PURE}: the one-pixel rules and the 1px
     * field borders are drawn at integer coordinates and snapping keeps them a
     * single crisp pixel rather than a two-pixel grey smear.
     */
    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    // ---- console styles ----

    private static final SimpleAttributeSet S_TEXT = style(0xFFFFFF);
    private static final SimpleAttributeSet S_OK = style(0x00E5FF);
    private static final SimpleAttributeSet S_WARN = style(0xFFC24B);
    private static final SimpleAttributeSet S_DIM = style(0x8A8A8A);

    private static SimpleAttributeSet style(int rgb) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, new Color(rgb));
        StyleConstants.setFontFamily(set, MONO_FAMILY);
        StyleConstants.setFontSize(set, 12);
        return set;
    }

    // ---- state ----

    private final ProcessTableModel model = new ProcessTableModel();
    /**
     * Column widths are computed from the viewport on every layout pass.
     *
     * <p>{@code AUTO_RESIZE_OFF} means the table is as wide as its columns, so a
     * {@link ComponentListener} on the viewport was the wrong hook: it ran before
     * the scroll pane had its final size and the table painted at its preferred
     * width, clipped by the viewport. Laying the columns out here always uses the
     * width the table is actually about to be given.
     */
    private final JTable table = new JTable(model) {
        @Override
        public void doLayout() {
            sizeColumns(getParent() == null ? getWidth() : getParent().getWidth());
            super.doLayout();
        }
    };
    private final JTextPane console = new JTextPane();
    private final Search search = new Search();

    private final Field jarField = new Field(24);
    private final Field hookField = new Field(24);
    private final Field modsField = new Field(24);
    private final Field clientField = new Field(24);
    private final Combo hostCombo = new Combo();

    private final Check stealth = new Check("Stealth", true);
    private final Check openGui = new Check("Open GUI", false);
    private final Check follow = new Check("Follow", true);

    private final Tab runningTab = new Tab("RUNNING GAME", true);
    private final Tab modTab = new Tab("INTO A MOD", false);

    private final CardLayout cards = new CardLayout();
    private final JPanel inputs = new JPanel(cards);

    private final FlatButton inject = new FlatButton("INJECT", Kind.SOLID);
    private final FlatButton unload = new FlatButton("UNLOAD", Kind.GHOST);
    private final FlatButton install = new FlatButton("INSTALL", Kind.GHOST);
    private final FlatButton remove = new FlatButton("REMOVE", Kind.GHOST);
    private final FlatButton clear = new FlatButton("CLEAR", Kind.GHOST);
    private final FlatButton launchOptions = new FlatButton("LAUNCH OPTIONS", Kind.GHOST);
    private final JLabel watchState = new JLabel();
    private LaunchSettings launchSettings = new LaunchSettings();
    private final AutoInjectController autoInject = new AutoInjectController();
    private String lastWatchMessage = "";
    private final FlatButton browseJar = new FlatButton("BROWSE", Kind.GHOST);
    private final FlatButton repoJar = new FlatButton("REPO", Kind.GHOST);
    private final FlatButton browseClient = new FlatButton("BROWSE", Kind.GHOST);
    private final FlatButton browseMods = new FlatButton("BROWSE", Kind.GHOST);
    private final IconButton refresh = new IconButton(Glyph.REFRESH, Kind.OUTLINE);

    private final JLabel pidLabel = new JLabel("PID \u2014");
    private final JLabel mainClass = new JLabel("no process selected");
    private final Value clientVersionValue = new Value(false);
    private final Value jvmValue = new Value(false);
    private final Value uptimeValue = new Value(false);
    private final Value heapValue = new Value(false);
    private final Value gameDirValue = new Value(true);
    private final Badge badge = new Badge("NO SELECTION");
    private final JLabel counts = new JLabel("0 JAVA \u00B7 0 MC");
    private final JLabel footerLeft = new JLabel("0 PROCESSES | 0 MINECRAFT");
    private final JLabel status = new JLabel("idle");
    private final OperationProgress operation = new OperationProgress();
    private volatile OperationProgress.Session session;

    /**
     * The capture switch in the header.
     *
     * <p>Local to this window and to nothing else: it asks Windows to leave the
     * injector's own frame out of DWM captures. The client's StreamProof module is a
     * different mechanism in a different process, with its own state and its own
     * file, and neither reaches the other.
     */
    private final HideToggle hideCapture = new HideToggle();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "injector-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * A second thread for release downloads, so a fetch never queues an attach.
     *
     * <p>{@link #worker} is single-threaded and serialises everything it is given.
     * A fetch can sit on a socket for the whole of its two-minute timeout, and on a
     * shared thread that delay would land on the next Inject click - the window
     * would answer "attaching" and then do nothing until the download finished.
     */
    private final ExecutorService fetchWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "injector-fetch");
        thread.setDaemon(true);
        return thread;
    });

    /** One fetch at a time: the button is disabled, this guards the startup path. */
    private final AtomicBoolean fetching = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile int selectionToken;

    private JComponent headerBar;
    private WindowChrome windowChrome;
    private Timer autoRefresh;
    private LogServer logServer;

    static void launch() { launch(false, false, false); }
    static void launch(boolean startup, boolean minimized, boolean watch) {
        // Read once, the first time this JVM paints text. Both must be set before
        // AWT/Swing initialise their font machinery or they are ignored, which is
        // why they are here and not in the frame's constructor: every label, button
        // and table cell then inherits grayscale anti-aliasing instead of the 1-bit
        // raster the default pipeline falls back to on a scaled display.
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // The cross-platform L&F is the fallback and paints the same.
        }
        // The header switch owns the exclusion, so the saved value is the switch's
        // starting position rather than a separate setting that could disagree with
        // it. -Dinjector.visibleInCapture=true forces it off for a support screenshot
        // without rewriting what the user chose.
        boolean hidden = !Boolean.getBoolean("injector.visibleInCapture") && CaptureConfig.load();
        CaptureHider.setEnabled(hidden);

        SwingUtilities.invokeLater(() -> {
            InjectorFrame frame = new InjectorFrame(watch);
            frame.setVisible(true);
            if (minimized || frame.launchSettings.startMinimized) frame.setState(JFrame.ICONIFIED);
        });
    }

    private InjectorFrame() { this(false); }
    private InjectorFrame(boolean watch) {
        super("utilityclient");
        setUndecorated(true);
        setBackground(BG);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(true);
        AppIcons.apply(this);

        startLogChannel();
        buildUi();
        restorePrefs();
        try { launchSettings = LaunchSettings.load(InstallPaths.root()); }
        catch (java.io.IOException failure) { append("[!] " + failure.getMessage()); }
        if (watch) launchSettings.autoInject = true;

        // setSelected does not fire the action listener - only a click or a call to
        // doClick does - so the switch starts where the config file left it without
        // re-running the toggle handler that wrote that value.
        hideCapture.setSelected(CaptureHider.isEnabled());

        if (jarField.getText().isBlank()) {
            SwingUtilities.invokeLater(this::fetchClient);
        }
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                // The peer is created by setVisible, so this is the first moment a
                // handle exists. Applying here means the frame is out of the capture
                // from its first painted pixel instead of one sweep later.
                applyCaptureAffinity();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                savePrefs();
                if (autoRefresh != null) autoRefresh.stop();
                if (logServer != null) logServer.close();
                worker.shutdownNow();
                fetchWorker.shutdownNow();
            }
        });

        refreshTargets();
        loadHosts();
        WindowChrome.sizeInitially(this);
        windowChrome = new WindowChrome(this, headerBar);

        autoRefresh = new Timer(launchSettings.scanIntervalMs, event -> refreshTargets());
        autoRefresh.start();
        updateWatchLabel();
    }

    // ---- layout ----

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createLineBorder(BORDER));
        content.add(headerBar = header(), BorderLayout.NORTH);
        content.add(body(), BorderLayout.CENTER);
        content.add(footer(), BorderLayout.SOUTH);

        setContentPane(content);

        // Both cards exist from the start; CardLayout swaps them on the header tabs.
        inputs.add(runningCard(), "running");
        inputs.add(modCard(), "mod");
        // JPanel is opaque by default; the cards sit directly on the panel colour.
        inputs.setOpaque(false);

        refresh.addActionListener(event -> refreshTargets());
        inject.addActionListener(event -> primaryAction());
        unload.addActionListener(event -> unloadSelected());
        install.addActionListener(event -> installIntoMod());
        remove.addActionListener(event -> removeFromMod());
        clear.addActionListener(event -> console.setText(""));
        launchOptions.addActionListener(event -> showLaunchOptions());
        browseJar.addActionListener(event -> pickFile(jarField, "Java archive", "jar"));
        repoJar.addActionListener(event -> fetchClient());
        hideCapture.addActionListener(event -> applyCaptureToggle(hideCapture.isSelected()));
        browseClient.addActionListener(event -> pickFile(clientField, "Java archive", "jar"));
        browseMods.addActionListener(event -> pickFolder(modsField));
        runningTab.addActionListener(event -> switchMode(true));
        modTab.addActionListener(event -> switchMode(false));
        hostCombo.addActionListener(event -> updateModButtons());

        search.onChange(this::applyFilter);

        getRootPane().registerKeyboardAction((ActionEvent event) -> primaryAction(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                        java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JPanel.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction((ActionEvent event) -> refreshTargets(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), JPanel.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction((ActionEvent event) -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JPanel.WHEN_IN_FOCUSED_WINDOW);
    }

    private JComponent header() {
        JPanel bar = new JPanel(new BorderLayout(20, 0));
        bar.setBackground(BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(12, 22, 12, 16)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(new JLabel(AppIcons.preview()));
        left.add(javax.swing.Box.createHorizontalStrut(10));
        left.add(wordmark());
        left.add(javax.swing.Box.createHorizontalStrut(24));
        left.add(tabs());
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        counts.setFont(tracked(SMALL_BOLD, 0.08f));
        counts.setForeground(MUTED);
        counts.setBorder(new EmptyBorder(0, 0, 0, 2));
        right.add(counts);

        // Before refresh on purpose: it is a standing state, not an action, so it
        // sits with the readouts rather than with the controls that do something.
        right.add(hideCapture);
        refresh.setPreferredSize(new Dimension(38, 34));
        right.add(refresh);
        right.add(windowControls());
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** "UTILITY" white, "//" accent, "CLIENT" white - one word on screen. */
    private JComponent wordmark() {
        JPanel mark = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mark.setOpaque(false);
        mark.add(word("UTILITY", TEXT));
        mark.add(word("//", ACCENT));
        mark.add(word("CLIENT", TEXT));
        return mark;
    }

    private JLabel word(String text, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(WORDMARK);
        label.setForeground(colour);
        return label;
    }

    private JComponent tabs() {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        group.setOpaque(false);
        runningTab.setBorder(new EmptyBorder(0, 0, 0, 0));
        group.add(runningTab);
        group.add(modTab);
        return group;
    }

    private JComponent windowControls() {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        group.setOpaque(false);
        group.add(windowButton(Glyph.MINIMISE, () -> setState(JFrame.ICONIFIED)));
        group.add(windowButton(Glyph.MAXIMISE, () -> windowChrome.toggleMaximized()));
        group.add(windowButton(Glyph.CLOSE, this::dispose));
        return group;
    }

    private JButton windowButton(Glyph glyph, Runnable action) {
        IconButton button = new IconButton(glyph, Kind.LINK);
        button.setPreferredSize(new Dimension(30, 30));
        button.addActionListener(event -> action.run());
        return button;
    }

    // ---- capture exclusion ----

    /**
     * The click handler. Switches the exclusion, moves the one handle this window
     * owns, remembers the choice, and says what happened.
     *
     * <p>Three steps rather than one because {@link CaptureHider#setEnabled} also
     * drives the sweep that covers dialogs the handle cannot name - the file chooser
     * opened after this click would otherwise show up in the share - while the
     * explicit handle call makes this frame change on the same event rather than on
     * the next pass a second later.
     */
    private void applyCaptureToggle(boolean hidden) {
        CaptureHider.setEnabled(hidden);
        Pointer hwnd = CaptureHider.pointerOf(this);

        boolean applied;
        if (!hidden) {
            // Already released by revealNow when the window is gone; a null handle
            // here means there is nothing left to restore, which is the answer we
            // wanted anyway.
            applied = hwnd == null || CaptureHider.releaseFrom(hwnd);
        } else {
            applied = hwnd != null && CaptureHider.applyTo(hwnd);
        }

        boolean saved = CaptureConfig.save(hidden);
        if (!hidden) {
            append("[Injector] Injector window visible to screen capture again.");
            status.setText("capture exclusion off");
        } else if (applied) {
            append("[Injector] Injector window hidden from screen capture.");
            status.setText("capture exclusion on");
        } else {
            append("[!] capture exclusion refused: " + CaptureHider.lastError());
            status.setText("capture exclusion failed");
        }
        if (!saved) {
            append("[!] could not write " + CaptureConfig.FILE_NAME + " - choice not remembered");
        }
    }

    /**
     * Applies the saved state to the native handle at startup.
     *
     * <p>The sweep {@link CaptureHider#setEnabled} starts in {@code launch()} also
     * covers this window, but only on its first pass, which can be a full second
     * after the frame appears. A capture that begins at launch would show that
     * second, so the handle is marked here, from {@code windowOpened}, where the peer
     * exists and nothing has been painted into the frame yet.
     */
    private void applyCaptureAffinity() {
        if (!hideCapture.isSelected() || !CaptureHider.isEnabled()) return;

        Pointer hwnd = CaptureHider.pointerOf(this);
        if (hwnd == null) {
            append("[!] capture exclusion unavailable: " + CaptureHider.lastError());
            return;
        }
        if (CaptureHider.applyTo(hwnd)) {
            append("[Injector] Injector window hidden from screen capture.");
            status.setText("capture exclusion on");
        } else {
            // Windows refuses layered windows silently, so the reason here is the
            // only diagnosis available if a future skin makes the frame transparent.
            append("[!] capture exclusion refused: " + CaptureHider.lastError());
            status.setText("capture exclusion failed");
        }
    }

    private JComponent body() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, processPanel(), selectedPanel());
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI());
        split.setBorder(null);
        split.setOpaque(false);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.62);
        split.setDividerSize(1);
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            javax.swing.plaf.basic.BasicSplitPaneDivider divider = basic.getDivider();
            divider.setBorder(null);
            divider.setBackground(BORDER);
        }
        return split;
    }

    // ---- left: process list ----

    private JComponent processPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(18, 18, 12, 18));
        top.add(search, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(tableBlock(), BorderLayout.CENTER);
        return panel;
    }

    private JComponent tableBlock() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(52);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(BG);
        table.setForeground(TEXT);
        table.setSelectionBackground(ROW_SEL);
        table.setSelectionForeground(TEXT);
        table.setFocusable(false);
        table.setAutoCreateRowSorter(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(300);
        for (int column = 0; column < table.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setMinWidth(48);
            table.getColumnModel().getColumn(column).setCellRenderer(new RowRenderer());
        }

        JTableHeader header = table.getTableHeader();
        header.setBackground(BG);
        header.setForeground(DIM);
        header.setFont(SECTION);
        header.setPreferredSize(new Dimension(0, 42));
        header.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER));
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setDefaultRenderer(new HeadRenderer());

        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectProcess();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && selected() != null) primaryAction();
            }
        });

        JScrollPane pane = new JScrollPane(table);
        pane.setBorder(null);
        pane.setOpaque(false);
        pane.getViewport().setBackground(BG);
        pane.getVerticalScrollBar().setUnitIncrement(28);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));

        // The table lays its own columns out (see the field initialiser); nothing to
        // wire here.
        return pane;
    }

    /**
     * Splits the visible width across PID / name / version / directory.
     *
     * <p>The sum has to stay inside the viewport: with {@code AUTO_RESIZE_OFF} the
     * table is as wide as its columns, and anything past the viewport edge is
     * clipped rather than scrolled - which cut the directory mid-word instead of
     * letting the cell draw its own ellipsis.
     */
    private void sizeColumns(int available) {
        if (available <= 0) return;
        int usable = Math.max(240, available - 8);

        int pid = Math.max(72, Math.round(usable * 0.10f));
        int version = Math.max(88, Math.round(usable * 0.15f));
        int name = Math.max(150, Math.round(usable * 0.40f));
        int directory = Math.max(120, usable - pid - version - name);
        int[] widths = {pid, name, version, directory};

        if (table.getColumnCount() < widths.length) return;
        for (int column = 0; column < widths.length; column++) {
            if (table.getColumnModel().getColumn(column).getWidth() != widths[column]) {
                table.getColumnModel().getColumn(column).setWidth(widths[column]);
            }
        }
    }

    // ---- right: selected process ----

    private JComponent selectedPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER));
        panel.setMinimumSize(new Dimension(430, 0));
        panel.setPreferredSize(new Dimension(500, 0));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
        head.setBorder(new EmptyBorder(20, 24, 0, 24));

        head.add(titleRow());
        head.add(javax.swing.Box.createVerticalStrut(14));

        pidLabel.setFont(PID);
        pidLabel.setForeground(TEXT);
        pidLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(pidLabel);

        mainClass.setFont(UI);
        mainClass.setForeground(MUTED);
        mainClass.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainClass.setBorder(new EmptyBorder(2, 0, 0, 0));
        head.add(mainClass);

        head.add(javax.swing.Box.createVerticalStrut(16));
        head.add(dataGrid());
        head.add(javax.swing.Box.createVerticalStrut(18));
        head.add(inputs);
        head.add(javax.swing.Box.createVerticalStrut(14));

        panel.add(head, BorderLayout.NORTH);
        panel.add(consoleBlock(), BorderLayout.CENTER);
        panel.add(primaryBlock(), BorderLayout.SOUTH);
        return panel;
    }

    private JComponent titleRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel title = new JLabel("SELECTED PROCESS");
        title.setFont(SECTION);
        title.setForeground(DIM);
        row.add(title, BorderLayout.WEST);
        row.add(badge, BorderLayout.EAST);
        return row;
    }

    private JComponent dataGrid() {
        JPanel grid = new JPanel(new GridLayout(0, 1));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        clientVersionValue.setForeground(TEXT);
        grid.add(new DataRow("CLIENT VERSION", clientVersionValue));
        grid.add(new DataRow("JVM", jvmValue));
        grid.add(new DataRow("UPTIME", uptimeValue));
        grid.add(new DataRow("HEAP", heapValue));
        grid.add(new DataRow("GAME DIR", gameDirValue));
        return grid;
    }

    private JComponent primaryBlock() {
        JPanel block = new JPanel(new BorderLayout(0, 10));
        block.setBackground(PANEL);
        block.setBorder(new EmptyBorder(10, 24, 22, 24));
        block.add(secondaryRow(), BorderLayout.NORTH);

        inject.setFont(ACTION);
        inject.setForeground(TEXT);
        inject.setPreferredSize(new Dimension(0, 54));
        block.add(inject, BorderLayout.SOUTH);
        return block;
    }

    private JComponent secondaryRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(unload);
        row.add(install);
        row.add(remove);
        row.add(launchOptions);
        return row;
    }

    private JComponent consoleBlock() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(PANEL);
        wrap.setBorder(new EmptyBorder(0, 24, 14, 24));

        JPanel frame = new JPanel(new BorderLayout());
        frame.setBackground(CONSOLE_BG);
        frame.setBorder(BorderFactory.createLineBorder(BORDER));

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(PANEL_RAISED);
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(8, 12, 8, 10)));

        JLabel title = new JLabel("TARGET OUTPUT");
        // Mono here as well as in the body: the console is the one place in the
        // window where a character count matters, and the heading should read as the
        // same surface as the text under it rather than as a label that wandered in.
        title.setFont(tracked(MONO_BOLD, 0.10f));
        title.setForeground(MUTED);
        head.add(title, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        status.setFont(tracked(SMALL, 0.06f));
        status.setForeground(DIM);
        status.setBorder(new EmptyBorder(4, 0, 0, 6));
        actions.add(status);
        actions.add(follow);
        actions.add(clear);
        head.add(actions, BorderLayout.EAST);

        console.setEditable(false);
        console.setBorder(new EmptyBorder(12, 12, 12, 12));
        console.setBackground(CONSOLE_BG);
        console.setForeground(TEXT);
        // The document styles carry the family per run; this is the fallback for the
        // caret, the selection and anything inserted before a style is applied.
        console.setFont(MONO);
        console.setCaretColor(ACCENT);
        console.setSelectionColor(new Color(0x0E3A40));
        console.setSelectedTextColor(TEXT);

        JScrollPane pane = new JScrollPane(console);
        pane.setBorder(null);
        pane.setOpaque(false);
        pane.getViewport().setBackground(CONSOLE_BG);
        pane.getVerticalScrollBar().setUnitIncrement(20);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        pane.getVerticalScrollBar().setOpaque(false);

        frame.add(head, BorderLayout.NORTH);
        frame.add(pane, BorderLayout.CENTER);
        frame.add(operation, BorderLayout.SOUTH);
        wrap.add(frame, BorderLayout.CENTER);
        wrap.setMinimumSize(new Dimension(0, 150));
        return wrap;
    }

    // ---- footer ----

    private JComponent footer() {
        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                new EmptyBorder(10, 22, 10, 22)));

        footerLeft.setFont(tracked(SMALL_BOLD, 0.08f));
        footerLeft.setForeground(MUTED);
        bar.add(footerLeft, BorderLayout.WEST);

        watchState.setFont(tracked(SMALL_BOLD, 0.08f));
        watchState.setForeground(MUTED);
        bar.add(watchState, BorderLayout.EAST);
        return bar;
    }

    // ---- inputs, per mode ----

    private JComponent inputRow(String label, JComponent field, JComponent trailing) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        row.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel name = new JLabel(label);
        name.setFont(SECTION);
        name.setForeground(DIM);
        name.setPreferredSize(new Dimension(74, 20));
        row.add(name, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        if (trailing != null) row.add(trailing, BorderLayout.EAST);
        return row;
    }

    private JComponent runningCard() {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new javax.swing.BoxLayout(card, javax.swing.BoxLayout.Y_AXIS));

        // Browse picks a jar off this disk; Repo replaces it with the newest release.
        // Same row, same height, because they are the two answers to one question.
        JPanel jarActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        jarActions.setOpaque(false);
        browseJar.setPreferredSize(new Dimension(86, 40));
        repoJar.setPreferredSize(new Dimension(72, 40));
        repoJar.setToolTipText("download the newest client jar from the GitHub release");
        jarActions.add(browseJar);
        jarActions.add(repoJar);

        card.add(inputRow("JAR", jarField, jarActions));
        card.add(inputRow("HOOK", hookField, null));

        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 22, 4));
        checks.setOpaque(false);
        checks.setAlignmentX(Component.LEFT_ALIGNMENT);
        checks.add(stealth);
        checks.add(openGui);
        card.add(checks);
        return card;
    }

    private JComponent modCard() {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new javax.swing.BoxLayout(card, javax.swing.BoxLayout.Y_AXIS));

        browseMods.setPreferredSize(new Dimension(86, 40));
        browseClient.setPreferredSize(new Dimension(86, 40));
        card.add(inputRow("MODS DIR", modsField, browseMods));
        card.add(inputRow("INTO", hostCombo, null));
        card.add(inputRow("CLIENT JAR", clientField, browseClient));
        return card;
    }

    // ---- actions ----

    private void startLogChannel() {
        try {
            logServer = LogServer.start(line -> {
                OperationProgress.Session current = session;
                OperationProgress.Event event = current == null ? null : current.accept(line);
                SwingUtilities.invokeLater(() -> {
                    if (event != null && session == current) {
                        if (event.terminal()) operation.finish(event.ok(), event.message());
                        else operation.step(event.step(), event.message());
                    }
                    if (!line.startsWith("UC_STAGE ")) append(line);
                });
            });
            append("[*] log channel on " + LogServer.HOST + ":" + logServer.port());
        } catch (Exception failure) {
            append("[!] no log channel: " + failure);
        }
    }

    /** Refreshes the process list. Cheap to call; overlapping calls are dropped. */
    private void refreshTargets() {
        if (busy.get()) return;
        if (!scanning.compareAndSet(false, true)) return;

        refresh.setEnabled(false);
        // Read on the EDT: a Swing field is not safe to touch from the worker, and
        // the user can browse for a new jar while the scan runs.
        String clientPath = jarField.getText();
        worker.submit(() -> {
            try {
                String version = clientJarVersion(clientPath);
                List<JvmScanner.Target> targets = JvmScanner.list();
                List<AutoInjectController.Candidate> candidates = targets.stream().map(AutoInjectController.Candidate::observe).filter(java.util.Objects::nonNull).toList();
                long minecraft = targets.stream().filter(JvmScanner.Target::isMinecraft).count();
                long launchers = targets.stream().filter(JvmScanner.Target::isLauncher).count();
                SwingUtilities.invokeLater(() -> {
                    installRows(targets, version);
                    counts.setText(targets.size() + " JAVA | " + minecraft + " MC");
                    footerLeft.setText(targets.size() + " PROCESSES | "
                            + minecraft + " MINECRAFT");
                    if (minecraft == 0 && launchers > 0) {
                        append("[*] a launcher is open but no game JVM is visible; press Play, "
                                + "then refresh");
                    }
                    refresh.setEnabled(true);
                    considerAutoInject(candidates);
                });
            } catch (RuntimeException failure) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("attach api unavailable");
                    append("[!] " + JvmScanner.describe(failure));
                    refresh.setEnabled(true);
                });
            } finally {
                scanning.set(false);
            }
        });
    }

    /** Publishes a fresh list, keeping the selected pid selected when it survived. */
    private void installRows(List<JvmScanner.Target> targets, String clientVersion) {
        JvmScanner.Target previous = selected();
        Map<String, String> versions = new LinkedHashMap<>();
        for (JvmScanner.Target target : targets) {
            versions.put(target.pid(), ProcessInfo.version(target));
        }
        model.setRows(targets, versions);
        applyFilter();

        int row = previous == null ? -1 : model.rowOf(previous.pid());
        if (row < 0) {
            // Arm Inject on the game only. Pre-selecting a Gradle daemon made the
            // button look ready for a target it cannot attach to.
            row = model.firstMinecraftRow();
            if (row < 0) row = model.getRowCount() > 0 ? 0 : -1;
        }
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        } else {
            table.clearSelection();
            clearSelection();
        }
    }

    private void applyFilter() {
        String query = search.getText().trim();
        model.setFilter(query);
        if (model.getRowCount() == 0) {
            table.clearSelection();
            clearSelection();
        } else if (table.getSelectedRow() < 0) {
            int row = model.firstMinecraftRow();
            table.setRowSelectionInterval(row >= 0 ? row : 0, row >= 0 ? row : 0);
        }
    }

    /** Fills the right panel from the selected row. */
    private void selectProcess() {
        JvmScanner.Target target = selected();
        if (target == null) {
            clearSelection();
            return;
        }

        int token = ++selectionToken;
        pidLabel.setText("PID " + target.pid());
        mainClass.setText(target.description().isBlank() ? ProcessInfo.UNKNOWN : target.description());
        badge.setText(target.isMinecraft() ? "READY" : "JAVA");

        String version = model.versionOf(target.pid());
        clientVersionValue.setValue(ProcessInfo.UNKNOWN.equals(version)
                ? ProcessInfo.UNKNOWN : "Minecraft " + version);
        gameDirValue.setValue(target.gameDirectory() == null || target.gameDirectory().isBlank()
                ? ProcessInfo.UNKNOWN : target.gameDirectory());
        uptimeValue.setValue(ProcessInfo.uptime(target.pid()));
        jvmValue.setValue("reading\u2026");
        heapValue.setValue("reading\u2026");

        // The attach probe and jcmd both cross a process boundary; neither may run
        // on the EDT or the window freezes while a game answers. One probe returns
        // everything the panel prints, so the target sees a single attach.
        worker.submit(() -> {
            ProcessInfo.Snapshot snapshot = ProcessInfo.snapshot(target);
            SwingUtilities.invokeLater(() -> {
                if (token != selectionToken) return;
                clientVersionValue.setValue(ProcessInfo.UNKNOWN.equals(snapshot.version())
                        ? ProcessInfo.UNKNOWN : "Minecraft " + snapshot.version());
                jvmValue.setValue(snapshot.jvm());
                heapValue.setValue(snapshot.heap());
                gameDirValue.setValue(snapshot.gameDir());
            });
        });

        unload.setEnabled(!busy.get());
        inject.setEnabled(!busy.get());
    }

    private void clearSelection() {
        selectionToken++;
        pidLabel.setText("PID \u2014");
        mainClass.setText("no process selected");
        badge.setText("NO SELECTION");
        clientVersionValue.setValue(ProcessInfo.UNKNOWN);
        jvmValue.setValue(ProcessInfo.UNKNOWN);
        uptimeValue.setValue(ProcessInfo.UNKNOWN);
        heapValue.setValue(ProcessInfo.UNKNOWN);
        gameDirValue.setValue(ProcessInfo.UNKNOWN);
        inject.setEnabled(false);
        unload.setEnabled(false);
    }

    /** The big button: attach in running mode, merge in mod mode. */
    private void primaryAction() {
        if (modTab.isSelected()) {
            installIntoMod();
        } else {
            attachSelected();
        }
    }

    private void operationDone(boolean ok, String message) {
        busy.set(false);
        session = null;
        operation.finish(ok, message);
        inject.setEnabled(selected() != null);
        unload.setEnabled(selected() != null);
        updateModButtons();
        if (!ok) {
            FailureRecovery.Advice advice = FailureRecovery.forMessage(message);
            append("[RECOVERY] " + advice.title());
            for (String step : advice.steps()) append("  " + step);
            javax.swing.JOptionPane.showMessageDialog(this, message + "\n\n" + advice.text(),
                    "Recovery instructions", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    private void attachSelected() {
        attachTarget(selected(), null);
    }

    private void attachTarget(JvmScanner.Target target, AutoInjectController.Candidate automatic) {
        if (busy.get()) return;
        if (target == null) return;
        if (automatic != null && (!target.isAutoInjectable() || !automatic.stillRunning())) {
            append("[!] Auto Inject target exited or changed before attachment"); return;
        }

        if (!target.isMinecraft()) {
            // Never block on the heuristic: Prism's classpath carries launcher words
            // into the game process, so a guess can be wrong. The attach result is
            // the honest answer, and a failed attach changes nothing.
            append("[!] pid " + target.pid() + " does not look like the game ("
                    + target.kind() + "); attaching anyway");
        }

        Path jar = AgentAttacher.selfJar();
        if (jar == null) {
            append("[!] run the packaged jar, not a classes directory");
            return;
        }

        Map<String, String> settings = new LinkedHashMap<>();
        if (logServer == null) {
            operation.finish(false, "Log channel unavailable; restart the injector before attaching");
            return;
        }
        operation.step(1, "Checking client and selected process");
        settings.put("port", Integer.toString(logServer.port()));
        settings.put("host", LogServer.HOST);
        settings.put("stealth", Boolean.toString(stealth.isSelected()));
        settings.put("openGui", Boolean.toString(openGui.isSelected()));

        String client = jarField.getText().trim();
        if (automatic != null && client.isEmpty()) { append("[!] Auto Inject needs a client JAR; select it in RUNNING GAME"); return; }
        if (!client.isEmpty()) {
            Path jarPath = Path.of(client);
            if (!Files.isRegularFile(jarPath)) {
                append("[!] no client jar at " + client);
                return;
            }
            // Never hand the agent a jar the repo button has already replaced.
            Path newest = newestAvailable(jarPath);
            if (!newest.equals(jarPath)) {
                append("[!] " + jarPath.getFileName() + " is older than the fetched copy; using "
                        + newest.getFileName());
                jarPath = newest;
                jarField.setText(newest.toString());
            }

            // Name the build before it goes in: filenames and mod versions are
            // identical across releases, so the digest is the only honest label.
            settings.put("mod", jarPath.toString());
        }

        String hooks = hookField.getText().trim();
        if (!hooks.isEmpty() && automatic == null) {
            try {
                settings.put("hooks", HookSpec.parse(hooks).display());
            } catch (IllegalArgumentException malformed) {
                append("[!] bad hook: " + malformed.getMessage());
                return;
            }
        }

        busy.set(true);
        session = new OperationProgress.Session();
        settings.put("request", session.id);
        operation.step(2, "Connecting to PID " + target.pid());
        inject.setEnabled(false);
        launchOptions.setEnabled(false);
        status.setText("attaching to " + target.pid() + "\u2026");
        append("[*] attach \u2192 pid " + target.pid() + " (" + target.description() + ")");

        worker.submit(() -> {
            Attempt attempt = automatic != null && !automatic.stillRunning()
                    ? new Attempt(false, "Selected game exited or restarted before attachment") : attach(target, jar, settings);
            SwingUtilities.invokeLater(() -> {
                if (automatic != null) autoInject.completed(automatic, attempt.ok());
                status.setText(attempt.message());
                operationDone(attempt.ok(), attempt.message());
                append((attempt.ok() ? "[+] " : "[!] ") + attempt.message());
                inject.setEnabled(selected() != null);
                launchOptions.setEnabled(true);
                updateWatchLabel();
            });
        });
    }

    private void updateWatchLabel() {
        watchState.setText("AUTO INJECT " + (launchSettings.autoInject ? "ON" : "OFF") + " | SCAN "
                + String.format(java.util.Locale.ROOT, "%.1fS", launchSettings.scanIntervalMs / 1000.0));
        watchState.setForeground(launchSettings.autoInject ? ACCENT : MUTED);
        watchState.setToolTipText(lastWatchMessage);
    }
    private void considerAutoInject(List<AutoInjectController.Candidate> candidates) {
        if (busy.get() || !runningTab.isSelected()) return;
        var decision = autoInject.poll(candidates, launchSettings, System.currentTimeMillis());
        if (!decision.message().equals(lastWatchMessage)) {
            lastWatchMessage = decision.message(); if (launchSettings.autoInject) append("[*] " + lastWatchMessage);
        }
        updateWatchLabel();
        if (decision.ready()) {
            int row = model.rowOf(decision.candidate().target().pid());
            if (row >= 0) { int view = table.convertRowIndexToView(row); if (view >= 0) table.setRowSelectionInterval(view, view); }
            autoInject.started(decision.candidate()); attachTarget(decision.candidate().target(), decision.candidate());
        }
    }
    private void showLaunchOptions() {
        if (busy.get()) return;
        LaunchOptionsDialog.show(this, launchSettings, value -> {
            try {
                value.save(InstallPaths.root()); launchSettings = value; autoInject.reset();
                autoRefresh.setDelay(value.scanIntervalMs); updateWatchLabel();
                append("[+] Launch options saved; automatic attempts reset"); refreshTargets();
            } catch (java.io.IOException failure) { javax.swing.JOptionPane.showMessageDialog(this, failure.getMessage(), "Launch options", javax.swing.JOptionPane.ERROR_MESSAGE); }
        }, operation -> worker.submit(() -> {
            try { String result = WindowsIntegration.apply(operation); SwingUtilities.invokeLater(() -> append("[+] " + result)); }
            catch (Exception failure) { SwingUtilities.invokeLater(() -> {
                append("[!] " + failure.getMessage()); javax.swing.JOptionPane.showMessageDialog(this, failure.getMessage(), "Windows integration", javax.swing.JOptionPane.ERROR_MESSAGE);
            }); }
        }), () -> { autoInject.reset(); append("[+] Automatic attempts reset"); refreshTargets(); });
    }

    /** One attach result, captured as a single value for the EDT update. */
    private record Attempt(boolean ok, String message) {
    }

    private Attempt attach(JvmScanner.Target target, Path jar, Map<String, String> settings) {
        try {
            String client = settings.get("mod");
            if (client != null && !client.isBlank() && !"unload".equals(settings.get("op"))) {
                SwingUtilities.invokeLater(() -> operation.step(1, "Verifying client contents and SHA-256"));
                ClientIntegrity.Report verified = ClientIntegrity.verify(Path.of(client));
                settings.put("clientSha256", verified.sha256());
                SwingUtilities.invokeLater(() -> { append("[VERIFIED] " + verified.summary()); operation.step(2, "Connecting to PID " + target.pid()); });
            }
            AgentAttacher.inject(target.pid(), jar, settings);
            OperationProgress.Session current = session;
            if (current == null) return new Attempt(false, "No progress session available");
            OperationProgress.Event event = current.await(10_000);
            return event == null
                    ? new Attempt(false, "Attach returned but target confirmation timed out; inspect target log before retrying")
                    : new Attempt(event.ok(), event.message());
        } catch (Exception failure) {
            return new Attempt(false, "injection failed: " + JvmScanner.describe(failure));
        }
    }

    /**
     * Unloads the client from the game that is running right now.
     *
     * <p>Same attach path as Inject, with {@code op=unload}: the agent looks up the
     * live client class through Instrumentation and calls its {@code unload()},
     * which switches every module off, closes the overlay window and puts every
     * entry point to sleep. The jar stays where it is — a Fabric mod cannot be
     * pulled out of a running loader — but it stops doing anything until the next
     * launch.
     */
    private void unloadSelected() {
        if (busy.get()) return;
        JvmScanner.Target target = selected();
        if (target == null) return;

        Path jar = AgentAttacher.selfJar();
        if (jar == null) {
            append("[!] run the packaged jar, not a classes directory");
            return;
        }

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("op", "unload");
        if (logServer == null) {
            operation.finish(false, "Log channel unavailable; restart the injector");
            return;
        }
        settings.put("port", Integer.toString(logServer.port()));
        settings.put("host", LogServer.HOST);

        busy.set(true);
        session = new OperationProgress.Session();
        settings.put("request", session.id);
        operation.step(2, "Connecting to unload PID " + target.pid());
        unload.setEnabled(false);
        status.setText("unloading pid " + target.pid() + "\u2026");
        append("[*] unload \u2192 pid " + target.pid() + " (" + target.description() + ")");

        worker.submit(() -> {
            Attempt attempt = attach(target, jar, settings);
            SwingUtilities.invokeLater(() -> {
                status.setText(attempt.message());
                operationDone(attempt.ok(), attempt.message());
                append((attempt.ok() ? "[+] " : "[!] ") + attempt.message());
                unload.setEnabled(selected() != null);
            });
        });
    }

    private void loadHosts() {
        hostCombo.removeAllItems();
        Path dir = modsFolder();
        if (dir == null || !Files.isDirectory(dir)) {
            updateModButtons();
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".jar"))
                    .sorted()
                    .forEach(hostCombo::addItem);
        } catch (Exception failure) {
            append("[!] cannot read " + dir + ": " + failure.getMessage());
        }
        updateModButtons();
    }

    private Path modsFolder() {
        String text = modsField.getText().trim();
        return text.isEmpty() ? null : Path.of(text);
    }

    private Path selectedHost() {
        Path dir = modsFolder();
        Object name = hostCombo.getSelectedItem();
        if (dir == null || name == null) return null;
        return dir.resolve(name.toString());
    }

    private Path selectedClient() {
        String text = clientField.getText().trim();
        return text.isEmpty() ? null : Path.of(text);
    }

    private void updateModButtons() {
        Path host = selectedHost();
        remove.setEnabled(!busy.get() && host != null && ModMerger.isInjected(host));
        install.setEnabled(!busy.get() && host != null);
    }

    private void installIntoMod() {
        if (busy.get()) return;
        Path host = selectedHost();
        Path client = selectedClient();
        if (host == null) {
            append("[!] pick a mods folder and a mod");
            return;
        }
        if (client == null) {
            append("[!] pick the client jar");
            return;
        }

        busy.set(true);
        session = null;
        updateModButtons();
        operation.step(1, "Checking selected client and host mod");
        status.setText("merging\u2026");
        append("[*] merging " + client.getFileName() + " into " + host.getFileName());
        worker.submit(() -> {
            SwingUtilities.invokeLater(() -> operation.step(3, "Backing up and merging the selected mod"));
            ModMerger.Result result = ModMerger.install(host, client);
            SwingUtilities.invokeLater(() -> {
                operationDone(result.ok(), result.ok() ? "Installed; restart the game to load the client" : result.message());
                status.setText(result.message());
                append((result.ok() ? "[+] " : "[!] ") + result.message());
                if (result.ok()) {
                    append("[*] launch the game — the client loads inside "
                            + host.getFileName() + " and lists no new mod");
                }
                updateModButtons();
            });
        });
    }

    private void removeFromMod() {
        if (busy.get()) return;
        Path host = selectedHost();
        if (host == null) return;
        busy.set(true);
        session = null;
        updateModButtons();
        operation.step(3, "Restoring the original mod from its backup");
        status.setText("restoring\u2026");
        worker.submit(() -> {
            ModMerger.Result result = ModMerger.remove(host);
            SwingUtilities.invokeLater(() -> {
                operationDone(result.ok(), result.message());
                status.setText(result.message());
                append((result.ok() ? "[+] " : "[!] ") + result.message());
                updateModButtons();
            });
        });
    }

    private JvmScanner.Target selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) return null;
        return model.at(table.convertRowIndexToModel(row));
    }

    private void append(String line) {
        StyledDocument doc = console.getStyledDocument();
        AttributeSet style = line.startsWith("[+]") ? S_OK
                : line.startsWith("[!]") ? S_WARN
                : (line.startsWith("[*]") || line.contains("[agent]")) ? S_DIM
                : S_TEXT;
        try {
            doc.insertString(doc.getLength(), line + System.lineSeparator(), style);
        } catch (BadLocationException ignored) {
            // The document only moves when we trim it below.
        }

        if (doc.getLength() > LOG_LIMIT) {
            try {
                doc.remove(0, doc.getLength() - LOG_LIMIT * 3 / 4);
            } catch (BadLocationException ignored) {
                // Nothing to trim if the document shrank in between.
            }
        }
        if (follow.isSelected()) console.setCaretPosition(doc.getLength());
    }

    /**
     * Pulls the newest client jar from the configured GitHub release and selects it.
     *
     * <p>Runs on {@link #fetchWorker}: the release lookup and the asset download are
     * two blocking HTTPS round trips, and either can take the whole of its timeout
     * on a bad connection. Doing that on the event thread is what froze the window
     * on every launch with an empty jar field.
     *
     * <p>The download lands in {@code %APPDATA%\depends\cheatclient.jar} - the folder
     * the agent reads from and the one an injected session expects - and the JAR
     * field is repointed at it, so the next Inject click attaches the build that was
     * just fetched rather than whatever path was typed before. A second click while
     * a fetch is in flight is dropped, not queued.
     */
    private void fetchClient() {
        if (busy.get()) return;
        if (!fetching.compareAndSet(false, true)) return;
        busy.set(true);
        session = null;
        operation.step(1, "Checking release source");

        String repo = RemoteClient.defaultRepo();
        Path destination = RemoteClient.cacheDir();
        repoJar.setEnabled(false);
        status.setText("fetching from " + repo + "\u2026");
        append("[Injector] Fetching latest release from GitHub...");
        append("[*] repo " + repo);
        append("[*] destination " + destination.resolve("cheatclient.jar"));

        fetchWorker.submit(() -> {
            RemoteClient.Result result = RemoteClient.fetch(repo, "", message ->
                    SwingUtilities.invokeLater(() -> operation.step(message.startsWith("Looking") ? 2 : 3, message)));
            SwingUtilities.invokeLater(() -> {
                fetching.set(false);
                operationDone(result.ok(), result.message());
                repoJar.setEnabled(true);

                // Name the release and the asset that was chosen, not just "ok": with
                // more than one asset on a release the picked one is the only way to
                // tell a working download from an accidental source jar.
                String source = result.source();
                Path jar = result.jar();
                if (result.ok() && jar != null) {
                    append("[Injector] Download complete.");
                    append("[+] " + result.message());
                    if (!source.isBlank()) append("[*] from " + source);
                    setField(jarField, jar.toString());
                    status.setText("fetched " + jar.getFileName());
                    savePrefs();
                    loadHosts();
                } else {
                    append("[!] " + result.message());
                    status.setText("fetch failed");
                }
            });
        });
    }

    private void switchMode(boolean running) {
        runningTab.setSelected(running);
        modTab.setSelected(!running);
        runningTab.refresh();
        modTab.refresh();
        cards.show(inputs, running ? "running" : "mod");
        inject.setText(running ? "INJECT" : "INSTALL");
        unload.setVisible(running);
        install.setVisible(!running);
        remove.setVisible(!running);
        status.setText(running ? "attaches to a live process"
                : "edits a jar \u2014 survives restarts");
    }

    // ---- file pickers ----

    private void pickFile(JTextField field, String label, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                label + " (*." + extension + ")", extension));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = chooser.getSelectedFile();
        field.setText(chosen.getAbsolutePath());
    }

    private void pickFolder(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = chooser.getSelectedFile();
        field.setText(chosen.getAbsolutePath());
        loadHosts();
    }

    // ---- prefs ----

    private void restorePrefs() {
        setField(jarField, preferredClientJar(PREFS.get("clientJar", defaultClientJar())));
        setField(hookField, PREFS.get("hook", ""));
        setField(modsField, PREFS.get("modsDir", defaultModsDir()));
        setField(clientField, PREFS.get("clientField", ""));
        stealth.setSelected(PREFS.getBoolean("stealth", true));
        openGui.setSelected(PREFS.getBoolean("openGui", false));
        switchMode(!PREFS.getBoolean("modMode", false));
    }

    /**
     * Sets a long path and shows its head.
     *
     * <p>{@code JTextField.setText} leaves the caret at the end, so a restored path
     * opened scrolled to its last segment and every field read as if it started with
     * a half word.
     */
    private static void setField(JTextField field, String value) {
        field.setText(value == null ? "" : value);
        field.setCaretPosition(0);
    }

    private void savePrefs() {
        PREFS.put("clientJar", jarField.getText());
        PREFS.put("hook", hookField.getText());
        PREFS.put("modsDir", modsField.getText());
        PREFS.put("clientField", clientField.getText());
        PREFS.putBoolean("stealth", stealth.isSelected());
        PREFS.putBoolean("openGui", openGui.isSelected());
        PREFS.putBoolean("modMode", modTab.isSelected());
    }

    /**
     * A saved path, unless it points into the retired {@code ~/.uc} cache. Earlier
     * builds fetched there; a stale copy left in it made the window reopen on an
     * old jar after the repo had moved on, and injection then ran that old build.
     */
    private static String preferredClientJar(String saved) {
        if (saved == null || saved.isBlank()) return defaultClientJar();
        Path path = Path.of(saved);
        Path legacy = Path.of(System.getProperty("user.home"), ".uc");
        Path canonical = RemoteClient.cacheDir().resolve("cheatclient.jar");
        if (path.startsWith(legacy) && Files.isRegularFile(canonical)) {
            return canonical.toString();
        }
        return saved;
    }

    /**
     * Version of the jar the Inject button would attach, for the table column.
     *
     * <p>Reads the manifest, so the number shown is the number inside the build
     * rather than the number in its filename.
     */
    private static String clientJarVersion(String path) {
        String text = path == null ? "" : path.trim();
        if (text.isEmpty()) return ProcessInfo.UNKNOWN;
        try {
            return ClientJar.versionOf(Path.of(text));
        } catch (RuntimeException failure) {
            return "bad path";
        }
    }

    /**
     * The chosen jar, or the fetched copy when that one is strictly newer.
     *
     * <p>A stale path is what let an attach load a build the repo had already
     * replaced. A newer custom jar is left alone, so a local build still wins.
     */
    private static Path newestAvailable(Path chosen) {
        return chosen;
    }

    /** Loud line when the chosen jar differs from the one the repo button fetched. */
    private void warnIfStale(Path chosenPath, ClientJar chosen) {
        Path canonical = RemoteClient.cacheDir().resolve("cheatclient.jar");
        if (chosenPath.toAbsolutePath().equals(canonical.toAbsolutePath())) return;
        if (!Files.isRegularFile(canonical)) return;

        ClientJar repo = ClientJar.describe(canonical);
        if (repo.digest().equals(chosen.digest())) return;
        append("[!] this is not the fetched build; repo copy is " + repo.line());
    }

    private static String defaultClientJar() {
        Path jar = AgentAttacher.selfJar();
        if (jar == null) return "";
        // <project>/injector/build/libs/injector.jar -> <project>/build/libs
        Path level = jar.getParent();
        for (int i = 0; i < 3 && level != null; i++) level = level.getParent();
        if (level == null) return "";
        Path dir = level.resolve("build").resolve("libs");
        if (!Files.isDirectory(dir)) return "";
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith("cheatclient"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .map(Path::toString)
                    .findFirst()
                    .orElse("");
        } catch (Exception failure) {
            return "";
        }
    }

    /** Prism's instances directory on Windows, macOS, Arch and the Flatpak build. */
    private static String defaultModsDir() {
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> bases = new ArrayList<>();

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) bases.add(Path.of(appData, "PrismLauncher", "instances"));
        } else if (os.contains("mac")) {
            bases.add(Path.of(home, "Library", "Application Support", "PrismLauncher", "instances"));
        } else {
            String data = System.getenv("XDG_DATA_HOME");
            bases.add(Path.of(data != null && !data.isBlank() ? data : home + "/.local/share",
                    "PrismLauncher", "instances"));
            // Flatpak keeps its data outside the normal XDG tree.
            bases.add(Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher",
                    "data", "PrismLauncher", "instances"));
        }

        for (Path instances : bases) {
            if (!Files.isDirectory(instances)) continue;
            try (Stream<Path> stream = Files.list(instances)) {
                String found = stream
                        .map(path -> path.resolve("minecraft").resolve("mods"))
                        .filter(Files::isDirectory)
                        .findFirst()
                        .map(Path::toString)
                        .orElse("");
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {
                // Try the next base.
            }
        }
        return "";
    }

    // ---- components ----

    private enum Kind { SOLID, OUTLINE, GHOST, LINK }

    /** Chrome glyphs drawn as strokes: no font fallback, no elided text. */
    private enum Glyph { MINIMISE, MAXIMISE, CLOSE, REFRESH }

    /**
     * Icon-only flat button.
     *
     * <p>A {@link JButton} with a unicode glyph elides to {@code …} as soon as the
     * button is narrower than the glyph, which is what every window control did on
     * first paint. These are drawn from lines and arcs, so the width is fixed.
     */
    private static final class IconButton extends JButton {

        private final Glyph glyph;
        private final Kind kind;
        private boolean hover;

        IconButton(Glyph glyph, Kind kind) {
            this.glyph = glyph;
            this.kind = kind;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(false);
            setText("");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(30, 30));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean on = isEnabled();
            int width = getWidth();
            int height = getHeight();

            if (kind == Kind.OUTLINE) {
                g.setColor(hover && on ? new Color(0x0E3A40) : PANEL_RAISED);
                g.fillRect(0, 0, width, height);
                g.setColor(BORDER);
                g.drawRect(0, 0, width - 1, height - 1);
            } else if (hover && on) {
                g.setColor(new Color(0x1E1E1E));
                g.fillRect(0, 0, width, height);
            }

            g.setColor(!on ? DIM : kind == Kind.OUTLINE ? ACCENT : MUTED);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));

            int cx = width / 2;
            int cy = height / 2;
            int r = 6;
            switch (glyph) {
                case MINIMISE -> g.drawLine(cx - r, cy, cx + r, cy);
                case MAXIMISE -> g.drawRect(cx - r, cy - r, r * 2, r * 2);
                case CLOSE -> {
                    g.drawLine(cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1);
                    g.drawLine(cx + r - 1, cy - r + 1, cx - r + 1, cy + r - 1);
                }
                case REFRESH -> {
                    g.drawArc(cx - r, cy - r, r * 2, r * 2, 40, 290);
                    g.drawLine(cx + 1, cy - r, cx + r, cy - r + 1);
                    g.drawLine(cx + 1, cy - r, cx + 1, cy - r + 4);
                }
            }
            g.dispose();
        }
    }

    /** Flat rectangular button. No radius, no shadow, one hover tint. */
    private static final class FlatButton extends JButton {

        private final Kind kind;
        private boolean hover;

        FlatButton(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(false);
            setFont(tracked(SMALL_BOLD, 0.10f));
            setForeground(TEXT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(0, 14, 0, 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            boolean on = isEnabled();
            int width = getWidth();
            int height = getHeight();

            switch (kind) {
                case SOLID -> {
                    g.setColor(!on ? ACCENT_DIM : hover ? ACCENT_HOVER : ACCENT);
                    g.fillRect(0, 0, width, height);
                }
                case OUTLINE -> {
                    g.setColor(hover && on ? new Color(0x0E3A40) : PANEL_RAISED);
                    g.fillRect(0, 0, width, height);
                    g.setColor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) == null ? BORDER : BORDER);
                    g.drawRect(0, 0, width - 1, height - 1);
                }
                case GHOST -> {
                    g.setColor(!on ? PANEL_RAISED : hover ? new Color(0x262626) : PANEL_RAISED);
                    g.fillRect(0, 0, width, height);
                    g.setColor(!on ? BORDER_SOFT : BORDER);
                    g.drawRect(0, 0, width - 1, height - 1);
                }
                case LINK -> {
                    if (hover && on) {
                        g.setColor(new Color(0x1E1E1E));
                        g.fillRect(0, 0, width, height);
                    }
                }
            }
            g.dispose();

            setForeground(!on ? DIM
                    : kind == Kind.SOLID ? Color.BLACK
                    : kind == Kind.LINK ? MUTED
                    : TEXT);
            if (kind == Kind.OUTLINE) {
                setForeground(!on ? DIM : ACCENT);
            }
            super.paintComponent(graphics);
        }
    }

    /**
     * "HIDE INJECTOR" - one bit, drawn from primitives like the rest of the chrome.
     *
     * <p>A {@code JToggleButton} rather than a {@code JCheckBox}: the checkbox is
     * themed by the look and feel and would arrive with a rounded box and a focus
     * ring inside a window that has neither. The state is still the button's, so
     * keyboard activation and {@code setSelected} work without extra plumbing.
     *
     * <p>The padlock is a stroke and a filled rectangle rather than an icon font or a
     * PNG: at 9px a bitmap would need a second asset per scale factor, and the
     * window already builds every other mark out of lines.
     */
    private static final class HideToggle extends JToggleButton {

        private boolean hover;

        HideToggle() {
            super("HIDE INJECTOR");
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(false);
            setFont(tracked(SMALL_BOLD, 0.10f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // Leaves the glyph its own space on the left and keeps the label off the
            // right border; the button's own insets are what centres the text
            // between the two.
            setMargin(new Insets(0, 28, 0, 13));
            setToolTipText("leave this window out of screen and window capture");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);

            boolean on = isSelected();
            int width = getWidth();
            int height = getHeight();

            // Sharp corners, one-pixel rule: the accent is the border and the glyph,
            // not a filled block, so the header keeps one emphasis colour and the
            // switch does not out-shout the INJECT button.
            g.setColor(on ? hover ? new Color(0x143A42) : ACCENT_DIM
                    : hover ? new Color(0x242424) : PANEL_RAISED);
            g.fillRect(0, 0, width, height);
            g.setColor(on ? ACCENT : BORDER);
            g.drawRect(0, 0, width - 1, height - 1);

            int left = 12;
            int middle = height / 2;
            if (on) {
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(1.4f));
                g.drawArc(left, middle - 7, 8, 9, 0, 180);
                g.fillRect(left + 1, middle - 2, 6, 7);
                g.setStroke(new BasicStroke(1f));
            } else {
                g.setColor(DIM);
                g.fillOval(left + 2, middle - 2, 5, 5);
            }
            g.dispose();

            setForeground(on ? ACCENT : MUTED);
            super.paintComponent(graphics);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(Math.max(152, size.width), 34);
        }
    }

    /** Top-level mode tab: solid accent with black text when active. */
    private static final class Tab extends JToggleButton {

        Tab(String text, boolean selected) {
            super(text, selected);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(false);
            setFont(tracked(SMALL_BOLD, 0.10f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(0, 20, 0, 20));
            addActionListener(event -> {
                refresh();
                for (Component sibling : getParent().getComponents()) {
                    if (sibling instanceof Tab other && other != this) other.refresh();
                }
            });
        }

        void refresh() {
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            int width = getWidth();
            int height = getHeight();
            if (isSelected()) {
                g.setColor(ACCENT);
                g.fillRect(0, 0, width, height);
            } else {
                g.setColor(BG);
                g.fillRect(0, 0, width, height);
                g.setColor(BORDER);
                g.drawRect(0, 0, width - 1, height - 1);
            }
            g.dispose();
            setForeground(isSelected() ? Color.BLACK : MUTED);
            super.paintComponent(graphics);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, 34);
        }
    }

    /** Flat text field with a one-pixel border and an accent focus line. */
    private static class Field extends JTextField {

        Field(int columns) {
            super(columns);
            setOpaque(false);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setForeground(TEXT);
            setCaretColor(ACCENT);
            setSelectionColor(ACCENT_DIM);
            setSelectedTextColor(TEXT);
            setFont(UI);
            setPreferredSize(new Dimension(0, 40));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(FIELD_BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(isFocusOwner() ? ACCENT : BORDER);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** The process filter: a cyan prompt, then the placeholder, then the query. */
    private static final class Search extends Field {

        private Runnable listener = () -> { };

        Search() {
            super(24);
            setBorder(new EmptyBorder(0, 38, 0, 12));
            setPreferredSize(new Dimension(0, 52));
            getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent event) {
                    listener.run();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent event) {
                    listener.run();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent event) {
                    listener.run();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        setText("");
                    }
                }
            });
        }

        void onChange(Runnable listener) {
            this.listener = listener == null ? () -> { } : listener;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);

            g.setFont(new Font(MONO_FAMILY, Font.BOLD, 13));
            g.setColor(ACCENT);
            FontMetrics promptMetrics = g.getFontMetrics();
            g.drawString(">_", 10,
                    10 + (getHeight() - 20) / 2 + promptMetrics.getAscent() - 3);

            if (getText().isEmpty() && !isFocusOwner()) {
                g.setFont(UI);
                g.setColor(DIM);
                FontMetrics fm = g.getFontMetrics();
                g.drawString("filter processes by pid, name, version, directory",
                        38, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
            g.dispose();
        }
    }

    /** Flat dropdown: same shell as a field, dark list, drawn caret. */
    private static final class Combo extends JComboBox<String> {

        Combo() {
            setOpaque(false);
            setBackground(FIELD_BG);
            setFont(UI);
            setForeground(TEXT);
            setBorder(new EmptyBorder(0, 12, 0, 28));
            setPreferredSize(new Dimension(0, 40));
            setFocusable(false);
            setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                @Override
                protected JButton createArrowButton() {
                    JButton hidden = new JButton();
                    hidden.setVisible(false);
                    return hidden;
                }
            });
            setRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                              int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(
                            list, value, index, false, false);
                    label.setOpaque(true);
                    label.setBackground(selected ? new Color(0x262626) : PANEL);
                    label.setForeground(TEXT);
                    label.setFont(UI);
                    label.setBorder(new EmptyBorder(7, 12, 7, 12));
                    return label;
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(FIELD_BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(BORDER);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

            int x = getWidth() - 20;
            int y = getHeight() / 2 - 2;
            g.setColor(DIM);
            g.fillPolygon(new int[]{x, x + 9, x + 4}, new int[]{y, y, y + 5}, 3);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** Checkbox: a 12px square that fills accent when checked. */
    private static final class Check extends JCheckBox {

        Check(String text, boolean selected) {
            super(text, selected);
            setOpaque(false);
            setFont(UI);
            setForeground(TEXT);
            setFocusPainted(false);
            setIcon(new Box(false));
            setSelectedIcon(new Box(true));
            setIconTextGap(9);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(2, 0, 2, 0));
        }
    }

    private static final class Box implements Icon {

        private final boolean filled;

        Box(boolean filled) {
            this.filled = filled;
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            int size = 12;
            int top = y + (getIconHeight() - size) / 2;

            g.setColor(filled ? ACCENT : FIELD_BG);
            g.fillRect(x, top, size, size);
            g.setColor(filled ? ACCENT : new Color(0x555555));
            g.drawRect(x, top, size - 1, size - 1);

            if (filled) {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                g.drawLine(x + 3, top + 6, x + 5, top + 9);
                g.drawLine(x + 5, top + 9, x + 9, top + 3);
            }
            g.dispose();
        }
    }

    /** Cyan word in a one-pixel accent box. */
    private static final class Badge extends JLabel {

        Badge(String text) {
            super(text);
            setFont(tracked(SMALL_BOLD, 0.12f));
            setForeground(ACCENT);
            setBorder(new EmptyBorder(4, 10, 4, 10));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(PANEL);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(ACCENT);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** One label/value line in the data grid, separated by a hairline. */
    private static final class DataRow extends JPanel {

        DataRow(String label, Value value) {
            super(new BorderLayout(16, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(9, 0, 9, 0));

            JLabel name = new JLabel(label);
            name.setFont(SECTION);
            name.setForeground(DIM);
            name.setPreferredSize(new Dimension(132, 16));
            add(name, BorderLayout.WEST);
            add(value, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(BORDER_SOFT);
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g.dispose();
        }
    }

    /** Right-aligned value that trims instead of wrapping or clipping mid-glyph. */
    private static final class Value extends JComponent {

        private final boolean fromStart;
        private String text = ProcessInfo.UNKNOWN;
        private Color colour = TEXT;

        Value(boolean fromStart) {
            this.fromStart = fromStart;
            setFont(UI);
            setPreferredSize(new Dimension(0, 18));
            setMinimumSize(new Dimension(0, 18));
        }

        void setValue(String value) {
            this.text = value == null || value.isBlank() ? ProcessInfo.UNKNOWN : value;
            repaint();
        }

        @Override
        public void setForeground(Color colour) {
            super.setForeground(colour);
            this.colour = colour;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            smooth(g);
            g.setFont(getFont());
            g.setColor(colour);

            FontMetrics fm = g.getFontMetrics();
            String shown = fit(text, fm, Math.max(0, getWidth() - 2));
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            int x = Math.max(0, getWidth() - fm.stringWidth(shown));
            g.drawString(shown, x, y);
            g.dispose();
        }

        /** Drops characters from the left or right and marks the cut with an ellipsis. */
        private String fit(String value, FontMetrics fm, int available) {
            if (available <= 0 || value.isEmpty()) return value;
            if (fm.stringWidth(value) <= available) return value;

            String mark = "\u2026";
            if (fromStart) {
                for (int keep = value.length() - 1; keep > 0; keep--) {
                    String candidate = mark + value.substring(value.length() - keep);
                    if (fm.stringWidth(candidate) <= available) return candidate;
                }
                return mark;
            }
            for (int keep = value.length() - 1; keep > 0; keep--) {
                String candidate = value.substring(0, keep) + mark;
                if (fm.stringWidth(candidate) <= available) return candidate;
            }
            return mark;
        }
    }

    /**
     * Row renderer: flat background, 2px accent bar on the selected row, text drawn
     * by hand so a long directory ends in an ellipsis instead of being cut at the
     * column edge.
     */
    private static final class RowRenderer extends JPanel implements TableCellRenderer {

        private static final int PAD_LEFT = 18;
        private static final int PAD_RIGHT = 12;

        private String text = "";
        private Font font = UI;
        private Color colour = TEXT;
        private boolean marked;
        private int columnWidth;

        RowRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            this.text = String.valueOf(value);
            // PID and process name are monospace: the pid column is pure digits that
            // have to line up down the list, and a stack of JVM main classes reads as
            // one column only when the character widths are equal. The version and
            // the directory keep the UI face - they are prose, not measurements.
            this.font = switch (column) {
                case 0 -> MONO;
                case 1 -> MONO_BOLD;
                default -> UI;
            };
            this.colour = switch (column) {
                case 0 -> selected ? TEXT : MUTED;
                case 2 -> ProcessInfo.UNKNOWN.equals(text) ? DIM : ACCENT;
                case 3 -> MUTED;
                default -> TEXT;
            };
            this.marked = selected && column == 0;
            // The column width, not the painted bounds: the shared renderer keeps a
            // stale size for one paint after the columns are resized, and measuring
            // against it drew the directory past the table's right edge.
            this.columnWidth = table.getColumnModel().getColumn(column).getWidth();
            setBackground(selected ? ROW_SEL : BG);
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            // The marker is 2px wide and only on the selected row's first column.
            if (marked) {
                g.setColor(ACCENT);
                g.fillRect(0, 0, 2, getHeight());
            }

            smooth(g);
            g.setFont(font);
            g.setColor(colour);

            // Measured against the clip, not the cell: the last column may overhang
            // the viewport by a pixel or two after a resize, and a string cut by the
            // clip loses its ellipsis exactly where it is needed most.
            FontMetrics fm = g.getFontMetrics();
            int cellWidth = columnWidth > 0 ? Math.min(columnWidth, getWidth()) : getWidth();
            java.awt.Rectangle clip = g.getClipBounds();
            if (clip != null) cellWidth = Math.min(cellWidth, clip.x + clip.width);
            int available = Math.max(0, cellWidth - PAD_LEFT - PAD_RIGHT);
            String shown = fit(text, fm, available);
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(shown, PAD_LEFT, y);
            g.dispose();
        }

        private static String fit(String value, FontMetrics fm, int available) {
            if (available <= 0 || value.isEmpty()) return value;
            if (fm.stringWidth(value) <= available) return value;
            String mark = "\u2026";
            for (int keep = value.length() - 1; keep > 0; keep--) {
                String candidate = value.substring(0, keep) + mark;
                if (fm.stringWidth(candidate) <= available) return candidate;
            }
            return mark;
        }
    }

    /** Column headers in caps, dim, with the same left padding as the cells. */
    private static final class HeadRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setText(String.valueOf(value).toUpperCase(Locale.ROOT));
            setFont(SECTION);
            setForeground(DIM);
            setBackground(BG);
            setBorder(new EmptyBorder(0, 18, 0, 12));
            return this;
        }
    }

    private static final class ProcessTableModel extends AbstractTableModel {

        /**
         * Column order matches {@link #getValueAt}.
         *
         * <p>{@code version} is the game version parsed from the target's command
         * line. It is not the client jar's manifest version: those are two different
         * numbers and only one of them belongs beside a process name.
         */
        private static final String[] COLUMNS = {"PID", "PROCESS NAME", "VERSION", "GAME DIRECTORY"};

        private List<JvmScanner.Target> all = List.of();
        private List<JvmScanner.Target> visible = List.of();
        private Map<String, String> versions = Map.of();

        void setRows(List<JvmScanner.Target> rows, Map<String, String> versions) {
            this.all = List.copyOf(rows);
            this.versions = Map.copyOf(versions);
            this.visible = this.all;
            fireTableDataChanged();
        }

        void setFilter(String query) {
            String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                visible = all;
            } else {
                List<JvmScanner.Target> matches = new ArrayList<>();
                for (JvmScanner.Target target : all) {
                    if (matches(target, needle)) matches.add(target);
                }
                visible = List.copyOf(matches);
            }
            fireTableDataChanged();
        }

        /** Every column is searchable, which is what the placeholder promises. */
        private boolean matches(JvmScanner.Target target, String needle) {
            return contains(target.pid(), needle)
                    || contains(target.description(), needle)
                    || contains(versionOf(target.pid()), needle)
                    || contains(target.gameDirectory(), needle);
        }

        private static boolean contains(String value, String needle) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
        }

        String versionOf(String pid) {
            return versions.getOrDefault(pid, ProcessInfo.UNKNOWN);
        }

        JvmScanner.Target at(int row) {
            return visible.get(row);
        }

        int rowOf(String pid) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).pid().equals(pid)) return i;
            }
            return -1;
        }

        int firstMinecraftRow() {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).isMinecraft()) return i;
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            JvmScanner.Target target = visible.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> target.pid();
                case 2 -> versionOf(target.pid());
                case 3 -> target.gameDirectory() == null || target.gameDirectory().isBlank()
                        ? ProcessInfo.UNKNOWN : target.gameDirectory();
                default -> target.description();
            };
        }
    }
}
