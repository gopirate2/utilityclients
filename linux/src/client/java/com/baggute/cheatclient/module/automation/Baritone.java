package com.baggute.cheatclient.module.automation;

import baritone.api.BaritoneAPI;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.settings.StringSetting;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.util.ChatCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;

/** Control for the complete Baritone implementation nested in cheatclient.jar. */
public final class Baritone extends Module {
    private final StringSetting command = new StringSetting("command", "Command to run on enable", "#goto 0 0");
    private final BoolSetting sendChatFeedback = new BoolSetting("sendChatFeedback", "Send Chat Feedback: include Baritone's automatic logger messages", false);
    private static java.util.function.Consumer<net.minecraft.network.chat.Component> underlyingLogger;
    private static final java.util.function.Consumer<net.minecraft.network.chat.Component> FILTERED_LOGGER = message -> {
        if (com.baggute.cheatclient.util.FeedbackPolicy.enabled("Baritone") && underlyingLogger != null) underlyingLogger.accept(message);
    };
    private boolean running;
    public Baritone() {
        super("Baritone", "Bundled Baritone 1.19.0 for Minecraft 26.2. Runs the configured command.", Category.AUTOMATION);
        addSetting(command);
        addSetting(sendChatFeedback);
    }
    @Override protected void onEnable() {
        running = false;
        if (!FabricLoader.getInstance().isModLoaded("baritone")) {
            feedback("[Baritone] Bundled, but requires startup loading: put cheatclient.jar in this Fabric profile's mods folder and restart Minecraft.");
            setEnabled(false);
            return;
        }
        try {
            installChatFilter();
            String input = command.get().strip();
            if (input.startsWith("#")) input = input.substring(1).strip();
            if (input.isEmpty() || !BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(input)) {
                feedback("[Baritone] Command was not recognized: " + input);
                setEnabled(false);
                return;
            }
            running = true;
        } catch (RuntimeException | LinkageError failure) {
            UtilityClient.LOGGER.error("[Baritone] Bundled integration failed", failure);
            feedback("[Baritone] Initialization failed: " + failure.getClass().getSimpleName());
            setEnabled(false);
        }
    }
    private void feedback(String message) {
        com.baggute.cheatclient.module.misc.Notifications.push(message, false);
        if (sendChatFeedback.get()) ChatCommands.say(message, ChatFormatting.RED);
    }
    public static void installChatFilter() {
        if (!FabricLoader.getInstance().isModLoaded("baritone")) return;
        try {
            var settings = BaritoneAPI.getSettings();
            if (settings.logger.value == FILTERED_LOGGER) return;
            underlyingLogger = settings.logger.value;
            settings.logger.value = FILTERED_LOGGER;
        } catch (RuntimeException | LinkageError failure) {
            UtilityClient.LOGGER.warn("Could not configure Baritone chat feedback", failure);
        }
    }
    @Override public void onConfigLoaded() { installChatFilter(); }
    @Override protected void onDisable() {
        if (!running) return;
        running = false;
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
    }
    /** Cache packer/saver tasks run forever upstream; stop them only on game exit. */
    public static void shutdownWorkers() {
        if (!FabricLoader.getInstance().isModLoaded("baritone")) return;
        try {
            baritone.Baritone.shutdown();
        } catch (RuntimeException | LinkageError failure) {
            UtilityClient.LOGGER.warn("Could not stop Baritone workers during game shutdown", failure);
        }
    }
}
