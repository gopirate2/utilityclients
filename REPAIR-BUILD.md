# Client repair build — 2026-11-21

Built by Olivia — https://discord.gg/ojailbreaks

The Appearance module provides Flat, Rounded, Outlined and Glass styles with Graphite, Midnight, Rose, Forest, Light and custom colors. Custom module JARs load through the versioned ModuleProvider API; see CUSTOM-MODULES-AND-THEMES.md and ModuleSDK.zip.

MouseTweaks now uses the cursor position for inventory drag actions and consumes releases it owns. AntiMiss holds back attacks on empty/block crosshairs until a living entity is targeted. Aimbot tracks the center of its target with frame-based smoothing. AutoMace, AutoBreachSwap and AutoStunSlam now own their attack sequence, use actual attacks, and retain manual slot changes. Enable silentAim to keep the local camera still. AutoStunSlam requires a shield target; AutoBreachSwap requires a Breach-enchanted mace. Existing saved settings remain active.

PearlCatch waits at least two ticks (about 100 ms) between the pearl and wind charge. AutoJumpReset uses the vanilla jump before movement. SmartWTap reacts to actual attacks; Triggerbot resets sprint only after a successful attack. AutoClicker validates solid block hits before mining and sends actual attacks against shield targets; vanilla shield rules still apply.

BowAimbot predicts target movement and arrow drop. Its silent setting directs the shot when the bow is released without moving the camera. AutoXbow waits for confirmed charge, stops when ammunition or load confirmation fails, avoids replaying placement while recharging, and cancels on manual scrolling. Slot changes synchronize vanilla state. LungeSwap equips before stabbing. These changes require a server retest for the reported GrimAC flags; no server acceptance is guaranteed.

WebDrain checks the player's intersecting cobwebs, aims at validated neighboring placement faces, retrieves the source when possible and restores the owned slot. AutoDrain picks up nearby source fluid with an exact empty bucket. AutoTotem uses one offhand swap instead of cursor pickup exchanges.

HealthIndicators displays larger full/half/empty pixel hearts and golden absorption hearts. ArmorIndicators displays armor-point icons. PotionCounter groups matching potion contents with icons and counts. Zoom applies smooth camera zoom, fractional scroll and sensitivity scaling without overwriting the saved FOV. GamemodeDetector renders a dedicated alert for changes even when the regular HUD is disabled. Remote .lookup reports operator status as unknown unless the server exposes authoritative information; an integrated server can report the actual OP list.

Baritone is included as a complete startup Fabric dependency. Use INSTALL FABRIC CLIENT + BARITONE in the injector, choose the profile's mods folder, then restart Minecraft. Live injection cannot attach the structural Baritone mixins to game classes that are already loaded.

AutoCart was removed by explicit request. Other modules remain. Cleanup targets disposable setup/UI fixtures and redundant build outputs only.

## Publishing automatic updates

Publish a stable GitHub release in gopirate2/utilityclients with UtilityClient-Update.zip and UtilityClient-Update.zip.sha256. The bootstrapper selects the newest compatible stable release by publication date, downloads it over HTTPS, verifies SHA-256 and the package manifest, installs it preserving settings, and caches it for offline repair. Drafts and prereleases are skipped. Selection uses publication dates, not semantic-version comparison.

An older EXE containing this updater can fetch future compatible schema-1 packages. An EXE distributed before the updater existed must be replaced once. No public release was uploaded during this build. Public download delivery requires publishing the generated assets.
