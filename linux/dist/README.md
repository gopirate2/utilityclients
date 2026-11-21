# UtilityClient installation

Minecraft 26.2 requires Java 25, Fabric Loader, and Fabric API in the chosen game instance.
The injector has its own window; it does not install Minecraft or Fabric.

`cheatclient.jar` includes UtilityClient, SeedCrackerX and Baritone 1.19.0 for Minecraft 26.2.
Full Baritone needs Fabric startup loading: copy this JAR into the chosen instance's `mods`
folder and restart Minecraft. Linux installation accepts `--mods-dir` for that copy.
Late injection supports the UtilityClient runtime hooks and SeedCracker observations,
but cannot add Baritone's startup mixin interfaces to already-loaded Minecraft classes.
Use `/seedcracker` for SeedCracker commands and `#help` for Baritone commands.
Baritone's license and corresponding source archive are included under `third-party/baritone`.

## Windows: offline bootstrapper

Run `UtilityClient-Setup.exe`, then click **INSTALL** or **REPAIR**. It includes the client, injector,
and a private Java 25 runtime. No Java download or administrator access is required.
The charcoal-and-cyan UI shows four stages, an actual file/byte progress table, and a scrollable log.
Its six underlying checks cover the system, package verification, runtime extraction, client and
injector installation, launcher creation, and completion. The **Launch when done** checkbox
controls whether the injector opens afterward.

Installed JARs and existing settings live in `%APPDATA%\depends`. The private runtime
lives in `%LOCALAPPDATA%\UtilityClient\versions`. Desktop and Start menu shortcuts
open the installed `UtilityClient.exe` launcher. The setup verifies each saved shortcut target.
The launcher checks for a visible injector window and records startup errors in
`%APPDATA%\depends\launcher.log`; startup failures also show an error dialog.
The setup window provides **LAUNCH** and **OPEN INSTALL FOLDER** controls.
If client files already exist, click **REPAIR** to update the launcher and shortcuts. `run.cmd` in the install folder also accepts injector CLI arguments.
Close the injector before reinstalling; locked files cause installation to stop and roll back.

Build the executable from the Windows source folder:

```powershell
.\dist\build-bootstrapper.ps1 -Jdk 'C:\Program Files\Java\jdk-25.0.2'
# Cached/offline build:
.\dist\build-bootstrapper.ps1 -Offline -Jdk 'C:\Program Files\Java\jdk-25.0.2'
```

Output: `build\bootstrapper\UtilityClient-Setup.exe`. Build requires an x64 JDK 25
and the .NET Framework C# compiler. The resulting EXE needs neither on the destination
apart from Windows' .NET Framework 4.8 runtime. This package targets x64 Windows.
The local EXE is unsigned; its embedded SHA-256 detects payload corruption, not publisher identity.

For an unpacked ZIP with a suitable Java installation, the smaller script also works:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
# Or name an archive / specific existing Fabric mods folder:
.\install.ps1 -From .\UtilityClient-1.0.0.zip -ModsDir 'C:\Games\instance\mods'
```

## Linux: install a release bundle

Extract the ZIP and keep the JARs and installer helpers together:

```bash
bash install.sh
# Or install a ZIP without extracting it:
bash install.sh --from ./UtilityClient-1.0.0.zip
# Also copy the client into your chosen Fabric instance:
bash install.sh --from ./UtilityClient-1.0.0.zip --mods-dir "$HOME/Games/instance/mods"
```

Bash and Python 3.8+ are required. The installer finds Java 25 with `jdk.attach` and
`java.desktop`, or downloads a private Temurin JDK 25 using HTTPS and verifies the
published SHA-256. It supports Linux x86_64 and aarch64 and performs no sudo operations.
For an entirely offline installation, supply a local bundle and use `--offline`.
Java must already be available, or use `--no-runtime` to install files for later setup.
A bundle can also be supplied with `--url https://...`; `UC_URL` supplies the same default.

Open **UtilityClient** from the application menu, or run:

```bash
"${XDG_CONFIG_HOME:-$HOME/.config}/depends/run.sh"
"${XDG_CONFIG_HOME:-$HOME/.config}/depends/run.sh" --list
```

JARs, the launcher, and settings live in `${XDG_CONFIG_HOME:-$HOME/.config}/depends`.
Private runtimes and the menu entry live under `${XDG_DATA_HOME:-$HOME/.local/share}`.
Use `--no-desktop` to skip the menu entry. `JAVA_HOME` overrides the remembered runtime
when it names a suitable JDK. macOS may use the shell installer with an existing JDK25;
automatic runtime provisioning and application-menu integration target Linux.

## Linux: install from the source folder

From the repository's `linux` folder:

```bash
bash setup.sh
bash setup.sh --mods-dir "$HOME/Games/instance/mods"
# With the JDK and Gradle dependencies already cached:
bash setup.sh --offline
```

Setup finds or provisions a full JDK 25, builds and checks the client and injector,
then runs the same staged installer. The first source build needs network access
for Gradle, Minecraft, Fabric, and other declared dependencies. No Git command is needed.

## Using the injector

Launch a Minecraft 26.2 Fabric instance with Fabric API and wait for the title screen.
Select the game JVM and client JAR in the injector, then click **Inject**.
The progress strip reports **Checking Ã¢â€ â€™ Connecting Ã¢â€ â€™ Loading Ã¢â€ â€™ Initializing Ã¢â€ â€™ Complete**.
Completion comes from the target agent after client initialization; attach success alone
does not show a ready client. Stage-specific failures remain visible with details in
**Target output**. A mod installation reports **Installed; restart the game**, since
it cannot verify initialization before launch. Injection does not replay mixins into
classes Minecraft has already loaded; review target hook warnings if functionality differs.

Right Control opens the client menu by default. The game and injector must run as the
same user at the same privilege level, with the JVM attach mechanism enabled. Linux
needs a graphical session (Swing may use XWayland). Windows capture-exclusion APIs
and the Windows native DLL are unavailable on Linux.

## Module settings

Press Right Control and right-click a module to open its settings. Closing the menu
saves changes. Reset restores that module's registered defaults.

The Apex tab now has 93 controls across 46 directly implemented entries. These cover
action intervals, target scan ranges, health thresholds, item filters, refill thresholds,
and visual colours, widths and limits. Intervals use ticks (20 ticks = 1 second).
Ranges change target scanning; the existing interaction limits still apply.

Seven delegated Apex entries expose the settings of their underlying module:
AutoShieldBreak / ShieldBreaker, SprintReset / SmartWTap, ItemESP / ESP,
MobESP / ESP, ShaderESP / ESP, Xray / Fullbright, and ConfigBind / PresetSwitch.
These controls share live values. Configs save them once under the underlying module,
while each Apex entry keeps its own toggle keybind.

AutoEat, AutoMine, AutoPot, AutoTotem, CartGrabber, MouseTweaks, Freecam, NoFall,
and SeedCrackerX also have additional controls. Existing configs retain their saved
values; newly added settings use defaults matching the previous behaviour.

## Profile comparison

Choosing a saved preset or importing a profile from the clipboard/file opens **Profile comparison**.
Each row shows the current and incoming values; bounds and legacy keybind hold modes match
the effective values that will be applied. Unknown module/settings rows are marked ignored.
**Copy Diff** copies the complete comparison, and clicking a row copies its full detail.
**Cancel** or Escape returns to the menu without applying anything. **Apply** uses the
reviewed snapshot. If live settings changed during the review, it refreshes the comparison
and requires another Apply. Friend identities are compared as normalized names/UUIDs;
applying may associate a saved name with a player already present in the world.

## Integrity and failure recovery

The injector verifies client metadata, archive CRCs, entrypoint bytecode, declared resources,
and all 24 public/static runtime helper contracts before installing or attaching. It hashes
the selected file again in the target JVM before loading it. Downloaded release assets require
the GitHub asset SHA-256 or a published checksum sidecar; mismatches preserve the cached client.
An offline local JAR without an expected checksum gets structural verification and explicitly
reports that no trusted release digest was supplied. A matching local checksum detects changes;
it does not by itself prove who published the file.

```bash
java -jar injector.jar --verify cheatclient.jar
java -jar injector.jar --verify cheatclient.jar --sha256 <expected-64-hex-digest>
sha256sum -c SHA256SUMS
```

Failed injector operations display recovery steps and retain the technical error in target
output. Loaded-class/helper errors require a complete Minecraft restart with a matching
client/injector pair. Attachment timeouts require checking target output before another attempt.
Permissions, disabled JVM attachment, missing runtimes, locked files and download failures
have separate recovery instructions. Recovery guidance does not automatically retry injection.

## Bootstrapper maintenance and future updates

**Health** checks the recorded program files, generated launchers, Java runtime files/modules,
client helper contracts and owned shortcut targets. **Copy Report** exports the results, and
**Repair** restores the selected verified package while preserving settings. An older install
without an installation record needs one Repair to establish checksums and ownership.

**Uninstall** defaults to keeping settings, friends, profiles, logs and backups. Its second
choice also removes those managed data paths. Both choices retain unrelated files, other
installations and the reusable setup. Close the injector and Minecraft before removing locked
files. Removal is staged and rolls back if moving program files or removing an owned shortcut/
registration fails. A rollback failure identifies the retained recovery directory.

**Export Linux** writes a Linux ZIP plus its `.sha256` sidecar outside the managed installation
folders. Send both files to the Linux user. The ZIP contains the Linux client, injector, shell/
Python installers, instructions, third-party notices/source and `SHA256SUMS`, without Windows
binaries or the Windows native DLL. Extract it and run `bash install.sh`.

**Check Updates** finds the newest stable compatible release in `gopirate2/utilityclients`.
It downloads and checks `UtilityClient-Update.zip`, then installs through the same staged
installer. **Local Update** selects that package from disk; **Offline Package** selects the
original embedded payload. Setup retains its reusable EXE and the last installed update under
`%LOCALAPPDATA%\UtilityClient\bootstrapper`, so later updates use the same bootstrapper and
offline Repair can use the cached package. An old bootstrapper needs this updater-capable
version once; subsequent compatible releases do not require another bootstrapper download.

Release publishers must upload `UtilityClient-Update.zip` and its `.sha256` sidecar (or provide
the GitHub asset digest). The update ZIP contains `Payload.zip`, `LinuxBundle.zip` and
`update.json` with schema 1, repository/version/build and both bundle hashes. Existing releases
without this format are skipped. Building creates these artifacts locally; no release is published
automatically. Direct injector downloads also need a matching asset checksum when GitHub supplies
no digest. The Windows payload includes `package.json` with checksums for every installed
package member. Both platform release bundles include `SHA256SUMS`.

CLI maintenance uses `--health`, `--export-linux <zip>`, `--uninstall --confirm` (optionally
`--remove-settings`), `--check-updates`, or `--quiet --update-package <zip> --package-sha256
<digest>`. `--root` and `--app-dir` select dedicated installation/runtime folders. Automated
installer checks use disposable workspace folders and never uninstall the real installation.

## Custom modules and themes

Open MODULES in the client menu to add custom JARs. Restart the game to load additions. The SDK subfolder includes a working HelloCustom module and Java 25 build scripts; load-report.txt records import results. Custom modules use normal settings, keybinds and profiles.

Open THEMES to select Flat, Rounded, Outlined or Glass styling. Choose Graphite, Midnight, Rose, Forest, Light or Custom colors. Selections apply immediately and persist with profiles.

New Windows setup builds check the latest verified stable update archive before installation. Earlier updater-capable setups can use CHECK UPDATES. Release publishers must provide UtilityClient-Update.zip and its .sha256 sidecar in gopirate2/utilityclients; the archive format remains compatible. Offline Package and --offline retain offline installation.
