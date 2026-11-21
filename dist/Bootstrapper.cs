using System;
using System.IO;
using System.IO.Compression;
using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using System.Drawing;
using System.ComponentModel;

// An offline, per-user setup. The build embeds both validated JARs and a Java 25 runtime.
internal static partial class Bootstrapper {
    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern void GetNativeSystemInfo(IntPtr info);

    private static bool NativeX64() {
        IntPtr info = System.Runtime.InteropServices.Marshal.AllocHGlobal(64);
        try { GetNativeSystemInfo(info); return System.Runtime.InteropServices.Marshal.ReadInt16(info) == 9; }
        finally { System.Runtime.InteropServices.Marshal.FreeHGlobal(info); }
    }
    internal static string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "depends");
    internal static string AppRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UtilityClient");
    internal static bool Shortcuts = true;
    internal static string ShortcutDirectory;
    internal static string LastShortcutWarning = "";
    internal static string CurrentStage = "Check system";
    internal static bool AutoUpdates = true;

    [STAThread]
    private static int Main(string[] args) {
        bool quiet = false;
        string operation = "install", exportPath = null, localPackage = null, packageHash = null, updateFeed = null;
        bool removeSettings = false, confirmed = false;
        try {
            for (int i = 0; i < args.Length; i++) {
                switch (args[i]) {
                    case "--quiet": quiet = true; break;
                    case "--offline": AutoUpdates = false; break;
                    case "--root": Root = Path.GetFullPath(args[++i]); break;
                    case "--app-dir": AppRoot = Path.GetFullPath(args[++i]); break;
                    case "--no-shortcuts": Shortcuts = false; break;
                    case "--shortcut-dir": ShortcutDirectory = Path.GetFullPath(args[++i]); break;
                    case "--health": operation = "health"; quiet = true; break;
                    case "--export-linux": operation = "export"; exportPath = Path.GetFullPath(args[++i]); quiet = true; break;
                    case "--uninstall": operation = "uninstall"; quiet = true; break;
                    case "--remove-settings": removeSettings = true; break;
                    case "--confirm": confirmed = true; break;
                    case "--check-updates": operation = "check-updates"; quiet = true; break;
                    case "--update-package": localPackage = Path.GetFullPath(args[++i]); break;
                    case "--package-sha256": packageHash = args[++i]; break;
                    case "--update-feed-url": updateFeed = args[++i]; break;
                    default: throw new ArgumentException("Unknown option: " + args[i]);
                }
            }
            if (removeSettings && operation != "uninstall") throw new ArgumentException("--remove-settings requires --uninstall");
            if (packageHash != null && localPackage == null) throw new ArgumentException("--package-sha256 requires --update-package");
            if (updateFeed != null) ConfigureUpdateFeed(updateFeed);
            if (localPackage != null) { AutoUpdates = false; SelectUpdatePackage(localPackage, packageHash, "Local update (" + (packageHash == null ? "structure verified" : "supplied checksum verified") + ")"); }
            else if (operation == "install" || operation == "export") RestoreSelectedUpdate();
            if (quiet) {
                if (operation == "health") {
                    HealthReport report = CheckHealth(); Console.WriteLine(report.Text());
                    return report.Healthy ? 0 : 2;
                }
                if (operation == "export") { ExportLinux(exportPath); Console.WriteLine("Linux bundle exported: " + exportPath); return 0; }
                if (operation == "uninstall") {
                    if (!confirmed) throw new ArgumentException("Uninstall requires --confirm; add --remove-settings only to delete managed settings/profiles.");
                    Console.WriteLine(Uninstall(removeSettings)); return 0;
                }
                if (operation == "check-updates") { UpdateRelease release = FindUpdate(); Console.WriteLine(release.Description()); return 0; }
                Console.WriteLine(PrepareLatestUpdate());
                Install(delegate(int step, string message) { Console.WriteLine("[" + step + "/6] " + message); });
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (SetupForm form = new SetupForm()) Application.Run(form);
            return 0;
        } catch (Exception error) {
            string message = CurrentStage + " failed: " + error.Message;
            if (quiet) Console.Error.WriteLine(message);
            else MessageBox.Show(message, "UtilityClient setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        } finally { CleanupUpdate(); }
    }

    private static string Hash(Stream stream) {
        using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }

    private static string Entry(ZipArchive jar, string name) {
        ZipArchiveEntry item = jar.GetEntry(name);
        if (item == null || item.Length > 8 * 1024 * 1024) throw new InvalidDataException("Missing or oversized " + name);
        using (StreamReader reader = new StreamReader(item.Open(), Encoding.UTF8)) return reader.ReadToEnd();
    }

    private static void ValidateJars(string directory) {
        using (ZipArchive jar = ZipFile.OpenRead(Path.Combine(directory, "cheatclient.jar"))) {
            Dictionary<string, object> metadata = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(Entry(jar, "fabric.mod.json"));
            object id;
            if (!metadata.TryGetValue("id", out id) || (Convert.ToString(id) != "cheatclient" && Convert.ToString(id) != "utilityclient"))
                throw new InvalidDataException("Wrong client identity");
        }
        using (ZipArchive jar = ZipFile.OpenRead(Path.Combine(directory, "injector.jar"))) {
            string manifest = Entry(jar, "META-INF/MANIFEST.MF").Replace("\r\n ", "").Replace("\n ", "");
            if (!manifest.Contains("Main-Class: com.baggute.cheatclient.injector.InjectorMain") ||
                !manifest.Contains("Agent-Class: com.baggute.cheatclient.injector.agent.ClientAgent"))
                throw new InvalidDataException("Wrong injector identity");
        }
    }

    private static string JavaOutput(string java, string arguments) {
        using (Process process = new Process()) {
            process.StartInfo = new ProcessStartInfo(java, arguments) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
            };
            process.Start();
            System.Threading.Tasks.Task<string> stdout = process.StandardOutput.ReadToEndAsync();
            System.Threading.Tasks.Task<string> stderr = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(20000)) { process.Kill(); throw new IOException("Private Java runtime did not respond"); }
            string output = stdout.Result + stderr.Result;
            if (process.ExitCode != 0) throw new IOException("Private Java runtime failed: " + output);
            return output;
        }
    }

    internal static string LauncherPath { get { return Path.Combine(Root, "UtilityClient.exe"); } }
    internal sealed class PackageProgress {
        internal string FileName;
        internal long FileBytes, FileTotal, Bytes, Total;
        internal int CompletedFiles, FileCount;
    }
    internal static void Install(Action<int, string> progress, Action<PackageProgress> fileProgress = null) {
        LastShortcutWarning = "";
        Action<int, string> stage = delegate(int number, string message) { CurrentStage = message; progress(number, message); };
        stage(1, "Check system and installation folders");
        if (!Environment.Is64BitOperatingSystem) throw new IOException("Windows 64-bit is required");
        if (!NativeX64())
            throw new IOException("This build contains an x64 runtime; use an x64 Windows PC");
        RequirePlainPath(Root); RequirePlainPath(AppRoot);
        if (String.Equals(Full(Root), Full(AppRoot), StringComparison.OrdinalIgnoreCase) || Within(Root, AppRoot) || Within(AppRoot, Root)) throw new IOException("Choose separate settings and private runtime folders");
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(AppRoot);
        // Separate setups cannot update the shared files simultaneously.
        using (FileStream runtimeLock = new FileStream(Path.Combine(AppRoot, ".setup.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None))
        using (FileStream installLock = new FileStream(Path.Combine(Root, ".setup.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
            string stageDir = Path.Combine(AppRoot, ".setup-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(stageDir);
            string createdVersion = null;
            try {
                stage(2, "Verify selected package");
                string expected = PayloadHash();
                using (Stream payload = OpenPayload()) {
                    if (payload == null || Hash(payload) != expected) throw new InvalidDataException("Package checksum mismatch; obtain a fresh setup");
                }
                stage(3, "Unpack private Java 25 runtime");
                using (Stream payload = OpenPayload())
                using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read)) {
                    ValidateZipMembers(archive, 2L * 1024 * 1024 * 1024);
                    string boundary = Path.GetFullPath(stageDir) + Path.DirectorySeparatorChar;
                    long totalBytes = 0, writtenBytes = 0;
                    int fileCount = 0, completedFiles = 0;
                    foreach (ZipArchiveEntry entry in archive.Entries) if (entry.Name.Length != 0) { totalBytes += entry.Length; fileCount++; }
                    byte[] buffer = new byte[65536];
                    Stopwatch updates = Stopwatch.StartNew();
                    foreach (ZipArchiveEntry entry in archive.Entries) {
                        string path = Path.GetFullPath(Path.Combine(stageDir, entry.FullName));
                        if (!path.StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Invalid package path");
                        if (entry.Name.Length == 0) { Directory.CreateDirectory(path); continue; }
                        Directory.CreateDirectory(Path.GetDirectoryName(path));
                        long fileBytes = 0;
                        using (Stream input = entry.Open())
                        using (FileStream output = new FileStream(path, FileMode.CreateNew, FileAccess.Write)) {
                            int count;
                            while ((count = input.Read(buffer, 0, buffer.Length)) > 0) {
                                output.Write(buffer, 0, count);
                                fileBytes += count; writtenBytes += count;
                                if (fileProgress != null && updates.ElapsedMilliseconds >= 50) {
                                    fileProgress(new PackageProgress { FileName = entry.FullName, FileBytes = fileBytes, FileTotal = entry.Length,
                                        Bytes = writtenBytes, Total = totalBytes, CompletedFiles = completedFiles, FileCount = fileCount });
                                    updates.Restart();
                                }
                            }
                        }
                        completedFiles++;
                        if (fileProgress != null) fileProgress(new PackageProgress { FileName = entry.FullName, FileBytes = fileBytes, FileTotal = entry.Length,
                            Bytes = writtenBytes, Total = totalBytes, CompletedFiles = completedFiles, FileCount = fileCount });
                    }
                }
                ValidateJars(stageDir);
                VerifyPackageManifest(stageDir);
                string stagedJava = Path.Combine(stageDir, "runtime", "bin", "java.exe");
                string modules = JavaOutput(stagedJava, "--list-modules");
                if (!modules.Contains("jdk.attach@25") || !modules.Contains("java.desktop@25")) throw new IOException("Java 25 attach/desktop modules missing");
                Dictionary<string, object> stagedManifest = ObjectJson(File.ReadAllText(Path.Combine(stageDir, "package.json")));
                string clientDigest = TextField((Dictionary<string, object>)stagedManifest["files"], "cheatclient.jar");
                JavaOutput(stagedJava, "-jar \"" + Path.Combine(stageDir, "injector.jar") + "\" --verify \"" + Path.Combine(stageDir, "cheatclient.jar") + "\" --sha256 " + clientDigest);
                string version = FindMatchingVersion(stageDir, expected);
                if (version == null) {
                    version = Path.Combine(AppRoot, "versions", expected);
                    if (Directory.Exists(version)) version += "-" + Guid.NewGuid().ToString("N").Substring(0, 8);
                    Directory.CreateDirectory(Path.GetDirectoryName(version));
                    Directory.Move(stageDir, version);
                    createdVersion = version;
                }
                stage(4, "Install client and injector");
                string command = "@echo off\r\nchcp 65001 >nul\r\n\"%~dp0UtilityClient.exe\" --root \"%~dp0.\" %*\r\nexit /b %errorlevel%\r\n";
                // Keep a reused version immutable while its runtime may be running.
                string installSource = Path.Combine(AppRoot, ".files-" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(installSource);
                try {
                    foreach (string name in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe" })
                        File.Copy(Path.Combine(version, name), Path.Combine(installSource, name));
                    File.WriteAllText(Path.Combine(installSource, "run.cmd"), command, new UTF8Encoding(false));
                    File.WriteAllText(Path.Combine(installSource, "java.path"), Path.Combine(version, "runtime", "bin", "java.exe"), new UTF8Encoding(false));
                    File.WriteAllText(Path.Combine(installSource, "installation.json"), InstallationRecord(version, expected, installSource), new UTF8Encoding(false));
                // Replace the complete file set with rollback if any member is locked.
                    ReplaceFiles(installSource, new string[] { "cheatclient.jar", "injector.jar", "run.cmd", "java.path", "UtilityClient.exe", "installation.json" });
                } finally { DeletePlainDirectory(installSource); }
                stage(5, "Create launchers and shortcuts");
                string warning = "";
                RegisterRoot();
                if (Shortcuts) {
                    try { CreateShortcuts(); }
                    catch (Exception error) { warning = " Shortcuts failed: " + error.Message + ". Open " + LauncherPath; LastShortcutWarning = warning.Trim(); }
                }
                JavaOutput(LauncherPath, "--root \"" + Root + "\" --check");
                try { RetainSetup(); PersistSelectedUpdate(); }
                catch (Exception error) { LastShortcutWarning += " Reusable setup/cache needs attention: " + error.Message + ". Keep this setup file for future updates."; warning += LastShortcutWarning; }
                RetireUnusedVersions();
                stage(6, "Installed in " + Root + "." + warning);
            } finally {
                DeletePlainDirectory(stageDir);
                // A rollback leaves the new version unreferenced. A committed
                // java.path retains it even when a later launcher check fails.
                if (createdVersion != null) RemoveUnusedVersion(createdVersion);
            }
        }
    }

    private static bool PlainDirectory(string directory) {
        if (!Directory.Exists(directory) || (File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0) return false;
        foreach (string path in Directory.GetFileSystemEntries(directory)) {
            FileAttributes attributes = File.GetAttributes(path);
            if ((attributes & FileAttributes.ReparsePoint) != 0) return false;
            if ((attributes & FileAttributes.Directory) != 0 && !PlainDirectory(path)) return false;
        }
        return true;
    }

    private static void DeletePlainDirectory(string directory) {
        string absolute = Path.GetFullPath(directory);
        string boundary = Path.GetFullPath(AppRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!absolute.StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) return;
        try { if (PlainDirectory(absolute)) Directory.Delete(absolute, true); }
        catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private static string FindMatchingVersion(string staged, string expected) {
        string versions = Path.Combine(AppRoot, "versions");
        if (!Directory.Exists(versions) || (File.GetAttributes(versions) & FileAttributes.ReparsePoint) != 0) return null;
        foreach (string directory in Directory.GetDirectories(versions)) {
            if (!Path.GetFileName(directory).StartsWith(expected.Substring(0, 16), StringComparison.OrdinalIgnoreCase)) continue;
            try {
                if (!PlainDirectory(directory)) continue;
                bool same = true;
                foreach (string source in Directory.GetFiles(staged, "*", SearchOption.AllDirectories)) {
                    string target = Path.Combine(directory, source.Substring(staged.Length + 1));
                    if (!File.Exists(target) || new FileInfo(source).Length != new FileInfo(target).Length) { same = false; break; }
                    using (Stream a = File.OpenRead(source)) using (Stream b = File.OpenRead(target))
                        if (Hash(a) != Hash(b)) { same = false; break; }
                }
                if (same) return directory;
            } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
        return null;
    }

    private static void RegisterRoot() {
        string roots = Path.Combine(AppRoot, "installations");
        Directory.CreateDirectory(roots);
        string key;
        using (Stream bytes = new MemoryStream(Encoding.UTF8.GetBytes(Path.GetFullPath(Root).ToUpperInvariant()))) key = Hash(bytes);
        string target = Path.Combine(roots, key + ".root");
        string temporary = target + "." + Guid.NewGuid().ToString("N");
        try {
            File.WriteAllText(temporary, Path.GetFullPath(Root), new UTF8Encoding(false));
            if (File.Exists(target)) File.Replace(temporary, target, null); else File.Move(temporary, target);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private static bool Referenced(string version) {
        List<string> roots = new List<string>(); roots.Add(Root);
        string registered = Path.Combine(AppRoot, "installations");
        if (Directory.Exists(registered)) {
            foreach (string file in Directory.GetFiles(registered, "*.root")) {
                try { roots.Add(File.ReadAllText(file, Encoding.UTF8).Trim()); }
                catch (IOException) { return true; } catch (UnauthorizedAccessException) { return true; }
            }
        }
        string boundary = Path.GetFullPath(version).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        foreach (string root in roots) {
            if (root.Length == 0) return true;
            try {
                string marker = Path.Combine(root, "java.path");
                if (File.Exists(marker) && Path.GetFullPath(File.ReadAllText(marker, Encoding.UTF8).Trim())
                        .StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) return true;
            } catch (IOException) { return true; } catch (UnauthorizedAccessException) { return true; }
              catch (ArgumentException) { return true; }
        }
        return false;
    }

    private static bool Running(string version) {
        string boundary = Path.GetFullPath(version).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        foreach (Process process in Process.GetProcesses()) {
            using (process) {
                try {
                    if (process.MainModule != null && process.MainModule.FileName.StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) return true;
                } catch (Exception) {
                    // An inaccessible Java process could be using this runtime.
                    try { if (process.ProcessName.Equals("java", StringComparison.OrdinalIgnoreCase)
                            || process.ProcessName.Equals("javaw", StringComparison.OrdinalIgnoreCase)) return true; }
                    catch (Exception) { }
                }
            }
        }
        return false;
    }

    private static void RemoveUnusedVersion(string version) {
        string boundary = Path.GetFullPath(Path.Combine(AppRoot, "versions")).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!Path.GetFullPath(version).StartsWith(boundary, StringComparison.OrdinalIgnoreCase)) return;
        try {
            if (!PlainDirectory(version) || Referenced(version) || Running(version)) return;
            // Verify all members are unlocked before deleting any of them.
            foreach (string file in Directory.GetFiles(version, "*", SearchOption.AllDirectories))
                using (FileStream probe = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.None)) { }
            DeletePlainDirectory(version);
        } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private static void RetireUnusedVersions() {
        string versions = Path.Combine(AppRoot, "versions");
        if (!Directory.Exists(versions) || (File.GetAttributes(versions) & FileAttributes.ReparsePoint) != 0) return;
        foreach (string directory in Directory.GetDirectories(versions)) RemoveUnusedVersion(directory);
    }

    private static void ReplaceFiles(string source, string[] names) {
        string backup = Path.Combine(Root, ".backup-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(backup);
        List<string> changed = new List<string>();
        bool cleanup = true;
        try {
            foreach (string name in names) {
                string destination = Path.Combine(Root, name);
                string pending = Path.Combine(backup, name + ".new");
                File.Copy(Path.Combine(source, name), pending);
                if (File.Exists(destination)) File.Replace(pending, destination, Path.Combine(backup, name));
                else File.Move(pending, destination);
                changed.Add(name);
            }
        } catch (Exception installError) {
            changed.Reverse();
            try {
                foreach (string name in changed) {
                    string original = Path.Combine(backup, name);
                    string destination = Path.Combine(Root, name);
                    if (File.Exists(original)) File.Copy(original, destination, true);
                    else File.Delete(destination);
                }
            } catch (Exception rollbackError) {
                cleanup = false;
                throw new IOException(installError.Message + "; rollback incomplete; backups retained at " + backup, rollbackError);
            }
            throw;
        } finally { if (cleanup) Directory.Delete(backup, true); }
    }

    private static void CreateShortcuts() {
        Exception failure = null;
        // Shell shortcut creation runs in an STA, including installs performed
        // by the WinForms BackgroundWorker. Reload saved targets to verify them.
        System.Threading.Thread thread = new System.Threading.Thread(delegate() {
            try { CreateShortcutsOnSta(); }
            catch (Exception error) { failure = error; }
        });
        thread.SetApartmentState(System.Threading.ApartmentState.STA);
        thread.Start(); thread.Join();
        if (failure != null) throw new IOException("Shortcut creation failed: " + failure.GetBaseException().Message, failure);
    }

    private static void CreateShortcutsOnSta() {
        string target = Path.GetFullPath(LauncherPath);
        if (!File.Exists(target)) throw new IOException("Installed launcher is missing: " + target);
        string menu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "UtilityClient");
        string[] folders = ShortcutDirectory == null
                ? new string[] { menu, Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory) }
                : new string[] { ShortcutDirectory };
        foreach (string folder in folders) WindowsShortcuts.Create(target, "--root \"" + Root + "\"", folder, Root);
    }

    internal static void UserShortcut(string kind, bool create) {
        if (!File.Exists(LauncherPath)) throw new IOException("Install UtilityClient before creating shortcuts");
        string folder;
        if (kind == "desktop") folder = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        else if (kind == "start-menu") folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "UtilityClient");
        else if (kind == "startup") folder = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
        else throw new IOException("Unknown shortcut kind");
        if (ShortcutDirectory != null) folder = kind == "startup" ? Path.Combine(ShortcutDirectory, "startup") : ShortcutDirectory;
        if (create) WindowsShortcuts.Create(LauncherPath, "--root \"" + Root + "\" --gui" + (kind == "startup" ? " --startup" : ""), folder, Root);
        else WindowsShortcuts.RemoveOwned(LauncherPath, Root, folder);
    }

    internal static void Launch() {
        Process.Start(new ProcessStartInfo(LauncherPath, "--root \"" + Root + "\"") { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Root });
    }
}

