# Custom modules, themes and reusable setup

Open **MODULES** in the client menu to open the extension folder. Drop module JARs directly into that folder and restart UtilityClient. The **SDK** subfolder contains build scripts and a complete HelloCustom example. Its toggle, keybind and settings use the normal menu and profile system. `load-report.txt` records import results. A standalone SDK is available at `build/dist/ModuleSDK.zip`.

The extension entry point is `com.baggute.cheatclient.api.ModuleProvider`, API version 1. Providers return Module subclasses. Names cannot collide with built-ins or other extensions. A broken or conflicting JAR is reported without preventing valid JARs from loading. The client closes its extension loaders when unloaded. Custom JARs run inside the game process; install extensions from authors you trust.

Open **THEMES** in the menu footer. Style choices are Flat, Rounded, Outlined and Glass. Color presets are Graphite, Midnight, Rose, Forest and Light. Select Custom to edit Accent, Background and Text using the existing color pickers. Changes apply immediately and persist with normal settings and profiles. The theme also colors the client's other shared menu screens.

Setup now checks the configured repository's latest verified stable release before each installation. It downloads a compatible update archive rather than relying on the executable's embedded client version. An already-cached matching release is reused. Network outages retain the verified cached or embedded package, with a log message; invalid published checksums stop installation. The Offline Package button and `--offline` select offline installation. Explicit local update packages are preserved as the selected source.

## Release publishing

Publish `build/bootstrapper/UtilityClient-Update.zip` and its `.sha256` sidecar as assets of a stable release in `gopirate2/utilityclients`. The archive retains schema 1 and contains Payload.zip, LinuxBundle.zip and update.json, so earlier updater-capable setup builds remain compatible. An old setup with **CHECK UPDATES** can download newer client releases from these assets. This build adds automatic discovery before installation; an already-distributed executable cannot gain new automatic behavior without being replaced.

Keep the update asset names and manifest schema stable across releases. The newer client, injector and required runtime travel inside the update archive. No release assets were uploaded by this local build.

## Validation

Windows and Linux client regressions cover custom JAR compilation/loading, conflicting/broken imports, profile participation, preset/custom colors and validated theme selectors. Installer policy fixtures check successive published releases, cache reuse, explicit offline/local selection, outage fallback and corrupt-update rejection. Management and UI fixtures exercise installation, repair, update rollback, Linux export, uninstall and minimum-size layout. Network publishing is a separate step; live release downloads were not verified in this run.
