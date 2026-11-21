using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using System.Runtime.InteropServices;

internal sealed partial class SetupForm : Form {
    private static readonly Color Base = Color.FromArgb(17, 17, 17);
    private static readonly Color Panel = Color.FromArgb(30, 30, 30);
    private static readonly Color Line = Color.FromArgb(44, 44, 44);
    private static readonly Color White = Color.FromArgb(228, 231, 232);
    private static readonly Color Muted = Color.FromArgb(126, 137, 145);
    private static readonly Color Cyan = Color.FromArgb(73, 198, 201);
    private static readonly Color Warning = Color.FromArgb(235, 166, 94);
    private readonly Font mono = new Font("Consolas", 9f);
    private readonly Font small = new Font("Consolas", 8f);
    private readonly Label heading = new Label();
    private readonly Label percent = new Label();
    private readonly Label metrics = new Label();
    private readonly Label footer = new Label();
    private readonly ThinProgress bar = new ThinProgress();
    private readonly StageStrip stages = new StageStrip();
    private readonly DataGridView files = new DataGridView();
    private readonly ListBox log = new ListBox();
    private readonly ToolTip logTip = new ToolTip();
    private readonly Button install;
    private readonly Button close;
    private readonly Button open;
    private readonly CheckBox launch = new CheckBox();
    private readonly BackgroundWorker worker = new BackgroundWorker();
    private readonly Dictionary<string, DataGridViewRow> fileRows = new Dictionary<string, DataGridViewRow>();
    private bool running, completed;
    private long totalBytes, bytes;
    private int completedFiles, fileCount;

    private sealed class FileState { internal string Name; internal long Size, Written; }
    private sealed class StageUpdate { internal int Number; internal string Message; }
    private sealed class LogEntry {
        internal string Time, Tag, Message; internal Color Color;
        public override string ToString() { return Time + " [" + Tag + "] " + Message; }
    }

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("user32.dll")] private static extern bool ReleaseCapture();
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    protected override void WndProc(ref Message message) {
        base.WndProc(ref message);
        if (message.Msg != 0x84 || WindowState != FormWindowState.Normal) return;
        long coordinates = message.LParam.ToInt64();
        Point point = PointToClient(new Point((short)(coordinates & 0xffff), (short)((coordinates >> 16) & 0xffff)));
        bool left = point.X < 6, right = point.X >= ClientSize.Width - 6;
        bool top = point.Y < 6, bottom = point.Y >= ClientSize.Height - 6;
        int edge = top ? (left ? 13 : right ? 14 : 12) : bottom ? (left ? 16 : right ? 17 : 15) : left ? 10 : right ? 11 : 0;
        if (edge != 0) message.Result = new IntPtr(edge);
    }
    protected override void OnHandleCreated(EventArgs e) {
        base.OnHandleCreated(e);
        try { int dark = 1; DwmSetWindowAttribute(Handle, 20, ref dark, 4); }
        catch (DllNotFoundException) { }
        catch (EntryPointNotFoundException) { }
    }

    internal SetupForm() {
        Text = "UtilityClient bootstrapper";
        FormBorderStyle = FormBorderStyle.None;
        ClientSize = new Size(880, 680);
        MinimumSize = new Size(760, 620);
        BackColor = Line; ForeColor = White;
        Font = new Font("Segoe UI", 9f);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = AppBrand.FormIcon(); ShowIcon = true; ShowInTaskbar = true;
        DoubleBuffered = true;
        Padding = new Padding(1);

        TableLayoutPanel layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 7, BackColor = Base, Margin = Padding.Empty };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        foreach (float height in new float[] { 43, 35, 181 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        foreach (float height in new float[] { 122, 66, 26 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
        Controls.Add(layout);

        BorderedPanel header = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Panel };
        header.Paint += delegate(object sender, PaintEventArgs e) {
            AppBrand.DrawHeader(e.Graphics);
            using (Font brand = new Font("Segoe UI", 13f, FontStyle.Bold)) {
                TextRenderer.DrawText(e.Graphics, "UTILITY", brand, new Point(52, 10), White);
                int width = TextRenderer.MeasureText("UTILITY", brand).Width;
                TextRenderer.DrawText(e.Graphics, "//", brand, new Point(49 + width, 10), Cyan);
                TextRenderer.DrawText(e.Graphics, "CLIENT", brand, new Point(49 + width + 23, 10), White);
            }
            TextRenderer.DrawText(e.Graphics, "BOOTSTRAPPER / LOCAL", small, new Rectangle(0, 0, header.Width - 82, header.Height), Muted,
                    TextFormatFlags.Right | TextFormatFlags.VerticalCenter);
        };
        Button minimize = new Button { Text = "\u2014", Dock = DockStyle.Right, Width = 34, FlatStyle = FlatStyle.Flat, ForeColor = Muted,
                BackColor = Panel, Font = new Font("Segoe UI", 12f), TabStop = false };
        Button dismiss = new Button { Text = "\u00d7", Dock = DockStyle.Right, Width = 34, FlatStyle = FlatStyle.Flat, ForeColor = Muted,
                BackColor = Panel, Font = new Font("Segoe UI", 15f), TabStop = false };
        minimize.FlatAppearance.BorderSize = 0; dismiss.FlatAppearance.BorderSize = 0;
        minimize.Click += delegate { WindowState = FormWindowState.Minimized; };
        dismiss.Click += delegate { if (!running) Close(); };
        header.Controls.AddRange(new Control[] { minimize, dismiss });
        header.MouseDown += delegate(object sender, MouseEventArgs e) {
            if (e.Button == MouseButtons.Left) { ReleaseCapture(); SendMessage(Handle, 0xA1, new IntPtr(2), IntPtr.Zero); }
        };
        header.MouseDoubleClick += delegate(object sender, MouseEventArgs e) {
            MaximizedBounds = Screen.FromControl(this).WorkingArea;
            WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;
        };
        layout.Controls.Add(header, 0, 0);
        layout.Controls.Add(stages, 0, 1);

        BorderedPanel overview = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Panel };
        heading.Text = File.Exists(Path.Combine(Bootstrapper.Root, "injector.jar")) ? "READY TO REPAIR" : "READY TO INSTALL";
        heading.Font = new Font("Segoe UI", 16f, FontStyle.Bold); heading.ForeColor = White;
        heading.SetBounds(13, 20, 660, 35);
        percent.Text = "0%"; percent.Font = new Font("Consolas", 23f, FontStyle.Bold); percent.ForeColor = Cyan;
        percent.TextAlign = ContentAlignment.TopRight; percent.SetBounds(746, 16, 118, 40);
        bar.SetBounds(14, 63, 850, 6);
        metrics.SetBounds(13, 79, 850, 22); metrics.Font = small; metrics.ForeColor = Muted;
        overview.Controls.AddRange(new Control[] { heading, percent, bar, metrics });
        AddMaintenanceControls(overview);
        overview.Resize += delegate {
            heading.SetBounds(13, 20, Math.Max(150, overview.Width - 160), 35);
            percent.SetBounds(overview.Width - 132, 16, 118, 40);
            bar.SetBounds(14, 63, Math.Max(0, overview.Width - 28), 6);
            metrics.SetBounds(13, 79, Math.Max(0, overview.Width - 26), 22);
        };
        layout.Controls.Add(overview, 0, 2);

        BorderedPanel filePanel = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Base };
        files.Dock = DockStyle.Fill; files.BackgroundColor = Base; files.BorderStyle = BorderStyle.None;
        files.ReadOnly = true; files.RowHeadersVisible = false; files.ColumnHeadersVisible = false;
        files.AllowUserToAddRows = false; files.AllowUserToDeleteRows = false; files.AllowUserToResizeRows = false;
        files.AllowUserToResizeColumns = false; files.MultiSelect = false; files.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        files.CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal; files.GridColor = Color.FromArgb(27, 27, 27);
        files.DefaultCellStyle.BackColor = Base; files.DefaultCellStyle.ForeColor = White; files.DefaultCellStyle.Font = mono;
        files.DefaultCellStyle.SelectionBackColor = Color.FromArgb(24, 37, 39); files.DefaultCellStyle.SelectionForeColor = White;
        files.DefaultCellStyle.Padding = new Padding(12, 0, 8, 0);
        files.RowTemplate.Height = 28; files.EnableHeadersVisualStyles = false;
        files.Columns.Add(new DataGridViewTextBoxColumn { Name = "Name", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill, FillWeight = 70 });
        files.Columns.Add(new DataGridViewTextBoxColumn { Name = "Size", Width = 90, DefaultCellStyle = new DataGridViewCellStyle { Alignment = DataGridViewContentAlignment.MiddleRight, ForeColor = Muted } });
        files.Columns.Add(new DataGridViewTextBoxColumn { Name = "Progress", Width = 200 });
        files.CellPainting += PaintFile;
        filePanel.Controls.Add(files);
        filePanel.Controls.Add(Section("FILES", "PACKAGE: OFFLINE"));
        layout.Controls.Add(filePanel, 0, 3);

        BorderedPanel logPanel = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Base };
        log.Dock = DockStyle.Fill; log.BackColor = Color.FromArgb(10, 10, 10); log.ForeColor = White;
        log.BorderStyle = BorderStyle.None; log.Font = small;
        log.DrawMode = DrawMode.OwnerDrawFixed; log.ItemHeight = 16; log.IntegralHeight = false;
        log.DrawItem += delegate(object sender, DrawItemEventArgs e) {
            if (e.Index < 0 || e.Index >= log.Items.Count) return;
            LogEntry entry = (LogEntry)log.Items[e.Index];
            using (SolidBrush fill = new SolidBrush(log.BackColor)) e.Graphics.FillRectangle(fill, e.Bounds);
            TextRenderer.DrawText(e.Graphics, entry.Time, small, new Point(e.Bounds.Left + 13, e.Bounds.Top + 1), Muted);
            TextRenderer.DrawText(e.Graphics, "[" + entry.Tag + "] " + entry.Message, small,
                    new Rectangle(e.Bounds.Left + 72, e.Bounds.Top, Math.Max(0, e.Bounds.Width - 83), e.Bounds.Height), entry.Color,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        };
        log.MouseMove += delegate(object sender, MouseEventArgs e) {
            int index = log.IndexFromPoint(e.Location);
            logTip.SetToolTip(log, index >= 0 ? log.Items[index].ToString() : "");
        };
        logPanel.Controls.Add(log); logPanel.Controls.Add(Section("LOG", ""));
        layout.Controls.Add(logPanel, 0, 4);

        BorderedPanel actions = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Panel, Padding = new Padding(13, 12, 13, 12) };
        install = MakeButton("REPAIR", false, 114);
        close = MakeButton("CLOSE", false, 88);
        open = MakeButton("LAUNCH", true, 150);
        open.Enabled = File.Exists(Bootstrapper.LauncherPath);
        FlowLayoutPanel buttons = new FlowLayoutPanel { Dock = DockStyle.Right, Width = 376, WrapContents = false, BackColor = Panel, Margin = Padding.Empty };
        buttons.Controls.AddRange(new Control[] { install, close, open });
        Label verified = new Label { Text = "\u2713  Verify checksums", ForeColor = White, AutoSize = true, Location = new Point(13, 22) };
        launch.Text = "Launch when done"; launch.AutoSize = true; launch.Checked = true; launch.ForeColor = White; launch.BackColor = Panel;
        launch.Location = new Point(150, 19);
        actions.Controls.AddRange(new Control[] { buttons, verified, launch });
        layout.Controls.Add(actions, 0, 5);

        BorderedPanel bottom = new BorderedPanel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(12, 12, 12) };
        footer.Text = "READY / NO NETWORK REQUIRED"; footer.Font = small; footer.ForeColor = Muted;
        footer.AutoSize = true; footer.Location = new Point(13, 6);
        LinkLabel folder = new LinkLabel { Text = "OPEN INSTALL FOLDER", Font = small, AutoSize = true, LinkColor = Cyan, ActiveLinkColor = White,
                VisitedLinkColor = Cyan, LinkBehavior = LinkBehavior.HoverUnderline };
        bottom.Controls.AddRange(new Control[] { footer, folder });
        bottom.Resize += delegate { folder.Location = new Point(bottom.Width - folder.Width - 14, 6); };
        folder.LinkClicked += delegate { Directory.CreateDirectory(Bootstrapper.Root); Process.Start("explorer.exe", "\"" + Bootstrapper.Root + "\""); };
        layout.Controls.Add(bottom, 0, 6);

        LoadInventory();
        Shown += delegate { files.ClearSelection(); ActiveControl = install; CheckStartupUpdates(); };
        install.Text = File.Exists(Path.Combine(Bootstrapper.Root, "injector.jar")) ? "REPAIR" : "INSTALL";
        AddLog("LOCAL", Bootstrapper.PackageLabel + ": client, injector, and private Java 25.", Muted);
        AddLog("PATH", "Install location: " + Bootstrapper.Root, Muted);
        AddLog("READY", "Settings are preserved. " + install.Text + " installs the package and repairs shortcuts.", Cyan);
        install.Click += delegate { BeginInstall(); };
        close.Click += delegate { if (!running) Close(); };
        open.Click += delegate { LaunchInjector(); };
        worker.WorkerReportsProgress = true;
        worker.DoWork += delegate {
            string updateMessage = Bootstrapper.PrepareLatestUpdate();
            worker.ReportProgress(0, updateMessage);
            Bootstrapper.Install(delegate(int number, string message) { worker.ReportProgress(0, new StageUpdate { Number = number, Message = message }); },
                    delegate(Bootstrapper.PackageProgress update) { worker.ReportProgress(0, update); });
        };
        worker.ProgressChanged += delegate(object sender, ProgressChangedEventArgs e) {
            string updateMessage = e.UserState as string;
            if (updateMessage != null) { LoadInventory(); AddLog("UPDATE", updateMessage, Cyan); return; }
            StageUpdate stage = e.UserState as StageUpdate;
            if (stage != null) ApplyStage(stage);
            else ApplyFile((Bootstrapper.PackageProgress)e.UserState);
        };
        worker.RunWorkerCompleted += InstallFinished;
        FormClosing += delegate(object sender, FormClosingEventArgs e) { if (running) e.Cancel = true; };
    }

    private Control Section(string left, string right) {
        BorderedPanel section = new BorderedPanel { Dock = DockStyle.Top, Height = 27, BackColor = Panel };
        section.Paint += delegate(object sender, PaintEventArgs e) {
            TextRenderer.DrawText(e.Graphics, left, small, new Point(13, 7), Muted);
            TextRenderer.DrawText(e.Graphics, right, small, new Rectangle(0, 0, section.Width - 13, section.Height), Muted,
                    TextFormatFlags.Right | TextFormatFlags.VerticalCenter);
        };
        return section;
    }

    private Button MakeButton(string text, bool primary, int width) {
        Button button = new Button { Text = text, Width = width, Height = 40, FlatStyle = FlatStyle.Flat,
                BackColor = primary ? Cyan : Color.FromArgb(25, 25, 25), ForeColor = primary ? Color.FromArgb(9, 21, 22) : White,
                Font = new Font("Consolas", 10f, FontStyle.Bold), Margin = new Padding(0, 0, 10, 0), Cursor = Cursors.Hand };
        button.FlatAppearance.BorderColor = primary ? Cyan : Line;
        button.FlatAppearance.MouseOverBackColor = primary ? Color.FromArgb(91, 216, 217) : Color.FromArgb(39, 39, 39);
        button.EnabledChanged += delegate {
            button.BackColor = primary && button.Enabled ? Cyan : Color.FromArgb(25, 25, 25);
            button.ForeColor = button.Enabled ? (primary ? Color.FromArgb(9, 21, 22) : White) : Muted;
            button.FlatAppearance.BorderColor = primary && button.Enabled ? Cyan : Line;
        };
        return button;
    }

    private void LoadInventory() {
        fileRows.Clear(); files.Rows.Clear(); totalBytes = 0; fileCount = 0; completedFiles = 0; bytes = 0;
        using (Stream payload = Bootstrapper.OpenPayload())
        using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read)) {
            List<ZipArchiveEntry> inventory = new List<ZipArchiveEntry>();
            foreach (ZipArchiveEntry entry in archive.Entries) if (entry.Name.Length != 0) inventory.Add(entry);
            inventory.Sort(delegate(ZipArchiveEntry a, ZipArchiveEntry b) {
                int group = (a.FullName.Contains("/") ? 1 : 0).CompareTo(b.FullName.Contains("/") ? 1 : 0);
                return group != 0 ? group : String.Compare(a.FullName, b.FullName, StringComparison.OrdinalIgnoreCase);
            });
            foreach (ZipArchiveEntry entry in inventory) {
                int index = files.Rows.Add(entry.FullName, FormatBytes(entry.Length), "0%");
                DataGridViewRow row = files.Rows[index];
                row.Tag = new FileState { Name = entry.FullName, Size = entry.Length };
                row.Cells[0].ToolTipText = entry.FullName;
                fileRows[entry.FullName] = row;
                totalBytes += entry.Length; fileCount++;
            }
        }
        files.ClearSelection();
        UpdateMetrics();
    }

    private static string FormatBytes(long value) {
        if (value >= 1024 * 1024) return (value / (1024d * 1024)).ToString("0.0") + " MB";
        if (value >= 1024) return (value / 1024d).ToString("0") + " KB";
        return value + " B";
    }

    private void UpdateMetrics() {
        metrics.Text = "MODE " + (Bootstrapper.SelectedUpdate == null ? "OFFLINE" : "UPDATE") + "     FILES " + completedFiles + "/" + fileCount + "     UNPACKED " + FormatBytes(bytes) + " / " + FormatBytes(totalBytes);
    }

    private void BeginInstall() {
        if (running) return;
        completed = false; running = true; bytes = 0; completedFiles = 0;
        heading.ForeColor = White;
        install.Enabled = false; close.Enabled = false; open.Enabled = false; launch.Enabled = false;
        SetMaintenanceEnabled(false);
        foreach (DataGridViewRow row in files.Rows) ((FileState)row.Tag).Written = 0;
        files.Invalidate(); SetPercent(0); UpdateMetrics();
        footer.Text = Bootstrapper.AutoUpdates ? "CHECKING LATEST RELEASE / DO NOT CLOSE" : "WORKING / OFFLINE PACKAGE SELECTED";
        if (Bootstrapper.AutoUpdates) heading.Text = "CHECKING LATEST CLIENT";
        worker.RunWorkerAsync();
    }

    private void ApplyStage(StageUpdate value) {
        string[] titles = { "CHECKING SYSTEM", "VERIFYING PACKAGE", "UNPACKING RUNTIME", "INSTALLING CLIENT", "SETTING UP LAUNCHER", "INSTALL COMPLETE" };
        int[] progress = { 0, 5, 10, 85, 95, 100 };
        heading.Text = titles[value.Number - 1]; SetPercent(progress[value.Number - 1]);
        stages.Active = value.Number == 1 ? 0 : value.Number <= 3 ? 1 : value.Number <= 5 ? 2 : 3;
        stages.Invalidate();
        AddLog(value.Number == 6 ? "READY" : "STEP " + value.Number, value.Message, value.Number == 6 ? Cyan : White);
    }

    private void ApplyFile(Bootstrapper.PackageProgress update) {
        DataGridViewRow row;
        if (fileRows.TryGetValue(update.FileName, out row)) {
            ((FileState)row.Tag).Written = update.FileBytes;
            files.InvalidateRow(row.Index);
        }
        bytes = update.Bytes; completedFiles = update.CompletedFiles;
        SetPercent(10 + (totalBytes == 0 ? 70 : (int)(70d * bytes / totalBytes)));
        UpdateMetrics();
    }

    private void SetPercent(int value) { bar.Value = Math.Max(0, Math.Min(100, value)); percent.Text = bar.Value + "%"; }

    private void InstallFinished(object sender, RunWorkerCompletedEventArgs e) {
        running = false; install.Enabled = true; close.Enabled = true; launch.Enabled = true;
        SetMaintenanceEnabled(true);
        open.Enabled = File.Exists(Bootstrapper.LauncherPath);
        if (e.Error != null) {
            heading.Text = "INSTALL FAILED"; heading.ForeColor = Warning; install.Text = "RETRY";
            footer.Text = "FAILED / SEE THE LOG ABOVE";
            AddLog("ERROR", Bootstrapper.CurrentStage + ": " + e.Error.GetBaseException().Message, Warning);
            ShowRecovery("UtilityClient installation failed", e.Error);
            return;
        }
        completed = true; heading.ForeColor = White; install.Text = "RE-CHECK";
        footer.Text = "INSTALLED / LAUNCHER STARTUP VERIFIED";
        SetPercent(100); AddLog("OK", "Launcher startup check passed. Use LAUNCH to open the injector.", Cyan);
        if (!String.IsNullOrEmpty(Bootstrapper.LastShortcutWarning)) {
            footer.Text = "INSTALLED / SHORTCUTS NEED ATTENTION";
            AddLog("WARNING", Bootstrapper.LastShortcutWarning, Warning);
            MessageBox.Show(Bootstrapper.LastShortcutWarning, "UtilityClient shortcuts", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        if (launch.Checked) LaunchInjector();
    }

    private void LaunchInjector() {
        try { Bootstrapper.Launch(); AddLog("LAUNCH", "Opening injector. Startup details: " + Path.Combine(Bootstrapper.Root, "launcher.log"), Cyan); }
        catch (Exception error) { AddLog("ERROR", error.Message, Warning); MessageBox.Show(error.Message, "Injector launch failed", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }

    private void AddLog(string tag, string message, Color color) {
        log.Items.Add(new LogEntry { Time = DateTime.Now.ToString("HH:mm:ss"), Tag = tag, Message = message, Color = color });
        if (log.Items.Count > 500) log.Items.RemoveAt(0);
        log.TopIndex = Math.Max(0, log.Items.Count - Math.Max(1, log.ClientSize.Height / log.ItemHeight));
    }

    private void PaintFile(object sender, DataGridViewCellPaintingEventArgs e) {
        if (e.RowIndex < 0 || (e.ColumnIndex != 0 && e.ColumnIndex != 2)) return;
        FileState state = (FileState)files.Rows[e.RowIndex].Tag;
        if (state == null) return;
        e.PaintBackground(e.ClipBounds, true);
        bool done = completed || state.Written >= state.Size && state.Written > 0;
        if (e.ColumnIndex == 0) {
            Rectangle name = new Rectangle(e.CellBounds.Left + 30, e.CellBounds.Top, e.CellBounds.Width - 37, e.CellBounds.Height);
            TextRenderer.DrawText(e.Graphics, done ? "\u2713" : "\u00b7", mono, new Rectangle(e.CellBounds.Left + 10, e.CellBounds.Top, 16, e.CellBounds.Height), done ? Cyan : Muted, TextFormatFlags.VerticalCenter);
            TextRenderer.DrawText(e.Graphics, state.Name, mono, name, White, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        } else {
            int value = state.Size == 0 ? (completed ? 100 : 0) : (int)Math.Min(100, state.Written * 100d / state.Size);
            Rectangle rail = new Rectangle(e.CellBounds.Left + 8, e.CellBounds.Top + e.CellBounds.Height / 2, Math.Max(10, e.CellBounds.Width - 70), 2);
            using (SolidBrush background = new SolidBrush(Line)) e.Graphics.FillRectangle(background, rail);
            using (SolidBrush fill = new SolidBrush(Cyan)) e.Graphics.FillRectangle(fill, rail.Left, rail.Top, rail.Width * value / 100, rail.Height);
            TextRenderer.DrawText(e.Graphics, value + "%", mono, new Rectangle(e.CellBounds.Right - 59, e.CellBounds.Top, 51, e.CellBounds.Height), value > 0 ? Cyan : Muted,
                    TextFormatFlags.Right | TextFormatFlags.VerticalCenter);
        }
        e.Handled = true;
    }

    private sealed class BorderedPanel : System.Windows.Forms.Panel {
        internal BorderedPanel() { Margin = Padding.Empty; DoubleBuffered = true; }
        protected override void OnPaint(PaintEventArgs e) {
            base.OnPaint(e);
            using (Pen pen = new Pen(Line)) e.Graphics.DrawLine(pen, 0, Height - 1, Width, Height - 1);
        }
    }

    private sealed class ThinProgress : Control {
        private int value;
        internal int Value { get { return value; } set { this.value = value; Invalidate(); } }
        internal ThinProgress() { DoubleBuffered = true; }
        protected override void OnPaint(PaintEventArgs e) {
            e.Graphics.Clear(Line);
            using (SolidBrush fill = new SolidBrush(Cyan)) e.Graphics.FillRectangle(fill, 0, 0, Width * value / 100, Height);
        }
    }

    private sealed class StageStrip : Control {
        internal int Active;
        internal StageStrip() { Dock = DockStyle.Fill; Margin = Padding.Empty; DoubleBuffered = true; }
        protected override void OnPaint(PaintEventArgs e) {
            e.Graphics.Clear(Base);
            string[] names = { "01 CHECK", "02 PACKAGE", "03 INSTALL", "04 READY" };
            using (Font font = new Font("Consolas", 8f, FontStyle.Bold))
            using (Pen border = new Pen(Line))
            using (SolidBrush fill = new SolidBrush(Cyan)) {
                for (int i = 0; i < 4; i++) {
                    int left = Width * i / 4, right = Width * (i + 1) / 4;
                    e.Graphics.DrawLine(border, right - 1, 0, right - 1, Height);
                    TextRenderer.DrawText(e.Graphics, names[i], font, new Rectangle(left + 13, 0, right - left - 18, Height), i <= Active ? Cyan : Muted, TextFormatFlags.VerticalCenter);
                    if (i == Active) e.Graphics.FillRectangle(fill, left, Height - 2, right - left, 2);
                }
                e.Graphics.DrawLine(border, 0, Height - 1, Width, Height - 1);
            }
        }
    }
}
