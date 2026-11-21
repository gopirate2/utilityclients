using System;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Text;

internal static partial class Bootstrapper {
    internal const string ReleaseRepository = "gopirate2/utilityclients";
    private const long MaxUpdateBytes = 512L * 1024 * 1024;
    internal sealed class UpdateRelease {
        internal string Label, Url, Digest, ChecksumUrl, Published;
        internal bool Installed;
        internal bool Available { get { return !String.IsNullOrEmpty(Url); } }
        internal string Description() {
            return Installed ? "You're up to date.\nInstalled: " + InstalledVersionLabel() + "\n" + Label
                : Available ? "Update available: " + Label + " (" + Published + ")\nInstalled: " + InstalledVersionLabel() + "\n" + Url + "\nVerified before installation; settings are preserved."
                : "No compatible stable update package is published in " + ReleaseRepository + ". The offline package remains available.";
        }
    }
    internal sealed class UpdatePackage {
        internal string Directory, Source, Digest, Label, PayloadDigest, LinuxDigest;
    }
    internal static UpdatePackage SelectedUpdate;
    internal static Func<UpdateRelease> DiscoverUpdate = FindUpdate;
    internal static Action<UpdateRelease, Action<long, long>> FetchUpdate = DownloadUpdate;
    internal static string PrepareLatestUpdate() {
        if (!AutoUpdates) return "Using the explicitly selected offline/local package.";
        CurrentStage = "Check latest client release";
        try {
            UpdateRelease release = DiscoverUpdate();
            if (!release.Available) return "No verified stable release is published; using " + PackageLabel + ".";
            if (release.Installed || IsReleaseInstalled(release)) return "You're up to date: " + InstalledVersionLabel();
            if (SelectedUpdate != null && IsDigest(release.Digest) && SelectedUpdate.Digest == release.Digest.ToLowerInvariant())
                return "Latest verified release is already cached: " + PackageLabel;
            FetchUpdate(release, null);
            return "Selected latest verified release: " + PackageLabel;
        } catch (WebException error) {
            return "Update service unavailable (" + error.Status + "); using " + PackageLabel + ". Check Updates can retry.";
        }
    }
    internal static string PackageLabel { get { return SelectedUpdate == null ? "Embedded offline package" : SelectedUpdate.Label; } }
    private static string InstalledVersionLabel() {
        if (!File.Exists(Path.Combine(Root, "installation.json"))) return "Not installed";
        Dictionary<string, object> record = ReadInstallationRecord();
        return TextField(record, "version") + " / " + TextField(record, "build");
    }
    internal static bool IsReleaseInstalled(UpdateRelease release) {
        if (release == null || !release.Available || !IsDigest(release.Digest)
                || !File.Exists(Path.Combine(Root, "installation.json"))) return false;
        Dictionary<string, object> record = ReadInstallationRecord();
        object installed;
        if (record.TryGetValue("updateSha256", out installed) && IsDigest(installed as string)
                && String.Equals((string)installed, release.Digest, StringComparison.OrdinalIgnoreCase)) return true;
        // Older installation records lack the outer update digest. Compare the
        // cached verified payload with the active installation, never cache alone.
        return SelectedUpdate != null && String.Equals(SelectedUpdate.Digest, release.Digest, StringComparison.OrdinalIgnoreCase)
            && String.Equals(TextField(record, "payloadSha256"), SelectedUpdate.PayloadDigest, StringComparison.OrdinalIgnoreCase);
    }
    internal static bool IsDigest(string value) {
        if (value == null || value.Length != 64) return false;
        foreach (char c in value) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        return true;
    }
    private static string ResourceDigest(string name) {
        using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)) {
            if (input == null) throw new InvalidDataException("Missing setup resource: " + name);
            using (StreamReader reader = new StreamReader(input)) {
                string hash = reader.ReadToEnd().Trim();
                if (!IsDigest(hash)) throw new InvalidDataException("Invalid setup resource checksum: " + name);
                return hash.ToLowerInvariant();
            }
        }
    }
    internal static Stream OpenPayload() { return SelectedUpdate == null ? Assembly.GetExecutingAssembly().GetManifestResourceStream("Payload.zip") : File.OpenRead(Path.Combine(SelectedUpdate.Directory, "Payload.zip")); }
    internal static string PayloadHash() { return SelectedUpdate == null ? ResourceDigest("Payload.sha256") : SelectedUpdate.PayloadDigest; }
    private static Stream OpenLinux() { return SelectedUpdate == null ? Assembly.GetExecutingAssembly().GetManifestResourceStream("LinuxBundle.zip") : File.OpenRead(Path.Combine(SelectedUpdate.Directory, "LinuxBundle.zip")); }
    private static string LinuxHash() { return SelectedUpdate == null ? ResourceDigest("LinuxBundle.sha256") : SelectedUpdate.LinuxDigest; }

    private static void RequireSchema(Dictionary<string, object> metadata) {
        object schema;
        if (!metadata.TryGetValue("schema", out schema) || !(schema is int) || (int)schema != 1) throw new InvalidDataException("Unsupported package format");
    }
    private static void ValidateZipMembers(ZipArchive archive, long limit) {
        HashSet<string> names = new HashSet<string>(StringComparer.OrdinalIgnoreCase); long total = 0;
        if (archive.Entries.Count > 25000) throw new InvalidDataException("Too many package members");
        foreach (ZipArchiveEntry entry in archive.Entries) {
            string name = entry.FullName.TrimEnd('/');
            SafeMember(Path.GetTempPath(), name);
            if (!names.Add(name)) throw new InvalidDataException("Duplicate package member: " + name);
            int kind = (entry.ExternalAttributes >> 16) & 0xf000;
            if (kind != 0 && kind != 0x8000 && kind != 0x4000) throw new InvalidDataException("Linked or special package member: " + name);
            if (entry.Length < 0 || entry.Length > limit || total > limit - entry.Length) throw new InvalidDataException("Package expansion limit exceeded");
            total += entry.Length;
        }
    }
    internal static void SelectUpdatePackage(string source, string expected, string label) {
        source = Path.GetFullPath(source);
        if (!File.Exists(source) || new FileInfo(source).Length > MaxUpdateBytes) throw new IOException("Update package is missing or exceeds 512 MiB");
        if (expected != null && !IsDigest(expected)) throw new ArgumentException("Package SHA-256 must contain 64 hexadecimal digits");
        RequirePlainPath(AppRoot); Directory.CreateDirectory(AppRoot);
        string directory = Path.Combine(AppRoot, ".update-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try {
            string copy = Path.Combine(directory, "UtilityClient-Update.zip"); File.Copy(source, copy);
            string digest = FileHash(copy);
            if (expected != null && digest != expected.ToLowerInvariant()) throw new InvalidDataException("Update package checksum mismatch");
            string payloadHash, linuxHash; Dictionary<string, object> metadata;
            using (ZipArchive zip = ZipFile.OpenRead(copy)) {
                ValidateZipMembers(zip, MaxUpdateBytes);
                metadata = ObjectJson(Entry(zip, "update.json")); RequireSchema(metadata);
                if (TextField(metadata, "repository") != ReleaseRepository) throw new InvalidDataException("Update belongs to another repository");
                TextField(metadata, "version"); TextField(metadata, "build");
                Dictionary<string, object> hashes = metadata["files"] as Dictionary<string, object>;
                if (hashes == null || hashes.Count != 2 || zip.Entries.Count != 3) throw new InvalidDataException("Update package must contain its manifest and both platform bundles");
                payloadHash = TextField(hashes, "Payload.zip").ToLowerInvariant(); linuxHash = TextField(hashes, "LinuxBundle.zip").ToLowerInvariant();
                foreach (KeyValuePair<string, object> item in hashes) {
                    string hash = item.Value as string;
                    if (!IsDigest(hash)) throw new InvalidDataException("Invalid update member checksum");
                    ZipArchiveEntry entry = zip.GetEntry(item.Key);
                    if (entry == null) throw new InvalidDataException("Missing update bundle: " + item.Key);
                    string target = SafeMember(directory, item.Key);
                    using (Stream input = entry.Open()) using (FileStream output = new FileStream(target, FileMode.CreateNew, FileAccess.Write)) input.CopyTo(output);
                    if (FileHash(target) != hash.ToLowerInvariant()) throw new InvalidDataException("Update bundle checksum mismatch: " + item.Key);
                }
            }
            using (ZipArchive payload = ZipFile.OpenRead(Path.Combine(directory, "Payload.zip"))) ValidateZipMembers(payload, 2L * 1024 * 1024 * 1024);
            VerifyLinuxBundle(Path.Combine(directory, "LinuxBundle.zip"));
            UpdatePackage previous = SelectedUpdate;
            SelectedUpdate = new UpdatePackage { Directory = directory, Source = copy, Digest = digest, Label = label + " / " + TextField(metadata, "version") + " / " + TextField(metadata, "build"), PayloadDigest = payloadHash, LinuxDigest = linuxHash };
            if (previous != null) DeletePlainDirectory(previous.Directory);
        } catch { DeletePlainDirectory(directory); throw; }
    }
    private static void RequireUpdateUri(Uri uri) {
        if (!uri.IsAbsoluteUri || uri.Scheme != "https" || String.IsNullOrEmpty(uri.Host) || !String.IsNullOrEmpty(uri.UserInfo))
            throw new InvalidDataException("Update URL must use HTTPS without embedded credentials");
    }
    private static HttpWebRequest Request(string url, string accept) {
        Uri uri = new Uri(url); RequireUpdateUri(uri);
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        HttpWebRequest request = (HttpWebRequest)WebRequest.Create(uri);
        request.UserAgent = "UtilityClient-Bootstrapper/1"; request.Accept = accept;
        request.Timeout = 20000; request.ReadWriteTimeout = 20000; request.AllowAutoRedirect = true;
        request.Headers[HttpRequestHeader.CacheControl] = "no-cache";
        return request;
    }
    private static byte[] Download(string url, long limit, Action<long, long> progress) {
        using (HttpWebResponse response = (HttpWebResponse)Request(url, url.Contains("/releases?") ? "application/vnd.github+json" : "application/octet-stream").GetResponse())
        using (Stream input = response.GetResponseStream()) using (MemoryStream output = new MemoryStream()) {
            RequireUpdateUri(response.ResponseUri);
            if (response.ContentLength > limit) throw new IOException("Download exceeds the size limit");
            DateTime deadline = DateTime.UtcNow.AddMinutes(3); byte[] buffer = new byte[65536]; int count;
            while ((count = input.Read(buffer, 0, buffer.Length)) > 0) {
                if (DateTime.UtcNow > deadline) throw new IOException("Update download timed out; retry when the connection is stable");
                if (output.Length > limit - count) throw new IOException("Download exceeds the size limit");
                output.Write(buffer, 0, count); if (progress != null) progress(output.Length, response.ContentLength);
            }
            return output.ToArray();
        }
    }
    internal static UpdateRelease PickUpdate(string json) {
        object parsed = Json.DeserializeObject(json); IEnumerable releases = parsed as IEnumerable;
        if (releases == null || parsed is string || parsed is Dictionary<string, object>) throw new InvalidDataException("Invalid release list");
        UpdateRelease best = null;
        foreach (object value in releases) {
            Dictionary<string, object> release = value as Dictionary<string, object>;
            if (release == null || !release.ContainsKey("draft") || !release.ContainsKey("prerelease") || !(release["draft"] is bool) || !(release["prerelease"] is bool)
                    || (bool)release["draft"] || (bool)release["prerelease"]) continue;
            object assetList; if (!release.TryGetValue("assets", out assetList)) continue;
            IEnumerable assets = assetList as IEnumerable; if (assets == null) continue;
            Dictionary<string, object> package = null, checksum = null;
            foreach (object item in assets) {
                Dictionary<string, object> asset = item as Dictionary<string, object>; if (asset == null) continue;
                object name; if (!asset.TryGetValue("name", out name)) continue;
                if (Convert.ToString(name) == "UtilityClient-Update.zip") package = asset;
                if (Convert.ToString(name) == "UtilityClient-Update.zip.sha256") checksum = asset;
            }
            if (package == null) continue;
            string digest = null; object raw;
            if (package.TryGetValue("digest", out raw) && raw is string && ((string)raw).StartsWith("sha256:")) digest = ((string)raw).Substring(7);
            if (!IsDigest(digest) && checksum == null) continue;
            string published = release.ContainsKey("published_at") ? Convert.ToString(release["published_at"]) : "";
            if (best != null && String.CompareOrdinal(best.Published, published) >= 0) continue;
            best = new UpdateRelease { Label = "Release " + TextField(release, "tag_name"), Published = published, Url = TextField(package, "browser_download_url"),
                Digest = IsDigest(digest) ? digest.ToLowerInvariant() : null, ChecksumUrl = checksum == null ? null : TextField(checksum, "browser_download_url") };
        }
        return best ?? new UpdateRelease();
    }
    internal static UpdateRelease FindUpdate() {
        CurrentStage = "Check published updates";
        string feed = UpdateFeedUrl();
        UpdateRelease release = !String.IsNullOrEmpty(feed) ? PickManifest(Encoding.UTF8.GetString(Download(feed, 65536, null)))
            : PickUpdate(Encoding.UTF8.GetString(Download("https://api.github.com/repos/" + ReleaseRepository + "/releases?per_page=100", 4 * 1024 * 1024, null)));
        if (release.Available) {
            if (!IsDigest(release.Digest)) release.Digest = ParseChecksum(Encoding.UTF8.GetString(Download(release.ChecksumUrl, 65536, null)), "UtilityClient-Update.zip");
            if (SelectedUpdate == null) RestoreSelectedUpdate();
            release.Installed = IsReleaseInstalled(release);
        }
        return release;
    }
    internal static string UpdateFeedUrl() {
        string configured = Path.Combine(AppRoot, "bootstrapper", "update-feed.url");
        if (File.Exists(configured)) { RequirePlainPath(configured); return File.ReadAllText(configured).Trim(); }
        using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("UpdateFeed.url")) {
            if (input == null) return "";
            using (StreamReader reader = new StreamReader(input)) return reader.ReadToEnd().Trim();
        }
    }
    internal static void ConfigureUpdateFeed(string url) {
        Uri uri; if (!Uri.TryCreate(url, UriKind.Absolute, out uri)) throw new InvalidDataException("Invalid update manifest URL");
        RequireUpdateUri(uri); RequirePlainPath(AppRoot);
        string directory = Path.Combine(AppRoot, "bootstrapper"); RequirePlainPath(directory); Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, "update-feed.url"); RequirePlainPath(path);
        File.WriteAllText(path, uri.AbsoluteUri, Encoding.UTF8);
    }
    internal static UpdateRelease PickManifest(string json) {
        Dictionary<string, object> metadata = Json.DeserializeObject(json) as Dictionary<string, object>;
        if (metadata == null) throw new InvalidDataException("Invalid update manifest");
        RequireSchema(metadata);
        if (TextField(metadata, "channel") != "stable") throw new InvalidDataException("Update manifest must use the stable channel");
        object raw; if (!metadata.TryGetValue("package", out raw)) throw new InvalidDataException("Missing update package");
        Dictionary<string, object> package = raw as Dictionary<string, object>;
        if (package == null) throw new InvalidDataException("Invalid update package");
        string url = TextField(package, "url"), digest = TextField(package, "sha256");
        Uri uri; if (!Uri.TryCreate(url, UriKind.Absolute, out uri)) throw new InvalidDataException("Invalid package URL");
        RequireUpdateUri(uri);
        if (!IsDigest(digest)) throw new InvalidDataException("Invalid package SHA-256");
        long bytes; if (!package.TryGetValue("bytes", out raw) || !Int64.TryParse(Convert.ToString(raw), out bytes) || bytes <= 0 || bytes > MaxUpdateBytes)
            throw new InvalidDataException("Invalid update package size");
        return new UpdateRelease { Url = uri.AbsoluteUri, Digest = digest.ToLowerInvariant(),
            Label = "Client " + TextField(metadata, "version") + " (" + TextField(metadata, "build") + ")", Published = TextField(metadata, "published") };
    }
    internal static void DownloadUpdate(UpdateRelease release, Action<long, long> progress) {
        if (release == null || !release.Available) throw new IOException("No compatible update selected");
        CurrentStage = "Download and verify update";
        string expected = release.Digest;
        if (!IsDigest(expected)) expected = ParseChecksum(Encoding.UTF8.GetString(Download(release.ChecksumUrl, 65536, null)), "UtilityClient-Update.zip");
        byte[] bytes = Download(release.Url, MaxUpdateBytes, progress);
        string actual; using (Stream input = new MemoryStream(bytes)) actual = Hash(input);
        if (actual != expected.ToLowerInvariant()) throw new InvalidDataException("Published update checksum mismatch; installed files were preserved");
        RequirePlainPath(AppRoot); Directory.CreateDirectory(AppRoot);
        string temporary = Path.Combine(AppRoot, ".download-" + Guid.NewGuid().ToString("N") + ".zip");
        try { File.WriteAllBytes(temporary, bytes); SelectUpdatePackage(temporary, expected, release.Label); }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    internal static string ParseChecksum(string text, string filename) {
        string bare = text.Trim(); if (IsDigest(bare)) return bare.ToLowerInvariant();
        string result = null;
        foreach (string line in text.Replace("\r", "").Split('\n')) {
            if (line.Length < 66) continue;
            string hash = line.Substring(0, 64), name = line.Substring(64).Trim().TrimStart('*');
            if (name != filename) continue;
            if (!IsDigest(hash) || result != null) throw new InvalidDataException("Invalid or duplicate published checksum for " + filename);
            result = hash.ToLowerInvariant();
        }
        if (result == null) throw new InvalidDataException("Published checksum is missing for " + filename);
        return result;
    }
    private static void PersistSelectedUpdate() {
        if (SelectedUpdate == null) return;
        string directory = Path.Combine(AppRoot, "bootstrapper"); Directory.CreateDirectory(directory);
        string package = Path.Combine(directory, "UtilityClient-Update.zip"), pending = package + "." + Guid.NewGuid().ToString("N");
        try {
            File.Copy(SelectedUpdate.Source, pending);
            AtomicPair(pending, package, SelectedUpdate.Digest + "  UtilityClient-Update.zip\n", package + ".sha256");
        } finally { if (File.Exists(pending)) File.Delete(pending); }
    }
    private static void RestoreSelectedUpdate() {
        string package = Path.Combine(AppRoot, "bootstrapper", "UtilityClient-Update.zip");
        RequirePlainPath(AppRoot);
        if (File.Exists(package) && File.Exists(package + ".sha256")) {
            try { SelectUpdatePackage(package, ParseChecksum(File.ReadAllText(package + ".sha256"), "UtilityClient-Update.zip"), "Cached verified update"); }
            catch (Exception error) { LastShortcutWarning = "Cached update could not be verified: " + error.Message + ". The embedded package is available."; }
        }
    }
    internal static void UseEmbeddedPackage() { AutoUpdates = false; UpdatePackage previous = SelectedUpdate; SelectedUpdate = null; if (previous != null) DeletePlainDirectory(previous.Directory); }
    internal static void CleanupUpdate() { if (SelectedUpdate != null) DeletePlainDirectory(SelectedUpdate.Directory); }
}
