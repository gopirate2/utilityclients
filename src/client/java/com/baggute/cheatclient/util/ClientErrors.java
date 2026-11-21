package com.baggute.cheatclient.util;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.testing.ModuleStats;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keep callback failures visible without flooding the game log every frame. */
public final class ClientErrors {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ClientErrors() {}

    public static void report(String callback, Throwable failure) {
        int separator = callback.indexOf('/');
        try { ModuleStats.error(separator < 0 ? callback : callback.substring(0, separator), failure); }
        catch (Throwable ignored) {}
        if (!REPORTED.add(callback + ":" + failure.getClass().getName())) return;
        try { UtilityClient.LOGGER.error("[UtilityClient] {} failed", callback, failure); }
        catch (Throwable ignored) {}
        try { com.baggute.cheatclient.stealth.LogManager.log("ERROR", callback + " failed", failure); }
        catch (Throwable ignored) {}
    }
}
