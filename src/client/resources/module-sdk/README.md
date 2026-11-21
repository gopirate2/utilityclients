# UtilityClient module SDK — API 1

Open **MODULES** in the client menu. Put custom `.jar` files directly in that folder and restart the client. The SDK folder contains a complete working example. `load-report.txt` records successful imports and errors; a broken JAR does not stop other imports.

Custom modules appear in their selected category. UtilityClient adds a toggle keybind, saves their settings in profiles, and calls their normal Module hooks. Module names must be unique, including built-in names. JARs execute inside the game process: install extensions from authors you trust.

## Build the example

Install a Java 25 JDK. From this SDK folder, run `./build.ps1` on Windows or `sh ./build.sh` on Linux. The script finds the installed client JAR three directories above this folder; for another layout, supply its path. It builds `../HelloModules.jar`. Restart UtilityClient, search for **HelloCustom**, set its keybind, and toggle it. The example uses a local notification and sends no server chat.

Windows: `./build.ps1 -ClientJar C:\path\cheatclient.jar`

Linux: `sh ./build.sh /path/to/cheatclient.jar`

## Write a module

Implement `com.baggute.cheatclient.api.ModuleProvider`, return API version 1, and return your Module subclasses from `createModules()`. The service file `META-INF/services/com.baggute.cheatclient.api.ModuleProvider` lists your provider's fully qualified class name. Each provider needs a public no-argument constructor.

Extend `com.baggute.cheatclient.module.Module`, set a unique name/description/category, and add settings in the constructor. Supported settings include BoolSetting, IntSetting, DoubleSetting, ColorSetting, StringSetting, ChoiceSetting, and KeybindSetting. Override onEnable/onDisable, onTick, onKey, onRawKey, onInputSuspended, onRenderFrame, or onRender3D as required. Hooks run on the client thread; keep them brief and release owned resources in onDisable.

Compile against the matching UtilityClient JAR; do not include UtilityClient, Minecraft, Fabric, or shared API classes in your JAR. Modules using Minecraft APIs also require the matching mapped game/dependency classpath, supplied through the build script's extra classpath argument. API version 1 is stable for this release family; game APIs still depend on the game's version.

Each JAR may contain up to 128 modules and 16 providers; each module may have up to 64 settings. At most 64 JARs load, each at most 16 MiB. Conflicting or invalid module batches are rejected together. Replacing a JAR takes effect after restarting the client.
