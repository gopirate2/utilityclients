UtilityClient 1.0.2 adds startup update checks and makes custom modules easier to install.

- Setup checks GitHub in the background when its window opens. It displays current/new release status and keeps the offline package usable when GitHub is unavailable. It installs only after selection.
- The injector checks GitHub whenever its window opens, including when a local client is already selected. Version and build identity recognize the current client. Its fetcher now supports the complete `UtilityClient-Update.zip`, verifies the platform bundle checksum, and extracts the correct Windows/Linux client.
- `.potion` opens a catalogue covering every registered vanilla potion type. All stronger/extended registry names, namespace prefixes, common aliases and splash/lingering prefixes are accepted. Luck and brewing bases have explanations rather than invented effect recipes.
- The bottom-right Modules button opens an in-game panel. Install Example provides a ready-built HelloCustom JAR without requiring a compiler. Import a custom JAR by pasting its path and pressing Enter, or open the folder and Reload. New JARs load immediately, errors are visible, and unchanged providers do not run twice. Replacing/removing loaded code requires a restart.
- The GitHub README now explains installation, updates, commands, custom-module creation/import, Baritone, themes, kits, potion levels/durations, file locations and common troubleshooting.

Custom JARs must implement the UtilityClient ModuleProvider API; ordinary Fabric mods belong in the Minecraft profile's mods folder.
