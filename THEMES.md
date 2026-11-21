# Theme creation and sharing

Open the bottom-right **Themes** button. There are sixteen palettes and four styles: Flat, Rounded, Outlined and Glass. Pick a preset as a starting point, edit colors/geometry, and review the preview. The active menu changes only when you click **Apply**; closing the screen discards an unapplied draft. **Save** stores the draft as a local JSON theme without changing the active appearance.

The editable colors are background, panel, accent, primary text, secondary text and border. Enter colors as `#RRGGBB`. Panel opacity ranges from 0.55 to 1; Glass adds its translucent finish. Rounded/Glass corner radius ranges from 0 to 16, panel border thickness from 0 to 3, and hover accent strength from 0 to 0.5.

Set a name and optional author, each up to 48 printable characters. **Copy Code** creates a compact `UCTHEME1:` clipboard string suitable for posting in Discord. Share that string or the saved JSON file. The author field is creator-supplied metadata, not proof of identity.

Recipients can copy a code/raw JSON or a local JSON file path, click **Import**, inspect the draft preview, then Apply or Save. A saved local theme can be clicked in the right-side library to preview it. Theme files live under `utilityclient/themes` inside the client settings directory. **Folder** opens it; reopen Themes after adding files to refresh the library. At most 128 JSON files are listed.

## JSON format

```json
{
  "format": 1,
  "client": "utilityclient",
  "name": "Ocean Glass",
  "author": "Community creator",
  "style": "Glass",
  "colors": {
    "background": "#071D2A",
    "panel": "#103346",
    "accent": "#49D3E9",
    "text": "#FFFFFF",
    "dim": "#9FB2BA",
    "border": "#365466"
  },
  "opacity": 0.85,
  "radius": 8,
  "borderWidth": 1,
  "hover": 0.15
}
```

A share code is `UCTHEME1:` followed by unpadded URL-safe Base64 of the UTF-8 JSON. Themes are limited to 8 KiB and use this fixed format. Invalid colors, unsupported styles, missing/unexpected fields and out-of-range values are rejected before applying. Theme imports do not import modules, friends, keybinds, files or network URLs.
