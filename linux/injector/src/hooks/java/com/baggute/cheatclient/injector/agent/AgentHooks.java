package com.baggute.cheatclient.injector.agent;

/**
 * Runtime end of an injected hook, loaded by the <b>bootstrap</b> classloader.
 *
 * <p>{@link HookTransformer} rewrites a method body so its first instruction is
 * {@code AgentHooks.enter("a.b.C#m")} and every return is preceded by
 * {@code AgentHooks.exit(...)}. The rewritten class resolves these calls through
 * its own loader, and bootstrap is the one place every loader can see — including
 * Fabric's Knot, which refuses classes that live in system-classpath jars.
 *
 * <p>Both methods must stay public, static, and exactly
 * {@code (Ljava/lang/String;)V} — the transformer emits that descriptor.
 *
 * <p>This class lives in the {@code hooks} source set and is shaded into
 * {@code bootstrap/hooks.jar} inside injector.jar. It must never appear as a
 * top-level entry of injector.jar, or Knot starts refusing the load again.
 * It also may not reference any non-JDK type: bootstrap sees java.base and
 * nothing else.
 */
public final class AgentHooks {

    private AgentHooks() {
    }

    public static void enter(String where) {
        AgentLog.log("->", where + " " + Thread.currentThread().getName());
    }

    public static void exit(String where) {
        AgentLog.log("<-", where + " " + Thread.currentThread().getName());
    }
}
