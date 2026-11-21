package com.baggute.cheatclient.util;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.settings.BoolSetting;

/** Automatic feedback is opt-in; consult the live value when queued messages are delivered. */
public final class FeedbackPolicy {
    private FeedbackPolicy() {}
    public static boolean enabled(String name) {
        if (UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return false;
        var module = UtilityClient.moduleManager.getByName(name);
        if (module == null || !module.isEnabled()) return false;
        for (var setting : module.getPersistentSettings())
            if (setting instanceof BoolSetting value && setting.getName().equals("sendChatFeedback")) return value.get();
        return false;
    }
}
