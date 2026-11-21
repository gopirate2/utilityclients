package com.baggute.cheatclient.api;

import com.baggute.cheatclient.module.Module;
import java.util.Collection;

/** Drop-in JAR entry point. Do not bundle client/Minecraft classes in your JAR. */
public interface ModuleProvider {
    default int apiVersion() { return 1; }
    Collection<? extends Module> createModules();
}
