using System;
using System.IO;
using System.Text;
using System.Xml;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

// Java's Windows launcher uses ANSI APIs to locate its own DLLs. A UTF-8 process
// code page keeps non-ASCII profile paths intact on Windows 10/11.
public static class UtilityClientRuntimeManifest {
    private delegate bool LanguageCallback(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr parameter);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr LoadLibraryExW(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr FindResourceW(IntPtr module, IntPtr name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool EnumResourceLanguagesW(IntPtr module, IntPtr type, IntPtr name, LanguageCallback callback, IntPtr parameter);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr BeginUpdateResourceW(string file, bool deleteExisting);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool UpdateResourceW(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool EndUpdateResourceW(IntPtr update, bool discard);

    public static void Patch(string path) {
        IntPtr name = new IntPtr(1), type = new IntPtr(24);
        IntPtr module = LoadLibraryExW(Path.GetFullPath(path), IntPtr.Zero, 2);
        if (module == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        List<ushort> languages = new List<ushort>();
        byte[] bytes;
        try {
            IntPtr resource = FindResourceW(module, name, type);
            if (resource == IntPtr.Zero) throw new IOException("Java launcher manifest missing");
            bytes = new byte[SizeofResource(module, resource)];
            Marshal.Copy(LockResource(LoadResource(module, resource)), bytes, 0, bytes.Length);
            LanguageCallback callback = delegate(IntPtr m, IntPtr t, IntPtr n, ushort language, IntPtr p) { languages.Add(language); return true; };
            if (!EnumResourceLanguagesW(module, type, name, callback, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
            GC.KeepAlive(callback);
        } finally { FreeLibrary(module); }
        XmlDocument xml = new XmlDocument();
        xml.LoadXml(Encoding.UTF8.GetString(bytes).TrimEnd('\0'));
        const string appNamespace = "urn:schemas-microsoft-com:asm.v3";
        XmlElement app = null, settings = null;
        foreach (XmlNode node in xml.DocumentElement.ChildNodes)
            if (node.LocalName == "application" && node.NamespaceURI == appNamespace) app = (XmlElement)node;
        if (app == null) { app = xml.CreateElement("application", appNamespace); xml.DocumentElement.AppendChild(app); }
        foreach (XmlNode node in app.ChildNodes) if (node.LocalName == "windowsSettings") settings = (XmlElement)node;
        if (settings == null) { settings = xml.CreateElement("windowsSettings", appNamespace); app.AppendChild(settings); }
        for (int i = settings.ChildNodes.Count - 1; i >= 0; i--)
            if (settings.ChildNodes[i].LocalName == "activeCodePage") settings.RemoveChild(settings.ChildNodes[i]);
        XmlElement codePage = xml.CreateElement("activeCodePage", "http://schemas.microsoft.com/SMI/2019/WindowsSettings");
        codePage.InnerText = "UTF-8";
        settings.AppendChild(codePage);
        bytes = new UTF8Encoding(false).GetBytes(xml.OuterXml);
        IntPtr update = BeginUpdateResourceW(Path.GetFullPath(path), false);
        if (update == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        bool commit = false;
        try {
            foreach (ushort language in languages)
                if (!UpdateResourceW(update, type, name, language, bytes, (uint)bytes.Length)) throw new Win32Exception(Marshal.GetLastWin32Error());
            commit = true;
        } finally {
            if (!EndUpdateResourceW(update, !commit) && commit) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }
}
