using System;
using System.IO;
using System.Text;
using System.Diagnostics;
using System.Threading;
using System.Collections.Generic;
using System.Windows.Forms;
using System.Runtime.InteropServices;

// A persistent launcher gives Desktop/Start-menu shortcuts a stable EXE target
// and keeps startup errors visible instead of losing them behind javaw.exe.
internal static class WindowsLauncher {
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr window);
    private static readonly object LogLock = new object();
    private static string logPath;
    private static bool check;
    private static bool commandMode;

    internal static string Quote(string value) {
        StringBuilder text = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') text.Append('\\', slashes * 2 + 1);
            else text.Append('\\', slashes);
            text.Append(c); slashes = 0;
        }
        text.Append('\\', slashes * 2).Append('"');
        return text.ToString();
    }

    private static void Log(string message) {
        lock (LogLock) {
            try { File.AppendAllText(logPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine, new UTF8Encoding(false)); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    [STAThread]
    private static int Main(string[] args) {
        string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "depends");
        List<string> forwarded = new List<string>();
        string shortcut = null, shortcutFolder = null; bool removeShortcut = false;
        try {
            for (int i = 0; i < args.Length; i++) {
                if (args[i] == "--root") root = Path.GetFullPath(args[++i]);
                else if (args[i] == "--check") check = true;
                else if (args[i] == "--create-shortcut") { shortcut = args[++i]; commandMode = true; }
                else if (args[i] == "--remove-shortcut") { shortcut = args[++i]; removeShortcut = true; commandMode = true; }
                else if (args[i] == "--shortcut-folder") shortcutFolder = Path.GetFullPath(args[++i]);
                else forwarded.Add(args[i]);
            }
            Directory.CreateDirectory(root);
            logPath = Path.Combine(root, "launcher.log");
            if (shortcut != null) {
                string folder;
                if (shortcut == "desktop") folder = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                else if (shortcut == "start-menu") folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "UtilityClient");
                else if (shortcut == "startup") folder = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
                else throw new ArgumentException("Shortcut kind must be desktop, start-menu, or startup");
                if (shortcutFolder != null) folder = shortcutFolder;
                string target = Path.Combine(root, "UtilityClient.exe");
                if (!File.Exists(target)) throw new IOException("Install the launcher before creating shortcuts");
                if (removeShortcut) WindowsShortcuts.RemoveOwned(target, root, folder);
                else WindowsShortcuts.Create(target, "--root " + Quote(root) + " --gui" + (shortcut == "startup" ? " --startup" : ""), folder, root);
                Console.OutputEncoding = new UTF8Encoding(false);
                Console.WriteLine((removeShortcut ? "Removed owned shortcut: " : "Created shortcut: ") + Path.Combine(folder, "UtilityClient.lnk"));
                return 0;
            }
            string java = File.ReadAllText(Path.Combine(root, "java.path"), Encoding.UTF8).Trim();
            string jar = Path.Combine(root, "injector.jar");
            if (!Path.IsPathRooted(java) || !File.Exists(java)) throw new IOException("Private Java runtime is missing. Run UtilityClient setup and choose Repair / reinstall.");
            if (!File.Exists(jar)) throw new IOException("injector.jar is missing. Run UtilityClient setup and choose Repair / reinstall.");
            if (check) forwarded = new List<string>(new string[] { "--help" });
            string options = Quote("-Dutilityclient.injectorRoot=" + Path.GetFullPath(root)) + " -jar " + Quote(jar);
            foreach (string value in forwarded) options += " " + Quote(value);
            Log("Starting injector with " + java + "; client files: " + root);
            bool cli = check || forwarded.Exists(delegate(string value) { return value == "--headless" || value == "--list" || value == "--verify" || value == "--inject"
                    || value == "--unload" || value == "--help" || value == "--jvm-args"; });
            bool background = !cli && (forwarded.Contains("--startup") || forwarded.Contains("--minimized"));
            StringBuilder output = new StringBuilder();
            using (Process child = new Process()) {
                child.StartInfo = new ProcessStartInfo(java, options) {
                    UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = root,
                    RedirectStandardOutput = true, RedirectStandardError = true
                };
                DataReceivedEventHandler receive = delegate(object sender, DataReceivedEventArgs e) {
                    if (e.Data == null) return;
                    lock (output) { if (output.Length < 65536) output.AppendLine(e.Data); }
                    Log(e.Data);
                    if (cli || check) Console.WriteLine(e.Data);
                };
                child.OutputDataReceived += receive; child.ErrorDataReceived += receive;
                if (!child.Start()) throw new IOException("Java did not start");
                child.BeginOutputReadLine(); child.BeginErrorReadLine();
                if (!cli && !background) {
                    Stopwatch deadline = Stopwatch.StartNew();
                    IntPtr window = IntPtr.Zero;
                    while (!child.HasExited && deadline.ElapsedMilliseconds < 30000) {
                        child.Refresh(); window = child.MainWindowHandle;
                        if (window != IntPtr.Zero) break;
                        Thread.Sleep(100);
                    }
                    if (window != IntPtr.Zero) { SetForegroundWindow(window); Log("Injector window opened"); }
                    else if (!child.HasExited) {
                        Log("No injector window appeared within 30 seconds");
                        MessageBox.Show("Java started, but the injector has not opened a window. Startup details are in:\n" + logPath,
                            "UtilityClient startup", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    else {
                        child.WaitForExit();
                        throw new IOException("The injector exited before opening its window.\n" + output.ToString());
                    }
                }
                child.WaitForExit();
                if (child.ExitCode != 0) throw new IOException("Injector exited with code " + child.ExitCode + ".\n" + output.ToString());
                if (check && !output.ToString().Contains("UtilityClient injector")) throw new IOException("Injector startup check returned unexpected output");
                Log(check ? "Startup check passed" : "Injector closed normally");
                return 0;
            }
        } catch (Exception error) {
            if (logPath == null) logPath = Path.Combine(root, "launcher.log");
            Log("STARTUP FAILED: " + error);
            string message = error.Message + "\n\nStartup log: " + logPath;
            if (check || commandMode) Console.Error.WriteLine(message);
            else MessageBox.Show(message, "UtilityClient could not start", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
