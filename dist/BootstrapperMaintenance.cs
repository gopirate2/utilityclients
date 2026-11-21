using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;
using System.Web.Script.Serialization;

internal static partial class Bootstrapper {
    internal sealed class HealthItem {
        internal string Name, Status, Detail;
        internal HealthItem(string name, string status, string detail) { Name = name; Status = status; Detail = detail; }
    }
    internal sealed class HealthReport {
        internal readonly List<HealthItem> Items = new List<HealthItem>();
        internal bool Healthy { get { return Items.TrueForAll(delegate(HealthItem item) { return item.Status != "REPAIR"; }); } }
        internal string Text() {
            StringBuilder text = new StringBuilder(Healthy ? "Installation healthy\n" : "Installation needs attention\n");
            foreach (HealthItem item in Items) text.Append(item.Status).Append(" | ").Append(item.Name).Append(" | ").AppendLine(item.Detail);
            return text.ToString();
        }
    }
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 8 * 1024 * 1024 };
    private static string Full(string path) { return Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar); }
    private static bool Within(string path, string root) { return Full(path).StartsWith(Full(root) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase); }
    private static string FileHash(string file) { using (Stream stream = File.OpenRead(file)) return Hash(stream); }
    private static Dictionary<string, object> ObjectJson(string text) {
        Dictionary<string, object> result = Json.Deserialize<Dictionary<string, object>>(text);
        if (result == null) throw new InvalidDataException("Expected a JSON object");
        return result;
    }
    private static string TextField(Dictionary<string, object> item, string key) {
        object value;
        if (!item.TryGetValue(key, out value) || !(value is string)) throw new InvalidDataException("Missing " + key);
        return (string)value;
    }
    private static string SafeMember(string directory, string name) {
        if (String.IsNullOrEmpty(name) || name.Contains("\\") || name.Contains(":") || name.StartsWith("/")) throw new InvalidDataException("Invalid package member");
        foreach (string part in name.Split('/')) {
            if (part.Length == 0 || part == "." || part == ".." || part.EndsWith(".") || part.EndsWith(" ") || part.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
                throw new InvalidDataException("Invalid package member");
            string stem = part.Split('.')[0].ToUpperInvariant();
            if (stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL" || (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && stem[3] >= '1' && stem[3] <= '9'))
                throw new InvalidDataException("Reserved package member");
        }
        string path = Path.GetFullPath(Path.Combine(directory, name.Replace('/', Path.DirectorySeparatorChar)));
        if (!Within(path, directory)) throw new InvalidDataException("Package member escapes its root");
        return path;
    }
    private static void RequirePlainPath(string directory) {
        string path = Full(directory);
        if (String.Equals(path, Path.GetPathRoot(path).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase))
            throw new IOException("Select a dedicated installation folder");
        for (string current = path; current != null; current = Path.GetDirectoryName(current)) {
            if ((Directory.Exists(current) || File.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Installation path contains a junction or symbolic link: " + current);
        }
    }

    internal static void VerifyPackageManifest(string directory) {
        string manifest = Path.Combine(directory, "package.json");
        if (!File.Exists(manifest)) throw new InvalidDataException("Package integrity manifest is missing");
        Dictionary<string, object> metadata = ObjectJson(File.ReadAllText(manifest));
        RequireSchema(metadata); TextField(metadata, "version"); TextField(metadata, "build");
        Dictionary<string, object> files = metadata["files"] as Dictionary<string, object>;
        if (files == null || files.Count == 0) throw new InvalidDataException("Package has no file checksums");
        foreach (string required in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "runtime/bin/java.exe", "runtime/bin/javaw.exe" })
            if (!files.ContainsKey(required)) throw new InvalidDataException("Package missing checksum for " + required);
        HashSet<string> unique = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (KeyValuePair<string, object> item in files) {
            string file = SafeMember(directory, item.Key), expected = item.Value as string;
            if (!unique.Add(item.Key) || item.Key == "package.json") throw new InvalidDataException("Duplicate or self-referencing package checksum");
            if (!IsDigest(expected) || !File.Exists(file) || FileHash(file) != expected.ToLowerInvariant())
                throw new InvalidDataException("Package checksum mismatch: " + item.Key);
        }
        foreach (string file in Directory.GetFiles(directory, "*", SearchOption.AllDirectories)) {
            string name = file.Substring(directory.Length + 1).Replace('\\', '/');
            if (name != "package.json" && !files.ContainsKey(name)) throw new InvalidDataException("Package member has no checksum: " + name);
        }
    }

    private static string InstallationRecord(string version, string payloadHash, string installedSource) {
        Dictionary<string, object> package = ObjectJson(File.ReadAllText(Path.Combine(version, "package.json")));
        Dictionary<string, object> record = new Dictionary<string, object>();
        record["schema"] = 1; record["root"] = Full(Root); record["runtimeVersion"] = Full(version);
        record["payloadSha256"] = payloadHash; record["version"] = package["version"];
        record["build"] = package["build"]; record["release"] = SelectedUpdate == null ? "Embedded offline package" : SelectedUpdate.Label;
        if (SelectedUpdate != null) record["updateSha256"] = SelectedUpdate.Digest;
        record["files"] = package["files"]; record["installedUtc"] = DateTime.UtcNow.ToString("o");
        Dictionary<string, object> managed = new Dictionary<string, object>();
        foreach (string name in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "run.cmd", "java.path" }) managed[name] = FileHash(Path.Combine(installedSource, name));
        record["managedFiles"] = managed;
        return Json.Serialize(record);
    }

    private static Dictionary<string, object> ReadInstallationRecord() {
        Dictionary<string, object> record = ObjectJson(File.ReadAllText(Path.Combine(Root, "installation.json")));
        RequireSchema(record);
        if (!String.Equals(Full(TextField(record, "root")), Full(Root), StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Manifest belongs to another installation");
        string version = TextField(record, "runtimeVersion");
        if (!Within(version, Path.Combine(AppRoot, "versions"))) throw new InvalidDataException("Runtime path is outside the managed versions folder");
        RequirePlainPath(version);
        foreach (string key in new string[] { "files", "managedFiles" }) {
            Dictionary<string, object> files = record[key] as Dictionary<string, object>;
            if (files == null || files.Count == 0) throw new InvalidDataException("Missing " + key + " checksums");
            foreach (KeyValuePair<string, object> file in files) { SafeMember(version, file.Key); if (!IsDigest(file.Value as string)) throw new InvalidDataException("Invalid recorded checksum"); }
        }
        Dictionary<string, object> managed = (Dictionary<string, object>)record["managedFiles"];
        foreach (string required in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "run.cmd", "java.path" })
            if (!managed.ContainsKey(required)) throw new InvalidDataException("Missing managed checksum for " + required);
        Dictionary<string, object> package = (Dictionary<string, object>)record["files"];
        foreach (string required in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "runtime/bin/java.exe", "runtime/bin/javaw.exe" })
            if (!package.ContainsKey(required)) throw new InvalidDataException("Missing package checksum for " + required);
        if (!IsDigest(TextField(record, "payloadSha256"))) throw new InvalidDataException("Invalid recorded package checksum");
        return record;
    }

    internal static HealthReport CheckHealth() {
        HealthReport report = new HealthReport();
        try { RequirePlainPath(Root); RequirePlainPath(AppRoot); }
        catch (Exception error) { report.Items.Add(new HealthItem("Installation path", "REPAIR", error.Message)); return report; }
        Dictionary<string, object> files = null, managed = null; string version = null;
        try {
            Dictionary<string, object> record = ReadInstallationRecord();
            version = TextField(record, "runtimeVersion");
            files = record["files"] as Dictionary<string, object>;
            managed = record["managedFiles"] as Dictionary<string, object>;
            report.Items.Add(new HealthItem("Installed release", "OK", Convert.ToString(record["version"]) + " / " + Convert.ToString(record["release"])));
        } catch (Exception error) { report.Items.Add(new HealthItem("Installation record", "REPAIR", "Run Repair to restore verified metadata. " + error.Message)); }
        foreach (string name in new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "run.cmd", "java.path" }) {
            try {
                string file = Path.Combine(Root, name);
                if (!File.Exists(file)) throw new IOException("Missing file; run Repair");
                object expected;
                if (managed == null || !managed.TryGetValue(name, out expected)) throw new IOException("Recorded checksum missing; run Repair");
                if (FileHash(file) != Convert.ToString(expected).ToLowerInvariant()) throw new IOException("Recorded checksum differs; run Repair");
                report.Items.Add(new HealthItem(name, "OK", "Recorded installation checksum matched"));
            } catch (Exception error) { report.Items.Add(new HealthItem(name, "REPAIR", error.Message)); }
        }
        try { ValidateJars(Root); report.Items.Add(new HealthItem("JAR identities", "OK", "UtilityClient and injector entry points")); }
        catch (Exception error) { report.Items.Add(new HealthItem("JAR identities", "REPAIR", error.Message)); }
        try {
            string java = Full(File.ReadAllText(Path.Combine(Root, "java.path")).Trim());
            if (version == null || !String.Equals(java, Full(Path.Combine(version, "runtime", "bin", "java.exe")), StringComparison.OrdinalIgnoreCase)) throw new IOException("Configured Java does not match the managed runtime");
            RequirePlainPath(version);
            int checkedFiles = 0;
            if (files != null) foreach (KeyValuePair<string, object> item in files) if (item.Key.StartsWith("runtime/", StringComparison.Ordinal)) {
                string file = SafeMember(version, item.Key);
                if (!File.Exists(file) || FileHash(file) != Convert.ToString(item.Value).ToLowerInvariant()) throw new IOException("Runtime checksum differs: " + item.Key);
                checkedFiles++;
            }
            if (checkedFiles == 0) throw new IOException("Runtime checksums missing");
            foreach (string name in new string[] { "cheatclient.jar", "injector.jar" })
                if (managed == null || FileHash(Path.Combine(Root, name)) != Convert.ToString(managed[name]).ToLowerInvariant()) throw new IOException("Repair the " + name + " checksum before running verification");
            string modules = JavaOutput(java, "--list-modules");
            if (!modules.Contains("jdk.attach@25") || !modules.Contains("java.desktop@25")) throw new IOException("Java 25 attach/desktop modules missing");
            report.Items.Add(new HealthItem("Private Java", "OK", checkedFiles + " checksums and Java 25 modules matched"));
            string verified = JavaOutput(java, "-jar \"" + Path.Combine(Root, "injector.jar") + "\" --verify \"" + Path.Combine(Root, "cheatclient.jar") + "\" --sha256 " + Convert.ToString(managed["cheatclient.jar"]));
            report.Items.Add(new HealthItem("Client helper contracts", "OK", verified.Trim()));
        } catch (Exception error) { report.Items.Add(new HealthItem("Private Java", "REPAIR", error.Message)); }
        string[] folders = ShortcutFolders();
        foreach (string folder in folders) {
            string file = Path.Combine(folder, "UtilityClient.lnk");
            try {
                if (!File.Exists(file)) { report.Items.Add(new HealthItem("Shortcut", "INFO", "Not installed: " + file)); continue; }
                string[] link = ReadShortcutOnSta(file);
                bool owned = WindowsShortcuts.Owns(link, LauncherPath, Root);
                report.Items.Add(new HealthItem("Shortcut", owned ? "OK" : "INFO", owned ? "Selected installation target verified" : "Shortcut belongs to another installation: " + file));
            } catch (Exception error) { report.Items.Add(new HealthItem("Shortcut", "REPAIR", error.Message)); }
        }
        try {
            InjectorLaunchOptions options = LaunchOptionsFile.Read(Root);
            report.Items.Add(new HealthItem("Launch options", "OK", "Auto Inject " + (options.AutoInject ? "on" : "off") + "; scan " + options.ScanIntervalMs + "ms; startup wait " + options.WarmupSeconds + "s"));
        } catch (Exception error) { report.Items.Add(new HealthItem("Launch options", "REPAIR", "Open Launch Options and save valid settings. " + error.Message)); }
        return report;
    }

    private static string[] ShortcutFolders() {
        return ShortcutDirectory == null ? new string[] {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "UtilityClient"),
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            Environment.GetFolderPath(Environment.SpecialFolder.Startup)
        } : new string[] { ShortcutDirectory, Path.Combine(ShortcutDirectory, "startup") };
    }
    private static string[] ReadShortcutOnSta(string path) {
        string[] result = null; Exception failure = null;
        System.Threading.Thread thread = new System.Threading.Thread(delegate() { try { result = WindowsShortcuts.Read(path); } catch (Exception error) { failure = error; } });
        thread.SetApartmentState(System.Threading.ApartmentState.STA); thread.Start(); thread.Join();
        if (failure != null) throw failure; return result;
    }

    internal static string Uninstall(bool removeSettings) {
        RequirePlainPath(Root); RequirePlainPath(AppRoot);
        string marker = Path.Combine(Root, "installation.json");
        if (!File.Exists(marker)) throw new IOException("No managed installation record. Run Repair before uninstalling an older installation.");
        Dictionary<string, object> record = ReadInstallationRecord();
        if (InstalledProcessRunning(Root) || InstalledProcessRunning(TextField(record, "runtimeVersion"))) throw new IOException("The installed launcher or injector is running. Close it before uninstalling.");
        List<string> names = new List<string>(new string[] { "cheatclient.jar", "injector.jar", "UtilityClient.exe", "run.cmd", "java.path", "installation.json" });
        if (removeSettings) names.AddRange(new string[] { "utilityclient.json", "friends.json", "stealth.json", "capture.json", "injector-settings.json", "share.txt", "launcher.log", "utilityclient", "logs", "backups" });
        Directory.CreateDirectory(AppRoot);
        using (FileStream runtimeLock = new FileStream(Path.Combine(AppRoot, ".setup.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None))
        using (FileStream rootLock = new FileStream(Path.Combine(Root, ".setup.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
        foreach (string name in names) {
            string path = Path.Combine(Root, name);
            if (!File.Exists(path) && !Directory.Exists(path)) continue;
            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0 || Directory.Exists(path) && !PlainDirectory(path)) throw new IOException("Refusing linked uninstall path: " + path);
            if (Directory.Exists(path)) foreach (string file in Directory.GetFiles(path, "*", SearchOption.AllDirectories)) using (FileStream probe = new FileStream(file, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) { }
            else using (FileStream probe = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) { }
        }
        List<string> shortcuts = new List<string>();
        foreach (string folder in ShortcutFolders()) {
            string link = Path.Combine(folder, "UtilityClient.lnk");
            if (!File.Exists(link)) continue;
            string[] saved = ReadShortcutOnSta(link);
            if (WindowsShortcuts.Owns(saved, LauncherPath, Root)) shortcuts.Add(link);
        }
        string staging = Path.Combine(Root, ".uninstall-" + Guid.NewGuid().ToString("N"));
        if (!Within(staging, Root)) throw new IOException("Invalid uninstall staging path");
        Directory.CreateDirectory(staging); List<string> moved = new List<string>();
        List<string[]> external = new List<string[]>();
            try {
                foreach (string name in names) {
                    string from = Path.Combine(Root, name), to = Path.Combine(staging, name);
                    if (Directory.Exists(from)) { Directory.Move(from, to); moved.Add(name); }
                    else if (File.Exists(from)) { File.Move(from, to); moved.Add(name); }
                }
                List<string> ownedExternal = new List<string>(shortcuts);
                string roots = Path.Combine(AppRoot, "installations");
                if (Directory.Exists(roots)) foreach (string file in Directory.GetFiles(roots, "*.root")) {
                    if (String.Equals(Full(File.ReadAllText(file).Trim()), Full(Root), StringComparison.OrdinalIgnoreCase)) ownedExternal.Add(file);
                }
                foreach (string path in ownedExternal) {
                    RequirePlainPath(Path.GetDirectoryName(path));
                    if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked registration or shortcut");
                    string backup = Path.Combine(staging, "external-" + external.Count);
                    File.Copy(path, backup); external.Add(new string[] { backup, path }); File.Delete(path);
                }
            } catch (Exception error) {
                moved.Reverse();
                try {
                    foreach (string[] pair in external) if (!File.Exists(pair[1])) File.Copy(pair[0], pair[1]);
                    foreach (string name in moved) { string from = Path.Combine(staging, name), to = Path.Combine(Root, name); if (Directory.Exists(from)) Directory.Move(from, to); else File.Move(from, to); }
                    if (PlainDirectory(staging)) Directory.Delete(staging, true);
                }
                catch (Exception rollback) { throw new IOException("Uninstall rollback incomplete; files retained at " + staging, rollback); }
                throw new IOException("Uninstall stopped without removing the installation: " + error.Message, error);
            }
            if (!PlainDirectory(staging)) throw new IOException("Uninstall files retained at " + staging);
            Directory.Delete(staging, true);
            RetireUnusedVersions();
        }
        return removeSettings ? "Managed program files and settings removed. Unrelated files, other installations and the reusable setup were retained."
                : "Managed program files removed. Settings/profiles, unrelated files and the reusable setup were retained.";
    }

    private static bool InstalledProcessRunning(string directory) {
        foreach (Process process in Process.GetProcesses()) using (process) {
            try { if (process.MainModule != null && Within(process.MainModule.FileName, directory)) return true; }
            catch (System.ComponentModel.Win32Exception) { } catch (InvalidOperationException) { }
        }
        return false;
    }

    internal static string RetainedSetupPath { get { return Path.Combine(AppRoot, "bootstrapper", "UtilityClient-Setup.exe"); } }
    private static void RetainSetup() {
        string source = Assembly.GetExecutingAssembly().Location;
        if (String.Equals(Full(source), Full(RetainedSetupPath), StringComparison.OrdinalIgnoreCase)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(RetainedSetupPath));
        RequirePlainPath(Path.GetDirectoryName(RetainedSetupPath));
        string pending = RetainedSetupPath + "." + Guid.NewGuid().ToString("N") + ".new";
        File.Copy(source, pending);
        try { if (File.Exists(RetainedSetupPath)) File.Replace(pending, RetainedSetupPath, null); else File.Move(pending, RetainedSetupPath); }
        finally { if (File.Exists(pending)) File.Delete(pending); }
    }

    internal static void ExportLinux(string destination) {
        if (String.IsNullOrWhiteSpace(destination)) throw new ArgumentException("Choose an export filename");
        string full = Path.GetFullPath(destination);
        RequirePlainPath(Path.GetDirectoryName(full));
        if (!full.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Linux export filename must end in .zip");
        if (Within(full, Root) || Within(full, AppRoot)) throw new IOException("Export outside managed installation folders");
        if (File.Exists(full) && (File.GetAttributes(full) & FileAttributes.ReparsePoint) != 0) throw new IOException("Export target is linked");
        string hash; using (Stream stream = OpenLinux()) { if (stream == null) throw new IOException("Linux export resource is unavailable"); hash = Hash(stream); }
        if (hash != LinuxHash()) throw new InvalidDataException("Linux bundle checksum mismatch");
        Directory.CreateDirectory(Path.GetDirectoryName(full));
        string pending = full + "." + Guid.NewGuid().ToString("N") + ".new";
        try {
            using (Stream input = OpenLinux()) using (FileStream output = new FileStream(pending, FileMode.CreateNew, FileAccess.Write)) { input.CopyTo(output); output.Flush(true); }
            if (FileHash(pending) != hash) throw new InvalidDataException("Linux bundle changed while exporting");
            VerifyLinuxBundle(pending);
            AtomicPair(pending, full, hash + "  " + Path.GetFileName(full) + "\n", full + ".sha256");
        } finally { if (File.Exists(pending)) File.Delete(pending); }
    }

    internal static void VerifyLinuxBundle(string file) {
        using (ZipArchive zip = ZipFile.OpenRead(file)) {
            ValidateZipMembers(zip, MaxUpdateBytes);
            foreach (string name in new string[] { "cheatclient.jar", "injector.jar", "install.sh", "install-artifacts.py", "java-runtime.py", "README.md", "SHA256SUMS" })
                if (zip.GetEntry(name) == null) throw new InvalidDataException("Linux export missing " + name);
            Dictionary<string, string> hashes = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (string line in Entry(zip, "SHA256SUMS").Replace("\r", "").Split('\n')) {
                if (line.Length == 0) continue;
                if (line.Length < 66 || !IsDigest(line.Substring(0, 64))) throw new InvalidDataException("Invalid Linux checksum manifest");
                string name = line.Substring(64).Trim().TrimStart('*'); SafeMember(Path.GetTempPath(), name);
                if (name == "SHA256SUMS" || hashes.ContainsKey(name)) throw new InvalidDataException("Duplicate Linux checksum");
                hashes.Add(name, line.Substring(0, 64).ToLowerInvariant());
            }
            int checkedMembers = 0;
            foreach (ZipArchiveEntry entry in zip.Entries) {
                if (entry.Name.Length == 0 || entry.FullName == "SHA256SUMS") continue;
                checkedMembers++;
                if (entry.FullName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) || entry.FullName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Linux export contains Windows binaries");
                string expected;
                if (!hashes.TryGetValue(entry.FullName, out expected)) throw new InvalidDataException("Linux member checksum missing: " + entry.FullName);
                using (Stream input = entry.Open()) if (Hash(input) != expected) throw new InvalidDataException("Linux member checksum mismatch: " + entry.FullName);
            }
            if (hashes.Count != checkedMembers) throw new InvalidDataException("Linux checksum manifest has missing or extra members");
            using (Stream input = zip.GetEntry("cheatclient.jar").Open()) using (MemoryStream bytes = new MemoryStream()) {
                input.CopyTo(bytes); bytes.Position = 0;
                using (ZipArchive client = new ZipArchive(bytes, ZipArchiveMode.Read)) {
                    if (client.GetEntry("assets/x/r.bin") != null) throw new InvalidDataException("Linux client contains the Windows native DLL");
                    Dictionary<string, object> metadata = ObjectJson(Entry(client, "fabric.mod.json"));
                    string id = TextField(metadata, "id");
                    if (id != "utilityclient" && id != "cheatclient") throw new InvalidDataException("Wrong Linux client identity");
                }
            }
        }
    }
    private static void AtomicPair(string pending, string target, string checksum, string checksumTarget) {
        string suffix = "." + Guid.NewGuid().ToString("N"), checksumPending = checksumTarget + suffix, backup = target + suffix + ".bak", checksumBackup = checksumTarget + suffix + ".bak";
        bool hadTarget = File.Exists(target), hadChecksum = File.Exists(checksumTarget), installed = false, checksumInstalled = false, recovered = true;
        try {
            if (hadTarget && (File.GetAttributes(target) & FileAttributes.ReparsePoint) != 0 || hadChecksum && (File.GetAttributes(checksumTarget) & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked output path");
            File.WriteAllText(checksumPending, checksum, new UTF8Encoding(false));
            if (hadTarget) File.Copy(target, backup); if (hadChecksum) File.Copy(checksumTarget, checksumBackup);
            if (hadTarget) File.Replace(pending, target, null); else File.Move(pending, target); installed = true;
            if (hadChecksum) File.Replace(checksumPending, checksumTarget, null); else File.Move(checksumPending, checksumTarget); checksumInstalled = true;
        } catch {
            try {
                if (installed) { if (hadTarget) File.Copy(backup, target, true); else File.Delete(target); }
                if (checksumInstalled) { if (hadChecksum) File.Copy(checksumBackup, checksumTarget, true); else File.Delete(checksumTarget); }
            } catch (Exception rollback) { recovered = false; throw new IOException("Output rollback failed; backups retained at " + backup + " and " + checksumBackup, rollback); }
            throw;
        } finally {
            foreach (string file in recovered ? new string[] { checksumPending, backup, checksumBackup } : new string[] { checksumPending }) if (File.Exists(file)) File.Delete(file);
        }
    }
}
