package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a method body so entry and exit are announced on the log channel.
 *
 * <p>Two instructions are added, nothing is removed: an {@code LDC} of the hook
 * label plus {@code INVOKESTATIC AgentHooks.enter}, and the same for
 * {@code exit} before every return opcode. Max stack is recomputed because the
 * added {@code LDC} pushes a value the original frame did not account for.
 *
 * <p>Before rewriting, the payload is resolved through the target's own loader.
 * If that fails the class is left alone — this is the guard that stops a
 * "worked in testing, crashed the game" hook: a class that cannot link the call
 * would throw NoClassDefFoundError on its first invocation, inside Minecraft.
 */
final class HookTransformer implements ClassFileTransformer {

    private static final String HOOKS_OWNER =
            "com/baggute/cheatclient/injector/agent/AgentHooks";
    private static final String HOOKS_CLASS = "com.baggute.cheatclient.injector.agent.AgentHooks";
    private static final String HOOKS_DESCRIPTOR = "(Ljava/lang/String;)V";

    private final Set<String> targets = new LinkedHashSet<>();
    private final Set<String> warned = new LinkedHashSet<>();
    private final Set<String> installed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Set<String> installedKeys() { return Set.copyOf(installed); }
    private final List<HookSpec> specs;

    HookTransformer(List<HookSpec> specs) {
        this.specs = List.copyOf(specs);
        for (HookSpec spec : specs) {
            this.targets.add(spec.internalName());
        }
    }

    Set<String> classNames() {
        Set<String> names = new LinkedHashSet<>();
        for (HookSpec spec : specs) {
            names.add(spec.className());
        }
        return names;
    }

    /**
     * True when {@code loader} can see the bootstrap payload. Knot refuses classes
     * that sit in an undeclared system-path jar, so this is not the same question
     * as "is it on a classpath somewhere".
     */
    static boolean payloadVisible(ClassLoader loader) {
        ClassLoader probe = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try {
            Class.forName(HOOKS_CLASS, false, probe);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (className == null || !targets.contains(className)) return null;
        // Never touch ourselves: the payload would call into a half-built transformer.
        if (className.startsWith("com/baggute/cheatclient/injector/")) return null;

        if (!payloadVisible(loader)) {
            warnOnce(className, "skipped " + className
                    + ": AgentHooks is not visible from its loader");
            return null;
        }

        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new Rewriter(writer, className), 0);
            return writer.toByteArray();
        } catch (Throwable failure) {
            warnOnce(className, "failed to rewrite " + className + ": " + failure);
            return null;
        }
    }

    private void warnOnce(String className, String message) {
        synchronized (warned) {
            if (!warned.add(className)) return;
        }
        Relay.log("hook", message);
    }

    private final class Rewriter extends ClassVisitor {

        private final String internalName;

        Rewriter(ClassVisitor delegate, String internalName) {
            super(Opcodes.ASM9, delegate);
            this.internalName = internalName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (delegate == null) return null;

            boolean concrete = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            if (!HookSpec.isHookableMethod(name, access, concrete) || !wanted(name)) {
                return delegate;
            }

            for (HookSpec spec : specs) {
                if (spec.internalName().equals(internalName) && spec.matchesMethod(name)) installed.add(spec.key());
            }
            String label = internalName.replace('/', '.') + "#" + name;
            return new MethodVisitor(Opcodes.ASM9, delegate) {

                @Override
                public void visitCode() {
                    super.visitCode();
                    Relay.log("hook", "instrumented " + label);
                    emit("enter", label);
                }

                @Override
                public void visitInsn(int opcode) {
                    if (isReturn(opcode)) {
                        emit("exit", label);
                    }
                    super.visitInsn(opcode);
                }

                private void emit(String hook, String where) {
                    super.visitLdcInsn(where);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_OWNER, hook,
                            HOOKS_DESCRIPTOR, false);
                }
            };
        }

        private boolean wanted(String methodName) {
            for (HookSpec spec : specs) {
                if (spec.internalName().equals(internalName) && spec.matchesMethod(methodName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }
}
