Built by Olivia — https://discord.gg/ojailbreaks

# UtilityClient management features — completed

20 November 2026. Project: `C:/Users/shahl/Desktop/importnat/utilityclient`.

All six requested additions and the earlier requirement for future updates through the same bootstrapper are implemented, compiled and locally packaged. The earlier injector crash, activation, HUD and AutoPearlCatch repairs are retained.

## Added behavior

| Addition | Access | Result |
| --- | --- | --- |
| Profile comparison | Client menu → Presets or Import | Reviews current/incoming enable flags, settings, primary/action keybinds and friends before Apply. Cancel/Escape leaves settings alone. Copy Diff and row copying preserve full values. |
| Failure recovery instructions | Injector GUI errors and console errors | Gives specific next steps for incompatible/loaded classes, missing hooks, exited processes, privileges, disabled attach, missing runtimes, timeouts, locks and download failures. Technical errors remain available. |
| Client integrity verification | Automatic before installation/attachment; CLI `--verify` | Checks SHA-256, ZIP entry CRCs/sizes, mod identity, entrypoint bytecode, declared resources and all 24 public/static helper contracts. The target JVM checks the file again before loading. |
| Installation health page | Bootstrapper → Health | Checks recorded program/launcher hashes, JAR identities, runtime files/modules, helper contracts and owned shortcut targets. Copy Report and Repair are available. A changed injector is rejected before execution. |
| Uninstall choices | Bootstrapper → Uninstall | Defaults to keeping settings, friends, profiles, logs and backups. The second choice removes those managed data paths too. Unrelated files, other installs and the retained setup are preserved. Removal and owned registration/shortcut changes roll back on failure. |
| Linux bundle export | Bootstrapper → Export Linux | Exports a verified Linux ZIP and `.sha256` file. Includes the Linux client, shared injector, shell/Python helpers, instructions, third-party notices/source and member checksums. Rejects managed-folder targets, unsafe paths, linked outputs and Windows binaries/native DLL. |
| Same-bootstrapper updates | Bootstrapper → Check Updates or Local Update | Selects a usable stable release, verifies the update archive, then uses the normal staging/rollback installer while keeping settings. Retains setup and the last installed update for later updates and offline repair. |

Profile review holds a detached, validated snapshot. Partial/legacy keys and bounded numeric values are shown as they will actually apply; unknown modules/settings are marked ignored. If live settings changed during review, Apply refreshes the comparison and requires another Apply. Friend names/UUIDs are normalized for comparison. A raw preset is explicitly distinguished from a share-code envelope, fixing the earlier partial import's silent no-op risk.

Downloaded clients require GitHub's SHA-256 asset digest or a published checksum sidecar. Invalid downloads preserve the existing cache. An offline local JAR without an expected digest receives structural verification and its own displayed hash; that alone does not establish publisher authenticity. Recovery guidance does not automatically retry attachment.

Health metadata records installed program files and generated launchers separately from personal settings. Older installs without that record need one Repair to establish checksums/ownership. Uninstall tests use disposable installations; the real installation was not removed or modified for these features.

The Linux installer, generated launcher and Linux source setup now find the actual Java version line even when `JAVA_TOOL_OPTIONS` prints a banner first. That prevents a valid Java 25 runtime from being rejected solely because options were set.

## Downloads and use

- [Windows setup](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Setup.exe) · [checksum](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Setup.exe.sha256)
- [Linux bundle](C:/Users/shahl/Desktop/importnat/utilityclient/build/dist/UtilityClient-Linux.zip) · [checksum](C:/Users/shahl/Desktop/importnat/utilityclient/build/dist/UtilityClient-Linux.zip.sha256)
- [Reusable-bootstrapper update package](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Update.zip) · [checksum](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Update.zip.sha256)

Run the setup to install/update the local client and injector. Restart Minecraft fully before attaching this rebuilt client, because classes already loaded into a game JVM cannot be replaced safely by overwriting a JAR. Target: Minecraft 26.2 with Fabric/Fabric API and Java 25. The Windows setup is x64 and requires .NET Framework 4.8.

Send the Linux ZIP **and its `.sha256` file** to a Linux user. After extraction, run `sha256sum -c SHA256SUMS`, then `bash install.sh`. Linux needs Bash, Python 3.8+, a graphical session and Java 25 with `java.desktop`/`jdk.attach`; its helper can provision a private verified runtime. It does not contain the Windows runtime or native DLL.

## CLI examples

From the appropriate package/installation directory:

```text
java -jar injector.jar --verify cheatclient.jar
java -jar injector.jar --verify cheatclient.jar --sha256 <expected-64-hex-digest>
UtilityClient-Setup.exe --health
UtilityClient-Setup.exe --export-linux "C:\chosen-folder\UtilityClient-Linux.zip"
UtilityClient-Setup.exe --check-updates
UtilityClient-Setup.exe --quiet --update-package "C:\chosen-folder\UtilityClient-Update.zip" --package-sha256 <expected-64-hex-digest>
UtilityClient-Setup.exe --uninstall --confirm
UtilityClient-Setup.exe --uninstall --confirm --remove-settings
```

The uninstall commands remove the selected installation. `--root`, `--app-dir` and `--shortcut-dir` select dedicated paths for isolated testing. Keep the second uninstall choice explicit when removing settings.

## Release contract for future updates

The configured repository is `gopirate2/utilityclients`. Check Updates skips drafts/prereleases and releases without a usable update package and trusted checksum. A published `UtilityClient-Update.zip` contains:

```text
Payload.zip
LinuxBundle.zip
update.json
```

`update.json` uses schema 1 and identifies the repository, version, build and SHA-256 of both bundles. Publish the update ZIP plus `UtilityClient-Update.zip.sha256`, or supply GitHub's asset digest. Both compatible client/injector and runtime/package changes travel through that archive. The setup keeps its reusable entry point and last installed update under `%LOCALAPPDATA%/UtilityClient/bootstrapper` by default; Offline Package selects the original embedded payload.

Existing old bootstrappers need this updater-capable version once. Later compatible releases use the same bootstrapper. These assets were generated locally; nothing was published online, and a real release-server download/update was not tested.

## Validation

- Compiled all production sources: Windows client **237 sources / 312 classes**, Linux client **238 / 314**, injector **26 / 81**; Windows setup compiled successfully.
- Windows and Linux-source client suites passed: profile comparison/import regressions, all **119 module activation routes**, **8 action bindings**, AutoXbow/Lunge/HUD repairs, and **1,454 moving-pearl intercepts + 1,282 impossible angles**.
- Injector suite passed: corruption/identity/helper/checksum cases, failure recovery/progress, runtime/bridge patch verification, original missing-GameplayHooks crash reproduction, actual Knot registration, isolated-JVM camera survival and repeat attachment.
- The **13 management integration cases** passed: update discovery/cache, health/repair, Linux export/member hashes and transactional ZIP/sidecar replacement, both uninstall choices, real Windows file locks and late registration rollback.
- All **14 broad installer cases** passed against the published files, including Windows install/launcher/rollback, Linux shell install and launch, unpacked/source delegation, argument failures and missing-runtime behavior.
- Actual WinForms install, progress, configuration preservation and launcher enablement were checked. Normal and minimum-size previews were visually inspected; the maintenance toolbar wraps within the minimum window.
- Published packages passed exact compiled-class bytes, source fingerprints, embedded resources, platform-native distinctions, manifest/member hashes, nested archive CRCs, update ZIP and refreshed source snapshots.

The custom Java compiler and C# compiler produced the local artifacts. Normal Gradle/jlink scripts were updated but were not rerun. The existing verified private Java 25 runtime was reused.

The Windows workspace sandbox denies Java 25 native canonicalization of the extracted runtime's `conf/security/java.security`. Isolated installer tests therefore used a test-process-only Java-home override to the installed JDK. Production code was not weakened. The private runtime's real module list was separately checked without the override. Windows-hosted Bash fixtures used project-relative installation roots to avoid denied ancestor traversal.

Live Minecraft gameplay and execution on a native Linux system remain untested. Linux Java sources and shell installation were exercised on the Windows host. Baritone still requires Fabric startup loading and is not represented as fully working through late injection.

## Final artifact identities

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| [build/bootstrapper/UtilityClient-Setup.exe](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Setup.exe) | 57887744 | `db51dc943ef43752630f88b26660c11ddaa48ac1069fc26db023ad52e90c99a6` |
| [build/dist/UtilityClient-Linux.zip](C:/Users/shahl/Desktop/importnat/utilityclient/build/dist/UtilityClient-Linux.zip) | 12248851 | `9a26c406eab42832be5efed68e73bab2c28c75b68ec655c4b58cd688ab9dc660` |
| [build/bootstrapper/UtilityClient-Update.zip](C:/Users/shahl/Desktop/importnat/utilityclient/build/bootstrapper/UtilityClient-Update.zip) | 57798810 | `b6bace2578e4fd2326a410aad58165df168c2f54698aba14d14b718eeffe64c1` |
| [build/dist/UtilityClient-1.0.0.zip](C:/Users/shahl/Desktop/importnat/utilityclient/build/dist/UtilityClient-1.0.0.zip) | 12507044 | `9f0a2f537950c2a0dfadbc6db7f94835f06a9a4bc6c13706e65f1f21d2fbe5f4` |
| [build/libs/cheatclient-1.0.0.jar](C:/Users/shahl/Desktop/importnat/utilityclient/build/libs/cheatclient-1.0.0.jar) | 9253258 | `e6c1e94f808fc82369cb04ad607a1d703f7e0b7befc06bfbad70c62b9859b8fa` |
| [linux/build/libs/cheatclient-1.0.0.jar](C:/Users/shahl/Desktop/importnat/utilityclient/linux/build/libs/cheatclient-1.0.0.jar) | 8997630 | `a455a404c5a60835e18f3c32fadf739ef66cab651018c8883f61068f65cb2999` |
| [injector/build/libs/injector.jar](C:/Users/shahl/Desktop/importnat/utilityclient/injector/build/libs/injector.jar) | 2722606 | `1f6302380455253e28c8f66755c66f75f6dcee21f533e33cea693b98125dd62e` |

Checksums beside the setup, Linux export and update ZIP match these bytes. Prior sources/setup are backed up under `utilityclient-repair/management-features/before`; earlier publication originals remain under `utilityclient-repair/current-fixes/before`.

The detailed current continuation context is [FEATURE-WORK-SUMMARY.md](C:/Users/shahl/Desktop/importnat/utilityclient/FEATURE-WORK-SUMMARY.md). The original paused checkpoint remains in [FEATURE-WORK-SUMMARY-PRE-IMPLEMENTATION.md](C:/Users/shahl/Desktop/importnat/utilityclient/FEATURE-WORK-SUMMARY-PRE-IMPLEMENTATION.md) as historical context.
