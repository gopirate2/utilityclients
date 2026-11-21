using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Web.Script.Serialization;

internal sealed class InjectorLaunchOptions {
    internal bool AutoInject, StartMinimized, AllowMultiple, ReinjectOnRestart = true;
    internal int WarmupSeconds = 8, ScanIntervalMs = 1500;
    internal string GameDirectory = "";
}
internal static class LaunchOptionsFile {
    internal const string Name = "injector-settings.json";
    private static Dictionary<string, object> Data(string root) {
        string file = Path.Combine(root, Name);
        if (!File.Exists(file)) return new Dictionary<string, object>();
        if (new FileInfo(file).Length > 65536) throw new IOException("Launch settings file is too large");
        Dictionary<string, object> value = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(file, Encoding.UTF8));
        if (value == null) throw new IOException("Launch settings must contain a JSON object"); return value;
    }
    internal static InjectorLaunchOptions Read(string root) {
        Dictionary<string, object> data = Data(root); InjectorLaunchOptions value = new InjectorLaunchOptions();
        Integer(data, "schema", 1, 1, 1);
        value.AutoInject = Boolean(data, "autoInject", false); value.StartMinimized = Boolean(data, "startMinimized", false);
        value.AllowMultiple = Boolean(data, "allowMultiple", false); value.ReinjectOnRestart = Boolean(data, "reinjectOnRestart", true);
        value.WarmupSeconds = Integer(data, "warmupSeconds", 8, 0, 120); value.ScanIntervalMs = Integer(data, "scanIntervalMs", 1500, 500, 15000);
        object directory; if (data.TryGetValue("gameDirectory", out directory)) {
            if (!(directory is string) || ((string)directory).Length > 32768) throw new IOException("Invalid game directory in launch options");
            value.GameDirectory = ((string)directory).Trim();
        }
        return value;
    }
    private static bool Boolean(Dictionary<string, object> data, string key, bool fallback) {
        object item; if (!data.TryGetValue(key, out item)) return fallback;
        if (!(item is bool)) throw new IOException("Invalid " + key + " in launch options"); return (bool)item;
    }
    private static int Integer(Dictionary<string, object> data, string key, int fallback, int min, int max) {
        object item; if (!data.TryGetValue(key, out item)) return fallback;
        if (!(item is int) && !(item is long) && !(item is decimal) && !(item is double)) throw new IOException("Invalid " + key + " in launch options");
        decimal number = Convert.ToDecimal(item);
        if (number < min || number > max || Decimal.Truncate(number) != number) throw new IOException(key + " must be " + min + "–" + max);
        return (int)number;
    }
    internal static void Save(string root, InjectorLaunchOptions value) {
        if (value.WarmupSeconds < 0 || value.WarmupSeconds > 120 || value.ScanIntervalMs < 500 || value.ScanIntervalMs > 15000
                || value.GameDirectory == null || value.GameDirectory.Length > 32768) throw new IOException("Launch options are outside their supported range");
        Dictionary<string, object> data;
        try { data = Data(root); } catch (Exception) { data = new Dictionary<string, object>(); }
        data["schema"] = 1; data["autoInject"] = value.AutoInject; data["startMinimized"] = value.StartMinimized;
        data["allowMultiple"] = value.AllowMultiple; data["reinjectOnRestart"] = value.ReinjectOnRestart;
        data["warmupSeconds"] = value.WarmupSeconds; data["scanIntervalMs"] = value.ScanIntervalMs; data["gameDirectory"] = value.GameDirectory.Trim();
        Directory.CreateDirectory(root); string file = Path.Combine(root, Name), pending = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try {
            File.WriteAllText(pending, new JavaScriptSerializer().Serialize(data) + "\n", new UTF8Encoding(false));
            if (File.Exists(file)) File.Replace(pending, file, null); else File.Move(pending, file);
        } finally { if (File.Exists(pending)) File.Delete(pending); }
    }
}
