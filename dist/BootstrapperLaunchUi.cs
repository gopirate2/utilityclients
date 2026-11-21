using System;
using System.IO;
using System.Drawing;
using System.Windows.Forms;

internal sealed partial class SetupForm {
    private void ShowLaunchOptions() {
        InjectorLaunchOptions value;
        try { value = LaunchOptionsFile.Read(Bootstrapper.Root); }
        catch (Exception error) { ShowRecovery("Launch options", error); value = new InjectorLaunchOptions(); }
        using (Form dialog = Dialog("UtilityClient launch options", 780, 510)) {
            TableLayoutPanel body = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, Padding = new Padding(18), AutoScroll = true };
            body.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 215)); body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); body.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 82));
            int row = 0;
            CheckBox auto = LaunchCheck("AutoInject", "Auto inject when Minecraft starts", value.AutoInject);
            CheckBox repeat = LaunchCheck("ReinjectOnRestart", "Inject again after the game restarts", value.ReinjectOnRestart);
            CheckBox minimized = LaunchCheck("StartMinimized", "Start the injector minimized", value.StartMinimized);
            CheckBox multiple = LaunchCheck("NewestMatchingGame", "Use the newest game when several match", value.AllowMultiple);
            foreach (CheckBox item in new CheckBox[] { auto, repeat, minimized, multiple }) { body.Controls.Add(item, 0, row); body.SetColumnSpan(item, 3); row++; }
            NumericUpDown warmup = new NumericUpDown { Name = "WarmupSeconds", Minimum = 0, Maximum = 120, Value = value.WarmupSeconds, Width = 100, BackColor = Panel, ForeColor = White };
            NumericUpDown interval = new NumericUpDown { Name = "ScanIntervalMs", Minimum = 500, Maximum = 15000, Increment = 250, Value = value.ScanIntervalMs, Width = 100, BackColor = Panel, ForeColor = White };
            AddLaunchRow(body, row++, "Game startup wait (seconds)", warmup); AddLaunchRow(body, row++, "Scan interval (milliseconds)", interval);
            TextBox directory = new TextBox { Name = "GameDirectory", Text = value.GameDirectory, Dock = DockStyle.Fill, BackColor = Panel, ForeColor = White };
            AddLaunchRow(body, row, "Game folder (blank matches any)", directory);
            Button browse = MakeButton("BROWSE", false, 76); browse.Height = 25; body.Controls.Add(browse, 2, row++);
            browse.Click += delegate { using (FolderBrowserDialog folder = new FolderBrowserDialog { Description = "Select the Minecraft game folder to match", SelectedPath = directory.Text }) if (folder.ShowDialog(dialog) == DialogResult.OK) directory.Text = folder.SelectedPath; };
            Label detail = new Label { Text = "Multiple matching games pause auto-injection by default.\nSaving options does not create shortcuts or enable Windows startup.", AutoSize = true, ForeColor = Muted, Margin = new Padding(0, 12, 0, 8) };
            body.Controls.Add(detail, 0, row); body.SetColumnSpan(detail, 3); row++;
            FlowLayoutPanel links = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true, Margin = Padding.Empty };
            AddLaunchLink(links, "DESKTOP SHORTCUT", "CreateDesktopShortcut", 170, delegate { Bootstrapper.UserShortcut("desktop", true); });
            AddLaunchLink(links, "START MENU SHORTCUT", "CreateStartMenuShortcut", 184, delegate { Bootstrapper.UserShortcut("start-menu", true); });
            AddLaunchLink(links, "ENABLE STARTUP", "EnableStartup", 148, delegate { Bootstrapper.UserShortcut("startup", true); });
            AddLaunchLink(links, "DISABLE STARTUP", "DisableStartup", 154, delegate { Bootstrapper.UserShortcut("startup", false); });
            body.Controls.Add(links, 0, row); body.SetColumnSpan(links, 3); dialog.Controls.Add(body);
            FlowLayoutPanel controls = DialogButtons(dialog);
            Button save = MakeButton("SAVE OPTIONS", true, 152), closeOptions = MakeButton("CLOSE", false, 100); save.Name = "SaveLaunchOptions";
            save.Click += delegate {
                try {
                    value.AutoInject = auto.Checked; value.ReinjectOnRestart = repeat.Checked; value.StartMinimized = minimized.Checked; value.AllowMultiple = multiple.Checked;
                    value.WarmupSeconds = (int)warmup.Value; value.ScanIntervalMs = (int)interval.Value; value.GameDirectory = directory.Text.Trim();
                    LaunchOptionsFile.Save(Bootstrapper.Root, value); AddLog("OPTIONS", "Saved launch options for the injector. Windows startup changes only through its buttons.", Cyan);
                    dialog.Close();
                } catch (Exception error) { MessageBox.Show(dialog, error.Message, "Launch options", MessageBoxButtons.OK, MessageBoxIcon.Error); }
            };
            closeOptions.Click += delegate { dialog.Close(); }; controls.Controls.AddRange(new Control[] { save, closeOptions });
            dialog.ShowDialog(this);
        }
    }
    private CheckBox LaunchCheck(string name, string text, bool value) {
        return new CheckBox { Name = name, Text = text, Checked = value, AutoSize = true, ForeColor = White, BackColor = Base, Margin = new Padding(0, 0, 0, 10) };
    }
    private void AddLaunchRow(TableLayoutPanel body, int row, string text, Control value) {
        body.Controls.Add(new Label { Text = text, AutoSize = true, ForeColor = White, Margin = new Padding(0, 4, 8, 10) }, 0, row);
        body.Controls.Add(value, 1, row);
    }
    private void AddLaunchLink(FlowLayoutPanel panel, string text, string name, int width, Action action) {
        Button button = MakeButton(text, false, width); button.Name = name; button.Height = 28; button.Font = small;
        button.Enabled = File.Exists(Bootstrapper.LauncherPath); button.Margin = new Padding(0, 0, 7, 7);
        button.Click += delegate {
            try { action(); AddLog("SHORTCUT", text + " completed for this installation.", Cyan); }
            catch (Exception error) { MessageBox.Show(error.Message, "Windows integration", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        panel.Controls.Add(button);
    }
}
