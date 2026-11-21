using System;
using System.IO;
using System.Reflection;
using System.Drawing;

internal static class AppBrand {
    internal static Icon FormIcon() {
        using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream("Bootstrapper.ico")) {
            if (source == null) throw new IOException("Bootstrapper icon resource is missing");
            return new Icon(source);
        }
    }
    private static readonly Bitmap HeaderImage = LoadPreview();
    private static Bitmap LoadPreview() {
        using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream("Bootstrapper.png")) {
            if (source == null) throw new IOException("Bootstrapper preview resource is missing");
            using (Bitmap image = new Bitmap(source)) return new Bitmap(image);
        }
    }
    internal static void DrawHeader(Graphics graphics) {
        graphics.DrawImage(HeaderImage, new Rectangle(13, 6, 30, 30));
    }
}
