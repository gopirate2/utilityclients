using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

// Use Unicode shell interfaces directly. WScript's TargetPath setter interprets
// percent characters as environment-variable syntax and can reject valid paths.
internal static class WindowsShortcuts {
    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class ShellLink { }

    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellLinkW {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int length, IntPtr findData, uint flags);
        void GetIDList(out IntPtr list);
        void SetIDList(IntPtr list);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder text, int length);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string text);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int length);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string path);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int length);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
        void GetHotkey(out short hotkey);
        void SetHotkey(short hotkey);
        void GetShowCmd(out int command);
        void SetShowCmd(int command);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int length, out int index);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
        void Resolve(IntPtr window, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHParseDisplayName(string name, IntPtr context, out IntPtr list, uint attributes, out uint actualAttributes);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CommandLineToArgvW(string command, out int count);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);

    internal static void Create(string target, string arguments, string folder, string workingDirectory) {
        Directory.CreateDirectory(folder);
        string file = Path.Combine(folder, "UtilityClient.lnk");
        if (File.Exists(file) && !Owns(Read(file), target, workingDirectory))
            throw new IOException("The existing shortcut belongs to another installation: " + file);
        string pending = file + "." + Guid.NewGuid().ToString("N") + ".tmp";
        object instance = new ShellLink();
        try {
            IShellLinkW link = (IShellLinkW)instance;
            IntPtr item; uint attributes;
            Marshal.ThrowExceptionForHR(SHParseDisplayName(Path.GetFullPath(target), IntPtr.Zero, out item, 0, out attributes));
            try { link.SetIDList(item); }
            finally { Marshal.FreeCoTaskMem(item); }
            // Persist an absolute filesystem path as well as the shell item.
            // PIDLs alone can lose their path when a new process reloads a link.
            link.SetPath(Path.GetFullPath(target));
            link.SetArguments(arguments);
            link.SetWorkingDirectory(workingDirectory);
            link.SetDescription("UtilityClient injector");
            link.SetIconLocation(Path.GetFullPath(target), 0);
            link.SetShowCmd(1);
            ((IPersistFile)instance).Save(pending, true);
            string[] savedValues = Read(pending);
            if (!String.Equals(savedValues[0], Path.GetFullPath(target), StringComparison.OrdinalIgnoreCase) || savedValues[1] != arguments)
                throw new IOException("Saved shortcut has the wrong target or arguments: " + file);
            if (File.Exists(file)) File.Replace(pending, file, null);
            else File.Move(pending, file);
        } finally { Marshal.FinalReleaseComObject(instance); if (File.Exists(pending)) File.Delete(pending); }
    }

    internal static bool Owns(string[] saved, string target, string root) {
        if (saved == null || saved.Length < 2 || !String.Equals(Path.GetFullPath(saved[0]), Path.GetFullPath(target), StringComparison.OrdinalIgnoreCase)) return false;
        string[] args = Arguments(saved[1]); bool found = false;
        for (int i = 0; i < args.Length; i++) {
            if (args[i] == "--root") {
                if (found || ++i >= args.Length || !String.Equals(Path.GetFullPath(args[i]).TrimEnd('\\', '/'), Path.GetFullPath(root).TrimEnd('\\', '/'), StringComparison.OrdinalIgnoreCase)) return false;
                found = true;
            } else if (args[i] != "--gui" && args[i] != "--watch" && args[i] != "--startup" && args[i] != "--minimized") return false;
        }
        return found;
    }
    private static string[] Arguments(string text) {
        int count; IntPtr memory = CommandLineToArgvW("UtilityClient " + text, out count);
        if (memory == IntPtr.Zero) throw new IOException("Shortcut arguments could not be read");
        try {
            string[] values = new string[Math.Max(0, count - 1)];
            for (int i = 1; i < count; i++) values[i - 1] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(memory, i * IntPtr.Size));
            return values;
        } finally { LocalFree(memory); }
    }
    internal static void RemoveOwned(string target, string root, string folder) {
        string file = Path.Combine(folder, "UtilityClient.lnk");
        if (!File.Exists(file)) return;
        if (!Owns(Read(file), target, root)) throw new IOException("The existing shortcut belongs to another installation: " + file);
        File.Delete(file);
    }
    internal static string[] ReadIcon(string file) {
        object instance = new ShellLink();
        try {
            ((IPersistFile)instance).Load(file, 0); StringBuilder path = new StringBuilder(32768); int index;
            ((IShellLinkW)instance).GetIconLocation(path, path.Capacity, out index);
            return new string[] { path.ToString(), index.ToString() };
        } finally { Marshal.FinalReleaseComObject(instance); }
    }

    internal static string[] Read(string file) {
        object instance = new ShellLink();
        try {
            ((IPersistFile)instance).Load(file, 0);
            StringBuilder saved = new StringBuilder(32768);
            StringBuilder arguments = new StringBuilder(32768);
            ((IShellLinkW)instance).GetPath(saved, saved.Capacity, IntPtr.Zero, 4);
            ((IShellLinkW)instance).GetArguments(arguments, arguments.Capacity);
            return new string[] { saved.ToString(), arguments.ToString() };
        } finally { Marshal.FinalReleaseComObject(instance); }
    }
}
