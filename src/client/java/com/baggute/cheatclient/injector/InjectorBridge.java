// language: Java, file: InjectorBridge.java, target: Fabric 26.2
package com.baggute.cheatclient.injector;

/**
 * Shared hand-off between the external loader and the running client.
 *
 * <p>{@link InjectorMain} runs as a normal JVM, attaches to a Minecraft process
 * and loads {@link ClientAgent} into it. The agent cannot touch this class
 * directly (it lives in the system classloader, the client lives in Knot's), so
 * it looks the class up through the game's loader and calls {@link #mark(String)}
 * reflectively. If the client was started normally through Fabric, the system
 * property is absent and the status simply reads "fabric".
 */
public final class InjectorBridge {

    /** System property set by the agent so early client code can see it too. */
    public static final String INJECTED_PROPERTY = "utilityclient.injected";
    public static final String MODE_PROPERTY = "utilityclient.injected.mode";

    private static volatile boolean attached;
    private static volatile String mode = "fabric";
    private static volatile long stamp;

    private InjectorBridge() {}

    /** Called reflectively by the agent once it finds the live client class. */
    public static void mark(String how) {
        attached = true;
        mode = how == null || how.isBlank() ? "agent" : how;
        stamp = System.currentTimeMillis();
    }

    /** True when an agent attached to this JVM (or the JVM was started by one). */
    public static boolean isInjected() {
        return attached || Boolean.getBoolean(INJECTED_PROPERTY);
    }

    public static String mode() {
        return mode;
    }

    public static long stampedAt() {
        return stamp;
    }

    /** Short label for the GUI ("injected" / "fabric"). */
    public static String label() {
        return isInjected() ? "injected" : "fabric";
    }

    /** Longer description for the footer hint. */
    public static String describe() {
        return isInjected()
                ? "agent attached (" + mode + ")"
                : "loaded through the Fabric loader";
    }
}
