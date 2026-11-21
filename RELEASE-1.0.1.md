UtilityClient 1.0.1 updates inventory commands, placement timing, indicators and movement controls.

| Change | Behavior |
| --- | --- |
| LungeSwap | Charges with the fast-hand attributes, then selects the spear and stabs before the next equipment update; returns to the fast slot. |
| Kit load | Replaces all 41 slots and clears unspecified slots in creative mode or the owned singleplayer world. Remote survival servers must authorize item creation. |
| `.drop 1/10/100/all <item>` | Drops the requested quantity across inventory stacks using normal inventory actions. `held` selects the held item type. |
| `.uuid <player>` | Copies the server-listed UUID, or resolves a Java account name through Mojang when it is absent from the player list. |
| `.potion str1/str2/speed1/invis` | Opens a brewing guide with ingredient order, stronger and extended variants. `strength8`, `invis8` and `long` select the extended recipe; strength/speed/invisibility extend to eight minutes. Stronger potions cannot also be extended. |
| Potion counter | Keeps potion variants separate and prints the level inside each icon, with the quantity beside it. |
| GamemodeDetector | Ignores your own changes and reports changes for other players. |
| AutoInstacart | Arms from an actual vanilla bow-release call, including partial draws that fire an arrow. Waits four ticks by default before choosing the crosshair block, then places without steering the camera. `shotDelay` is configurable. |
| FastCart | Places without aim assist or camera steering. |
| AutoAlienWC | Requires an equipped elytra, active gliding and downward movement. Throws straight down, shows a one-tick downward snap, restores the view and returns the hotbar slot. |
| Flight | Neutral vertical input hovers; jump and shift control ascent/descent. Original gravity state is restored when disabled. |
| Health/armor indicators | Read current values each render and interpolate positions between ticks. Each has a Players Only switch; disabling it includes mobs. |
| Themes | Appearance preferences are hidden from module lists and opened from the bottom-right Themes button. |
| Baritone | Loads as a separate Fabric mod with normal `#` commands. The injector's startup install extracts its sidecar; late injection stages it for the next restart. Existing standalone copies are preserved. No Baritone module entry remains. |
| SeedCrackerX | The command service remains available; its module-list entry is hidden. |
| Terminal `version` | Prints `1.0.1`; mod metadata and installer package manifests use the same version from Gradle. |

Regression coverage includes fast-hand Lunge timing, exact drop quantities, replacement kits, partial-draw cart delay, potion levels and recipes, other-player gamemode detection, neutral flight, wind-charge view restoration and standalone Baritone installation.
