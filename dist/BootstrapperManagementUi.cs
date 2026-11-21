using System;
using System.IO;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

internal sealed partial class SetupForm {
    private readonly List<Button> maintenanceButtons = new List<Button>();
    private void AddMaintenanceControls(Control panel) {
        FlowLayoutPanel toolbar = new FlowLayoutPanel { Location = new Point(13, 110), Height = 65, Width = 840, WrapContents = true, BackColor = Panel };
        AddMaintenanceButton(toolbar, "HEALTH", "InstallationHealth", 92, ShowHealth);
        AddMaintenanceButton(toolbar, "CHECK UPDATES", "CheckUpdates", 142, CheckUpdates);
        AddMaintenanceButton(toolbar, "LOCAL UPDATE", "LocalUpdate", 130, SelectLocalUpdate);
        AddMaintenanceButton(toolbar, "EXPORT LINUX", "ExportLinux", 136, ExportLinux);
        AddMaintenanceButton(toolbar, "UNINSTALL", "UninstallChoices", 114, UninstallChoices);
        AddMaintenanceButton(toolbar, "LAUNCH OPTIONS", "LaunchOptions", 146, ShowLaunchOptions);
        AddMaintenanceButton(toolbar, "OFFLINE PACKAGE", "EmbeddedPackage", 150, delegate {
            Bootstrapper.UseEmbeddedPackage(); LoadInventory(); heading.Text = "EMBEDDED PACKAGE SELECTED";
            AddLog("PACKAGE", "Using the embedded offline package. Check Updates selects the latest published package.", Muted);
        });
        panel.Controls.Add(toolbar);
        panel.Resize += delegate { toolbar.Width = Math.Max(1, panel.Width - 26); };
        toolbar.Width = Math.Max(1, panel.Width - 26);
    }
    private void AddMaintenanceButton(FlowLayoutPanel panel, string caption, string name, int width, Action action) {
        Button button = MakeButton(caption, false, width); button.Name = name; button.Height = 29; button.Font = small;
        button.Margin = new Padding(0, 0, 7, 0); button.Click += delegate { if (!running) action(); };
        maintenanceButtons.Add(button); panel.Controls.Add(button);
    }
    private void SetMaintenanceEnabled(bool enabled) { foreach (Button button in maintenanceButtons) button.Enabled = enabled; }
    private void MaintenanceWork(string caption, Func<object> action, Action<object> done) {
        if (running) return;
        running = true; install.Enabled = false; close.Enabled = false; open.Enabled = false; launch.Enabled = false; SetMaintenanceEnabled(false);
        heading.Text = caption; footer.Text = "WORKING / " + caption;
        BackgroundWorker task = new BackgroundWorker();
        task.DoWork += delegate(object sender, DoWorkEventArgs e) { e.Result = action(); };
        task.RunWorkerCompleted += delegate(object sender, RunWorkerCompletedEventArgs e) {
            running = false; install.Enabled = true; close.Enabled = true; open.Enabled = File.Exists(Bootstrapper.LauncherPath); launch.Enabled = true; SetMaintenanceEnabled(true);
            footer.Text = "READY / " + Bootstrapper.PackageLabel;
            task.Dispose();
            if (e.Error != null) { heading.Text = "ACTION NEEDS ATTENTION"; AddLog("ERROR", e.Error.GetBaseException().Message, Warning); ShowRecovery(caption, e.Error); return; }
            done(e.Result);
        };
        task.RunWorkerAsync();
    }
    private Form Dialog(string title, int width, int height) {
        return new Form { Text = title, Size = new Size(width, height), MinimumSize = new Size(width, height), StartPosition = FormStartPosition.CenterParent,
            BackColor = Base, ForeColor = White, Font = Font, Icon = (Icon)Icon.Clone(), ShowIcon = true, MinimizeBox = false, MaximizeBox = false };
    }
    private TextBox DetailText(string text) {
        return new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Both, WordWrap = false, Dock = DockStyle.Fill,
            Text = text.Replace("\r\n", "\n").Replace("\n", "\r\n"), BackColor = Base, ForeColor = White, Font = mono, BorderStyle = BorderStyle.None };
    }
    private FlowLayoutPanel DialogButtons(Form dialog) {
        FlowLayoutPanel controls = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 58, Padding = new Padding(12, 8, 0, 0), WrapContents = false, BackColor = Panel };
        dialog.Controls.Add(controls); return controls;
    }
    private void CopyText(string text) { try { Clipboard.SetText(text); } catch (Exception error) { MessageBox.Show(error.Message, "Clipboard unavailable"); } }
    private void ShowRecovery(string title, Exception error) {
        string instructions = Bootstrapper.RecoveryInstructions(error, Bootstrapper.CurrentStage);
        AddLog("RECOVERY", instructions.Replace("\n", " "), Warning);
        using (Form dialog = Dialog(title, 740, 390)) {
            dialog.Padding = new Padding(16); dialog.Controls.Add(DetailText(instructions));
            FlowLayoutPanel controls = DialogButtons(dialog);
            Button copy = MakeButton("COPY INSTRUCTIONS", true, 190), okay = MakeButton("CLOSE", false, 100);
            copy.Click += delegate { CopyText(instructions); }; okay.Click += delegate { dialog.Close(); };
            controls.Controls.AddRange(new Control[] { copy, okay }); dialog.ShowDialog(this);
        }
    }
    private void ShowHealth() {
        MaintenanceWork("CHECKING INSTALLATION HEALTH", delegate { return Bootstrapper.CheckHealth(); }, delegate(object value) {
            Bootstrapper.HealthReport report = (Bootstrapper.HealthReport)value;
            heading.Text = report.Healthy ? "INSTALLATION HEALTHY" : "INSTALLATION NEEDS REPAIR";
            AddLog("HEALTH", report.Healthy ? "Recorded files, runtime and client helpers verified." : "Health checks found items needing repair.", report.Healthy ? Cyan : Warning);
            bool repair = false;
            using (Form dialog = Dialog("UtilityClient installation health", 900, 560)) {
                TableLayoutPanel layout = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1, Padding = new Padding(14) };
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 65)); layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                layout.Controls.Add(new Label { Text = report.Healthy ? "Installation healthy\n" + Bootstrapper.Root : "Installation needs attention\n" + Bootstrapper.Root, Dock = DockStyle.Fill, ForeColor = report.Healthy ? Cyan : Warning }, 0, 0);
                DataGridView grid = new DataGridView { Name = "HealthResults", Dock = DockStyle.Fill, ReadOnly = true, RowHeadersVisible = false, AllowUserToAddRows = false, AllowUserToDeleteRows = false,
                    AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells, BackgroundColor = Base, BorderStyle = BorderStyle.None, SelectionMode = DataGridViewSelectionMode.FullRowSelect };
                grid.DefaultCellStyle.BackColor = Base; grid.DefaultCellStyle.ForeColor = White; grid.DefaultCellStyle.WrapMode = DataGridViewTriState.True; grid.DefaultCellStyle.SelectionBackColor = Panel;
                grid.Columns.Add("Status", "Status"); grid.Columns[0].Width = 72; grid.Columns.Add("Item", "Item"); grid.Columns[1].Width = 180; grid.Columns.Add("Detail", "Details / recovery"); grid.Columns[2].AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
                foreach (Bootstrapper.HealthItem item in report.Items) { int row = grid.Rows.Add(item.Status, item.Name, item.Detail); grid.Rows[row].DefaultCellStyle.ForeColor = item.Status == "REPAIR" ? Warning : item.Status == "OK" ? Cyan : White; }
                layout.Controls.Add(grid, 0, 1); dialog.Controls.Add(layout);
                FlowLayoutPanel controls = DialogButtons(dialog);
                Button copy = MakeButton("COPY REPORT", false, 148), fix = MakeButton("REPAIR", true, 118), dismiss = MakeButton("CLOSE", false, 100);
                copy.Click += delegate { CopyText(report.Text()); }; fix.Click += delegate { repair = true; dialog.Close(); }; dismiss.Click += delegate { dialog.Close(); };
                controls.Controls.AddRange(new Control[] { fix, copy, dismiss }); dialog.ShowDialog(this);
            }
            if (repair) BeginInstall();
        });
    }
    private void CheckUpdates() {
        MaintenanceWork("CHECKING PUBLISHED UPDATES", delegate { return Bootstrapper.FindUpdate(); }, delegate(object value) {
            Bootstrapper.UpdateRelease release = (Bootstrapper.UpdateRelease)value;
            AddLog("UPDATE", release.Description().Replace("\n", " "), release.Available ? Cyan : Muted);
            if (!release.Available) { heading.Text = "NO COMPATIBLE UPDATE PUBLISHED"; MessageBox.Show(release.Description(), "UtilityClient updates", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            if (release.Installed) { heading.Text = "YOU'RE UP TO DATE"; MessageBox.Show(release.Description(), "UtilityClient updates", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
            if (MessageBox.Show(release.Description() + "\n\nDownload, verify and install this release?", "UtilityClient update", MessageBoxButtons.YesNo, MessageBoxIcon.Information) != DialogResult.Yes) return;
            MaintenanceWork("DOWNLOADING AND VERIFYING UPDATE", delegate {
                if (Bootstrapper.SelectedUpdate == null || !String.Equals(Bootstrapper.SelectedUpdate.Digest, release.Digest, StringComparison.OrdinalIgnoreCase))
                    Bootstrapper.DownloadUpdate(release, null);
                return Bootstrapper.PackageLabel;
            }, delegate(object selected) {
                LoadInventory(); AddLog("VERIFIED", Convert.ToString(selected), Cyan); BeginInstall();
            });
        });
    }
    private void SelectLocalUpdate() {
        using (OpenFileDialog file = new OpenFileDialog { Title = "Select UtilityClient update package", Filter = "UtilityClient update|UtilityClient-Update.zip|ZIP packages|*.zip", CheckFileExists = true }) {
            if (file.ShowDialog(this) != DialogResult.OK) return;
            string source = file.FileName;
            MaintenanceWork("VERIFYING LOCAL UPDATE", delegate {
                string expected = File.Exists(source + ".sha256") ? Bootstrapper.ParseChecksum(File.ReadAllText(source + ".sha256"), Path.GetFileName(source)) : null;
                Bootstrapper.SelectUpdatePackage(source, expected, expected == null ? "Local package: structure verified" : "Local package: sidecar checksum verified"); Bootstrapper.AutoUpdates = false; return Bootstrapper.PackageLabel;
            }, delegate(object result) { LoadInventory(); heading.Text = "LOCAL PACKAGE READY TO INSTALL"; AddLog("PACKAGE", Convert.ToString(result), Cyan); });
        }
    }
    private void ExportLinux() {
        using (SaveFileDialog file = new SaveFileDialog { Title = "Export Linux bundle", Filter = "Linux ZIP bundle|*.zip", FileName = "UtilityClient-Linux.zip", AddExtension = true, DefaultExt = "zip", OverwritePrompt = true }) {
            if (file.ShowDialog(this) != DialogResult.OK) return;
            string destination = file.FileName;
            MaintenanceWork("VERIFYING AND EXPORTING LINUX", delegate { Bootstrapper.ExportLinux(destination); return destination; }, delegate(object result) {
                heading.Text = "LINUX BUNDLE EXPORTED"; AddLog("EXPORT", Convert.ToString(result) + " and .sha256", Cyan);
                MessageBox.Show("Send this ZIP and its .sha256 file to the Linux user:\n" + result + "\n\nExtract it, then run: bash install.sh\nThe bundle contains the Linux client and no Windows runtime.", "Linux bundle exported", MessageBoxButtons.OK, MessageBoxIcon.Information);
            });
        }
    }
    private void UninstallChoices() {
        bool confirmed = false, remove = false;
        using (Form dialog = Dialog("UtilityClient uninstall choices", 710, 340)) {
            dialog.Padding = new Padding(20);
            Label details = new Label { Text = "Remove the managed client, injector and launcher from:\n" + Bootstrapper.Root + "\n\nOther installations, unrelated files and the reusable setup are retained.", Dock = DockStyle.Top, Height = 95 };
            RadioButton keep = new RadioButton { Name = "KeepSettings", Text = "Keep settings, friends, profiles, logs and backups", Checked = true, Dock = DockStyle.Top, Height = 38 };
            RadioButton erase = new RadioButton { Name = "RemoveSettings", Text = "Also remove managed settings, friends, profiles, logs and backups", Dock = DockStyle.Top, Height = 38 };
            dialog.Controls.Add(erase); dialog.Controls.Add(keep); dialog.Controls.Add(details);
            FlowLayoutPanel controls = DialogButtons(dialog);
            Button uninstall = MakeButton("UNINSTALL", true, 140), cancel = MakeButton("CANCEL", false, 115);
            uninstall.Click += delegate { confirmed = true; remove = erase.Checked; dialog.Close(); }; cancel.Click += delegate { dialog.Close(); };
            controls.Controls.AddRange(new Control[] { uninstall, cancel }); dialog.ShowDialog(this);
        }
        if (!confirmed) return;
        MaintenanceWork("REMOVING MANAGED INSTALLATION", delegate { return Bootstrapper.Uninstall(remove); }, delegate(object result) {
            heading.Text = "UNINSTALL COMPLETE"; install.Text = "INSTALL"; completed = false; LoadInventory();
            AddLog("REMOVED", Convert.ToString(result), Cyan); MessageBox.Show(Convert.ToString(result), "UtilityClient uninstall complete");
        });
    }
}

internal static partial class Bootstrapper {
    internal static string RecoveryInstructions(Exception error, string stage) {
        string message = error.GetBaseException().Message, lower = message.ToLowerInvariant();
        string steps;
        if (lower.Contains("checksum") || lower.Contains("integrity") || lower.Contains("package") || lower.Contains("manifest"))
            steps = "1. Use Check Updates or obtain a fresh complete UtilityClient package.\n2. Choose Repair to restore verified program files.\n3. Preserve settings; do not copy damaged JARs over the repaired installation.";
        else if (lower.Contains("running") || lower.Contains("locked") || lower.Contains("used by another process"))
            steps = "1. Close the UtilityClient injector and launcher.\n2. Close Minecraft if it is holding a client JAR.\n3. Retry the same operation. A rollback message identifies any retained recovery files.";
        else if (lower.Contains("network") || lower.Contains("connect") || lower.Contains("timed out") || lower.Contains("remote") || lower.Contains("resolve"))
            steps = "1. Check the network connection and retry Check Updates.\n2. Use the embedded offline package or a local update ZIP with its checksum.\n3. Keep the existing installation while downloads are unavailable.";
        else if (error is UnauthorizedAccessException || lower.Contains("access") || lower.Contains("permission"))
            steps = "1. Choose a writable folder owned by the current Windows account.\n2. Check that antivirus or a file lock is not blocking this operation.\n3. Retry with the same per-user setup; administrator installation is not required.";
        else steps = "1. Open Installation Health and copy the report.\n2. Choose Repair with the selected verified package.\n3. If it still fails, keep the exact error and the launcher.log file from the install folder.";
        return stage + "\n" + message + "\n\n" + steps + "\n\nInstallation: " + Root;
    }
}
