package com.baggute.cheatclient.module.misc;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;

/** UtilityClient's Misc-tab control for the embedded official SeedCrackerX system. */
public final class SeedCrackerX extends Module {
    private final BoolSetting resetOnDisable = new BoolSetting("resetOnDisable", "Clear collected data when disabling", true);
    private final BoolSetting sendChatFeedback = new BoolSetting("sendChatFeedback", "Send Chat Feedback: show automatic seed-cracker messages in local chat", false);
    public SeedCrackerX() {
        super("SeedCrackerX", "Official SeedCrackerX structure seed cracker.", Category.MISC);
        addSetting(resetOnDisable);
        addSetting(sendChatFeedback);
    }

    @Override
    protected void onEnable() {
        Config.get().active = true;
        Config.save();
        UtilityClient.LOGGER.info("[SeedCrackerX] enabled from UtilityClient Misc tab");
    }

    @Override
    protected void onDisable() {
        Config.get().active = false;
        Config.save();
        if (resetOnDisable.get() && SeedCracker.get() != null) SeedCracker.get().reset();
        UtilityClient.LOGGER.info("[SeedCrackerX] disabled (reset data={})", resetOnDisable.get());
    }

    @Override
    public void onTick() {
        syncFromConfig();
    }

    public void syncFromConfig() {
        // Keep commands/config changes and the UtilityClient toggle synchronized.
        if (Config.get().active != isEnabled()) {
            setEnabled(Config.get().active);
        }
    }
}
