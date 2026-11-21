// language: Java, file: UtilityClient.java, target: Fabric 26.2
package com.baggute.cheatclient;

import com.baggute.cheatclient.event.EventBus;
import com.baggute.cheatclient.event.events.KeyEvent;
import com.baggute.cheatclient.event.events.WorldLoadEvent;
import com.baggute.cheatclient.event.events.WorldUnloadEvent;
import com.baggute.cheatclient.gui.ClickGuiScreen;
import com.baggute.cheatclient.config.ConfigManager;
import com.baggute.cheatclient.module.ModuleManager;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import com.baggute.cheatclient.util.ChatCommands;
import com.baggute.cheatclient.util.PlayerSnapshots;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UtilityClient implements ClientModInitializer {

    public static final String MOD_ID = "utilityclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static UtilityClient INSTANCE;
    public static ModuleManager moduleManager;
    public static EventBus eventBus;

    /**
     * True when an agent loaded this class into a game that is already running.
     *
     * <p>Fabric's key-mapping registry is a load-time structure: registering into
     * it after {@code GameOptions} exists throws
     * {@code IllegalStateException: GameOptions has already been initialised}.
     * Late init therefore skips the registry entirely and reads GLFW directly.
     */
    public static final boolean LATE_INIT = Boolean.getBoolean("utilityclient.lateInit");

    /**
     * Set by {@link #unload()} — the client is inert from that point on.
     *
     * <p>A mod cannot be removed from a running Fabric loader: its classes are
     * already in Knot's loader and its mixins are already woven into the game's
     * methods. What can be removed is every effect it has — modules off, overlays
     * gone, tick/render/key entry points returning immediately — so the game runs
     * as if the client were not installed until the next launch.
     */
    private static volatile boolean unloaded;
    private static volatile boolean initialized;

    public static boolean isUnloaded() {
        return unloaded;
    }

    public static boolean isInitialized() { return initialized && !unloaded; }

    /**
     * Disables the client inside the running game. Safe to call at any time, from
     * the injector or from a module.
     *
     * <p>Ordered: modules first (so nothing keeps ticking), then the overlay
     * window, then the flag that silences every entry point.
     */
    public static void unload() {
        if (unloaded) return;
        unloaded = true;
        try {
            if (moduleManager != null) {
                // Fully qualified: an unqualified Module resolves to java.lang.Module
                // in this file, which is not what the module list holds.
                for (com.baggute.cheatclient.module.Module m : moduleManager.getModules()) {
                    try {
                        m.setEnabled(false);
                    } catch (Throwable ignored) {
                        // One stubborn module must not stop the unload.
                    }
                }
            }
        } finally {
            ContainerMouseHooks.clear();
            if (moduleManager != null) moduleManager.closeCustomModules();
            com.baggute.cheatclient.render.HudOverlay.shutdown();
            if (configManager != null) configManager.save();
            LOGGER.info("[UtilityClient] unloaded");
        }
    }

    public static final KeyMapping.Category CATEGORY = LATE_INIT ? null : KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "gui")
    );

    private KeyMapping guiKeyBinding;
    private KeyMapping terminalKeyBinding;
    private static ConfigManager configManager;

    /** Persists module state and settings. Called when the click GUI closes. */
    public static void saveConfig() {
        if (configManager != null) configManager.save();
    }

    public static ConfigManager configManager() {
        return configManager;
    }

    @Override
    public void onInitializeClient() {
        if (initialized) return;
        // First, before any other component can write a line: route this client's
        // records out of latest.log and into the hidden folder.
        com.baggute.cheatclient.stealth.LogManager.install();
        INSTANCE = this;
        eventBus = new EventBus();
        moduleManager = new ModuleManager();
        moduleManager.init();
        com.baggute.cheatclient.util.BaritoneSideload.install();
        // SeedCrackerX must load its own config before the module config is applied:
        // enabling the SeedCrackerX module calls Config.save(), which would otherwise
        // overwrite seedcracker.json with defaults that were never loaded.
        // Feature-table failures are logged individually by the embedded cracker.
        try {
            new SeedCracker().onInitialize();
        } catch (Throwable failure) {
            LOGGER.error("[UtilityClient] SeedCrackerX init failed; continuing without it", failure);
        }
        configManager = new ConfigManager(moduleManager);
        configManager.load();
        // After the config: friends.json is its own file, but the click GUI and the
        // ESP both read it on the first frame, so it has to be in memory already.
        com.baggute.cheatclient.friends.FriendsManager.load();
        com.baggute.cheatclient.stealth.ScreenBypass.init();
        // Registers the CLIENT_STARTED hook. ScreenBypass.init() above runs before
        // the window exists, so the affinity itself is applied from that event.
        com.baggute.cheatclient.stealth.StealthInitializer.init();
        if (moduleManager.getSeedCrackerX() != null) {
            moduleManager.getSeedCrackerX().setEnabled(Config.get().active);
        }

        if (!LATE_INIT) {
            // Right Ctrl is the only bind. A second registration on Right Shift
            // meant two controls owned one piece of state, so the menu opened from
            // a key the user never chose - and rebinding one left the other live.
            guiKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.utilityclient.gui",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_RIGHT_CONTROL,
                    CATEGORY
            ));
            terminalKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.utilityclient.terminal",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_GRAVE_ACCENT,
                    CATEGORY
            ));
        }

        // An agent may have marked this JVM before the client initialized.
        if (Boolean.getBoolean(com.baggute.cheatclient.injector.InjectorBridge.INJECTED_PROPERTY)) {
            com.baggute.cheatclient.injector.InjectorBridge.mark(
                    System.getProperty(com.baggute.cheatclient.injector.InjectorBridge.MODE_PROPERTY));
        }

        eventBus.subscribe(KeyEvent.class, this::onKey);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                eventBus.post(new WorldLoadEvent()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PlayerSnapshots.clear();
            moduleManager.releaseHeld();
            com.baggute.cheatclient.util.Stealth.reset();
            eventBus.post(new WorldUnloadEvent());
        });

        // Client-side chat commands. Returning false consumes the message, so a
        // leading dot behaves like a slash without ever reaching the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !ChatCommands.handle(message));

        // Brigadier front-end for the same block .version prints. Registered on the
        // client dispatcher only, so it resolves before the server's /version.
        com.baggute.cheatclient.util.VersionCommand.init();

        // MinecraftMixin and the injector's Minecraft.tick hook own ticking.
        // Registering a Fabric event here cannot install its startup mixin into
        // an already-running game, and would double-tick a game that has it.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (configManager != null) configManager.save();
            com.baggute.cheatclient.testing.TestBridge.stop();
            // The overlay is its own Win32 window; it outlives the game unless it is
            // destroyed here, leaving a transparent always-on-top square behind.
            com.baggute.cheatclient.render.HudOverlay.shutdown();
        });

        // No-op unless -Dutilityclient.bridge.port is set. Started last so the audit
        // never sees a half-initialised module list.
        com.baggute.cheatclient.testing.TestBridge.startIfRequested();
        initialized = true;

        // An injected, stealth session leaves no init line in latest.log.
        if (!Boolean.getBoolean("utilityclient.stealth")) {
            LOGGER.info("[UtilityClient] initialized");
        }
    }

    // ---- late init: no registry, so read the keyboard itself ----

    /** One end-of-tick entry point for both startup loading and injection. */
    public static void onClientTick(Minecraft client) {
        UtilityClient instance = INSTANCE;
        if (!isInitialized() || instance == null || moduleManager == null || client == null) return;
        service("held input", () -> {
            boolean suspended = client.player == null || client.gui.screen() != null || client.isPaused()
                    || !com.baggute.cheatclient.util.PhysicalInput.focused(client);
            if (suspended) {
                moduleManager.releaseHeld();
                if (!instance.inputSuspended) moduleManager.suspendInput();
            }
            instance.inputSuspended = suspended;
        });
        if (!client.isPaused()) service("module tick", moduleManager::onTick);
        service("panic tick", com.baggute.cheatclient.stealth.PanicHandler::tick);
        service("capture upkeep", () -> com.baggute.cheatclient.stealth.StealthInitializer.onTick(client));
        service("HUD upkeep", () -> com.baggute.cheatclient.stealth.ScreenBypass.tick(client));
        service("player snapshots", () -> PlayerSnapshots.tick(client));
        service("chat autocomplete", () -> com.baggute.cheatclient.util.CommandAutocomplete.tick(client));
        service("menu keys", () -> {
            if (LATE_INIT) instance.pollKeysDirectly(client);
            else {
                if (instance.guiKeyBinding != null)
                    while (instance.guiKeyBinding.consumeClick()) toggleGui();
                if (instance.terminalKeyBinding != null)
                    while (instance.terminalKeyBinding.consumeClick()) openTerminal();
            }
        });
    }

    private static void service(String name, Runnable callback) {
        try { callback.run(); }
        catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(name, failure); }
    }

    private boolean guiKeyDown;
    private boolean terminalKeyDown;
    private boolean inputSuspended;

    /**
     * Edge-detects the same defaults without a KeyMapping. Nothing here touches
     * the input registry, so it is safe to run in a game that is already up.
     */
    private void pollKeysDirectly(Minecraft mc) {
        if (mc.getWindow() == null || !com.baggute.cheatclient.util.PhysicalInput.focused(mc)) {
            guiKeyDown = terminalKeyDown = false;
            return;
        }

        boolean guiDown = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
        if (guiDown && !guiKeyDown && (mc.gui.screen() == null
                || mc.gui.screen() instanceof ClickGuiScreen)) toggleGui();
        guiKeyDown = guiDown;

        boolean terminalDown = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_GRAVE_ACCENT);
        if (terminalDown && !terminalKeyDown) openTerminal();
        terminalKeyDown = terminalDown;
    }

    private static void toggleGui() {
        Minecraft mc = Minecraft.getInstance();
        // Pressing the GUI key again while it is open closes it.
        if (mc.gui.screen() instanceof ClickGuiScreen) {
            mc.gui.setScreen(null);
            return;
        }
        // The menu always opens. Blocking it while a capture was detected sounded
        // safe until the detection matched a process that is always running (a
        // Discord client in the tray), at which point the GUI key did nothing at
        // all and the client looked dead. The HUD stays out of captures through the
        // excluded overlay window; the menu is a deliberate, visible interaction.
        mc.gui.setScreen(new ClickGuiScreen());
    }

    private static void openTerminal() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof com.baggute.cheatclient.gui.TerminalScreen)) {
            mc.gui.setScreen(new com.baggute.cheatclient.gui.TerminalScreen());
        }
    }

    private void onKey(KeyEvent event) {
        // Suppress module toggles while any screen is open — pressing a key to set
        // a keybind in the GUI would otherwise toggle every module bound to it.
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_RELEASE && (mc.gui.screen() != null || mc.isPaused())) return;
        moduleManager.onKey(event.getKey(), event.getAction());
    }
}
