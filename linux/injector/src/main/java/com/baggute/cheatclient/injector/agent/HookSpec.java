package com.baggute.cheatclient.injector.agent;

import java.util.Locale;

/**
 * {@code a.b.C#method} or {@code a.b.C} (every method). Parsed on both sides so
 * the UI can reject a bad spec before it ever reaches a target.
 */
public record HookSpec(String className, String methodName) {

    public static HookSpec parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("empty hook");

        String type = trimmed;
        String method = "*";
        int hash = trimmed.indexOf('#');
        if (hash > 0) {
            type = trimmed.substring(0, hash).trim();
            method = trimmed.substring(hash + 1).trim();
            if (method.isEmpty()) method = "*";
        }
        if (type.isEmpty()) throw new IllegalArgumentException("missing class name in " + text);
        return new HookSpec(type, method);
    }

    /** {@code a.b.C} as the class file spells it. */
    public String internalName() {
        return className.replace('.', '/');
    }

    public boolean targetsEverything() {
        return "*".equals(methodName);
    }

    /** Stable key used to avoid installing the same hook twice. */
    public String key() {
        return internalName() + "#" + methodName;
    }

    public String display() {
        return className + "#" + methodName;
    }

    /** True when the transformer should rewrite this method. */
    public boolean matchesMethod(String name) {
        return targetsEverything() || methodName.equals(name);
    }

    /** `<init>` and `<clinit>` are skipped: a hook call there can run before linkage settles. */
    public static boolean isHookableMethod(String name, int access, boolean concrete) {
        if (!concrete) return false;
        if (name.startsWith("<")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith("lambda$");
    }
}
