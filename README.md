# UtilityClient

UtilityClient is a client for **Minecraft Java 26.2 with Fabric**. The Windows setup includes a private Java 25 runtime for the injector. Your Minecraft launcher still needs a compatible Java runtime and Fabric profile.

[Download the latest Windows setup](https://github.com/gopirate2/utilityclients/releases/latest/download/UtilityClient-Setup-Updated.exe) · [All releases and change lists](https://github.com/gopirate2/utilityclients/releases) · [Build status](https://github.com/gopirate2/utilityclients/actions)

## Install and open the client

1. Download and open `UtilityClient-Setup-Updated.exe`. It checks GitHub in the background when its window opens. Choose Install, Repair, or Update as appropriate; saved client settings are preserved.
2. Open Minecraft using the supported Fabric profile. Open the installed **UtilityClient** launcher/injector, select the Minecraft process, then inject the client. The injector also checks GitHub when its window opens.
3. Press **Right Ctrl** for the client menu. Press the **grave accent/backtick key** for the terminal. Startup-loaded keybinds can be changed in Minecraft's Controls settings.

The injector's startup/mod installation option can install UtilityClient into a selected Fabric profile's `mods` folder. Restart Minecraft after installing a startup mod. Keep one UtilityClient copy in that folder.

To export the matching Linux package, use **Export Linux** in setup. The exported ZIP includes its own installer instructions; the Windows private runtime is not a Linux runtime.

## Updates: setup, injector, and client

Opening setup or the injector performs a **check**, not an unrequested installation. Setup shows Update Available or You're Up To Date. Choose **Update** to download the complete verified client/injector/runtime package. The injector's **Fetch Client / Update Client** action downloads and verifies the current client JAR; use setup to update the installed injector and runtime as well.

GitHub checks use the public `gopirate2/utilityclients` repository. No GitHub login, token, card, or subscription is needed to download releases. Drafts and prereleases are skipped. SHA-256 checksums and package integrity are verified before installation; a matching installed build is recognized rather than offered repeatedly. Version numbers such as `1.0.2` identify releases, and build hashes distinguish rebuilt packages.

If GitHub is unavailable or rate-limits a connection, your local files remain available. **Offline Package** or `--offline` uses the embedded package. **Local Update** accepts a downloaded `UtilityClient-Update.zip` and its checksum sidecar. Earlier updater-enabled setups can retrieve newer packages, but an old EXE cannot gain new UI behavior without being replaced once.

For this repository, pushing source changes to `main` or `master` triggers the release workflow. It builds Windows/Linux, runs client and injector checks, verifies the installer, uploads all assets to a draft, then publishes. A failed build does not publish an incomplete update. See [automatic releases](AUTOMATIC-UPDATES.md) for details.

## Custom modules: the bottom-right Modules button

**Modules** opens an in-game extension panel. It is separate from the list of built-in modules.

### Try the ready-made example

1. Open the menu with Right Ctrl and click **Modules** at the bottom right.
2. Click **Install Example**. No compiler or separate JDK is needed for this example.
3. Close the panel, search for **HelloCustom**, then enable it. It displays a local notification. You can give it a keybind and save its settings in a profile.

### Install someone else's custom module

Obtain a JAR built for the **UtilityClient ModuleProvider API**, not an arbitrary Fabric mod. In the Modules panel, paste its full file path with Ctrl+V and press Enter. This copies it into the module folder and loads new modules immediately. Alternatively, use **Open Folder**, copy the JAR directly into that folder, then click **Reload**. **Copy Path** works even if opening the system file manager fails.

The **Load Report** shows imported module names and errors. Duplicate names, missing provider files, unsupported API versions, and oversized or invalid JARs are explained there. A failed JAR does not stop other imports. Replacing or removing a JAR that is already loaded requires restarting Minecraft; Reload adds new JARs without running existing providers twice.

### Make your own

Click **Open SDK**. The SDK includes source for `HelloProvider`, its required service file, Windows/Linux build scripts, and detailed instructions. Install a **Java 25 JDK** to compile your own code; the private runtime in setup is not a compiler. Edit the example and run `build.ps1` or `build.sh`. The result is `HelloModules.jar` in the module folder. Give your module a unique name before installing another copy of the example.

The required service file is `META-INF/services/com.baggute.cheatclient.api.ModuleProvider`. Custom modules extend UtilityClient's `Module` and return from a public `ModuleProvider` with API version 1. Do not bundle copies of UtilityClient, Minecraft, or Fabric classes. Modules calling Minecraft APIs also need the matching game/dependency compilation classpath. See the [SDK README](src/client/resources/module-sdk/README.md) for the exact structure. Custom JARs run code inside Minecraft, so use sources/authors you trust.

## Commands and where to type them

**Dot commands go in Minecraft chat.** Terminal commands are entered in the backtick terminal without a leading dot.

| Command | What it does |
| --- | --- |
| `.version` | Client/version details in chat. |
| Terminal `version` | Prints the loaded client version, such as `1.0.2`. |
| `.lookup <player>` | Shows available player information. Remote OP status can be unknown when the server does not disclose it. |
| `.uuid <player>` | Copies the UUID to your clipboard. Uses the server's player list first, then a Mojang account lookup. |
| `.drop 1 stone`, `.drop 10 diamond`, `.drop 100 arrow` | Drops up to the requested item count across matching inventory stacks. |
| `.drop all held` | Drops all inventory items of the held item type. |
| `.potion` or `.potion list` | Opens the complete potion catalogue. Click a potion for its brewing guide. |
| `.potion strength2`, `.potion str1`, `.potion invis8` | Aliases and level/duration shortcuts. |
| `.potion minecraft:long_fire_resistance` | Registry-style names and stronger/extended variants also work. |
| `.potion splash_strong_healing` | Shows the effect recipe and the optional splash/lingering conversion. |
| Terminal `kit save <name>` / `kit load <name>` | Saves or replaces a kit. See the inventory explanation below. |
| `#help` | Baritone commands, after Baritone loads on Minecraft startup. |
| `/seedcracker` | SeedCrackerX command root; use tab completion for its available subcommands. |

### Potion levels, quantities, and durations

The potion counter prints the **effect level inside the bottle icon** and the **number of bottles beside it**. Strength I and Strength II remain separate entries. Guides support all vanilla potion types, including awkward/water/mundane/thick bases and Luck's lack of a survival brewing recipe.

Redstone extends supported potions; glowstone strengthens supported potions. A potion cannot be both stronger and extended. Eight-minute Strength, Speed, Invisibility, Night Vision, Fire Resistance, Water Breathing, and Leaping use redstone. Other effects have different maximum durations, displayed in the guide. Gunpowder makes splash potions; dragon's breath then makes lingering potions, whose effects last one quarter of the listed drink/splash duration.

## Kits, Baritone, themes, and common surprises

**Kit Load replaces the whole inventory**, including armor/offhand and clearing positions omitted from the kit. It can do this in creative mode or your own singleplayer world. A remote survival server must authorize creating items; the client does not fabricate a local inventory that the server will overwrite. Kit Save stores item components and slot positions.

**Baritone is a separate Fabric mod**, not a client module toggle. Startup installation places its side-load JAR in the selected profile. Late injection stages it in that profile's mods folder for the next restart. Restart Minecraft once, then use `#help`. An existing standalone Baritone copy is preserved. SeedCrackerX is similarly accessed through its commands and has no module-list entry.

**Themes** is the bottom-right menu button. Appearance is hidden from Misc/module lists. Choose a panel style and palette, or edit Custom colors; changes apply immediately and are saved with settings.

**AutoInstacart** starts after a bow release that fires an arrow; it does not require a full draw. Its default four-tick `shotDelay` lets you look down before it chooses the placement block. The crosshair must reach a suitable support/rail and the required items must be in the hotbar. AutoInstacart and FastCart do not steer your camera.

**AutoAlienWC** requires an equipped elytra, active gliding, and downward velocity. It throws down with a brief one-tick view snap and restores your view. **Flight** uses jump/shift for vertical movement and hovers with neutral vertical input.

Health/Armor Indicators each have a **Players Only** option; disable it to include mobs. Their positions are interpolated each render frame, but health/armor information still depends on the server's updates.

## Files and troubleshooting

On Windows, client settings and program files are under `%APPDATA%\depends`. Custom JARs are under `%APPDATA%\depends\utilityclient\modules`; its `SDK` subfolder and `load-report.txt` explain imports. The private runtime and retained setup/update cache are under `%LOCALAPPDATA%\UtilityClient`. Linux uses `$XDG_CONFIG_HOME/depends`, usually `~/.config/depends`.

If a module does not appear, read the Modules panel's Load Report. If Baritone is missing, check the correct Fabric profile's mods folder and restart. If a changed client still behaves like the old build, check `version`, fully close Minecraft, update through setup, and reopen the correct profile. Installation Health and Repair can restore damaged program files while preserving settings. Uninstall preserves settings by default; remove them only through its explicit settings-removal option.

The project license is [CC0](LICENSE). Bundled Baritone has its own [license and source notices](vendor/README.md).
