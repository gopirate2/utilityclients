package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The startup mixin entry points, installed and verified in an already-running game. */
final class BridgePatcher {
    private static final String BRIDGE = "com/baggute/cheatclient/LateBridge";
    private static final Map<Instrumentation, BridgeTransformer> ACTIVE = new IdentityHashMap<>();

    private record Site(String owner, String method, String descriptor, String callback,
                        String callbackDescriptor, String argument, boolean head, String wovenMarker) {}

    private static final List<Site> SITES = List.of(
            new Site("net/minecraft/client/gui/Hud", "extractRenderState",
                    "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                    "onHudRender", "(Ljava/lang/Object;)V", "GuiGraphicsExtractor", false, "utilityclient$onHudRender"),
            new Site("net/minecraft/client/renderer/LevelRenderer", "submitFeatures",
                    "(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
                    "onSubmitFeatures", "(Ljava/lang/Object;)V", "LevelRenderState", true, "utilityclient$onSubmitFeatures"),
            new Site("net/minecraft/client/Minecraft", "renderFrame", "(Z)V",
                    "onRenderFrame", "()V", null, true, "utilityclient$onRenderFrame"),
            new Site("net/minecraft/client/Minecraft", "tick", "()V",
                    "onClientTick", "()V", null, false, "utilityclient$onClientTick"),
            new Site("net/minecraft/client/KeyboardHandler", "keyPress",
                    "(JILnet/minecraft/client/input/KeyEvent;)V",
                    "onKeyPress", "(Ljava/lang/Object;I)Z", "KeyEvent", true, "utilityclient$onKeyPress"));

    private BridgePatcher() {}

    static synchronized int patch(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            Relay.log("bridge", "this JVM does not support runtime hooks");
            return 0;
        }
        ClassLoader gameLoader = null;
        boolean found = false;
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.getName().equals("net.minecraft.client.Minecraft")) {
                gameLoader = type.getClassLoader(); found = true; break;
            }
        }
        if (!found || !HookLinkage.ready(gameLoader)) return 0;

        BridgeTransformer transformer = ACTIVE.get(inst);
        if (transformer == null) {
            transformer = new BridgeTransformer(gameLoader);
            inst.addTransformer(transformer, true);
            ACTIVE.put(inst, transformer);
        }
        transformer.verified.clear();
        Set<String> owners = new LinkedHashSet<>();
        for (Site site : SITES) owners.add(site.owner());
        for (String owner : owners) {
            try {
                Class<?> type = Class.forName(owner.replace('/', '.'), false, gameLoader);
                if (type.getClassLoader() != gameLoader || !inst.isModifiableClass(type))
                    throw new IllegalStateException("wrong loader or unmodifiable class");
                transformer.verified.remove(owner);
                inst.retransformClasses(type);
            } catch (Throwable failure) {
                transformer.verified.remove(owner);
                Relay.log("bridge", owner.replace('/', '.') + " refused: " + failure);
            }
        }
        int available = transformer.available().size();
        Relay.log("bridge", available + " of " + SITES.size() + " hooks verified"
                + (available == SITES.size() ? "" : "; missing " + missing(inst)));
        return available;
    }

    static synchronized boolean ready(Instrumentation inst) {
        BridgeTransformer transformer = ACTIVE.get(inst);
        return transformer != null && transformer.available().size() == SITES.size();
    }

    static synchronized List<String> missing(Instrumentation inst) {
        BridgeTransformer transformer = ACTIVE.get(inst);
        Set<String> available = transformer == null ? Set.of() : transformer.available();
        return SITES.stream().filter(site -> !available.contains(site.callback()))
                .map(site -> site.owner().replace('/', '.') + "#" + site.method()).toList();
    }

    private static final class BridgeTransformer implements ClassFileTransformer {
        private final ClassLoader gameLoader;
        private final Map<String, Set<String>> verified = new ConcurrentHashMap<>();

        BridgeTransformer(ClassLoader gameLoader) { this.gameLoader = gameLoader; }

        Set<String> available() {
            Set<String> out = new HashSet<>();
            verified.values().forEach(out::addAll);
            return out;
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
            if (className == null || loader != gameLoader
                    || SITES.stream().noneMatch(site -> site.owner().equals(className))) return null;
            try {
                byte[] rewritten = rewrite(className, bytes);
                byte[] result = rewritten == null ? bytes : rewritten;
                if (!HookLinkage.visible(loader, className, result)) return null;
                Set<String> matches = matchingSites(className, result);
                verified.put(className, matches);
                for (String callback : matches) Relay.log("bridge", className + " -> LateBridge." + callback);
                return rewritten;
            } catch (Throwable failure) {
                verified.remove(className);
                Relay.log("bridge", "failed on " + className + ": " + failure);
                return null;
            }
        }
    }

    /** Also used by regressions against the actual supported Minecraft bytecode. */
    static byte[] rewrite(String owner, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean touched = false;
        for (Site site : SITES) {
            if (!site.owner().equals(owner)) continue;
            for (MethodNode method : node.methods) {
                if (!matches(method, site) || hasHook(node.name, method, site)) continue;
                if (site.head()) method.instructions.insert(buildCall(method, site));
                else {
                    boolean returned = false;
                    for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                        if (instruction.getOpcode() == Opcodes.RETURN) {
                            method.instructions.insertBefore(instruction, buildCall(method, site));
                            returned = true;
                        }
                    }
                    if (!returned) continue;
                }
                touched = true;
            }
        }
        if (!touched) return null;
        // The keyboard's new branch has a SAME frame at the original method body.
        // Do not resolve game types to recompute frames or add methods or fields.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static Set<String> matchingSites(String owner, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> matches = new HashSet<>();
        for (Site site : SITES) if (site.owner().equals(owner))
            for (MethodNode method : node.methods) if (matches(method, site) && hasHook(node.name, method, site))
                matches.add(site.callback());
        return matches;
    }

    private static boolean matches(MethodNode method, Site site) {
        return method.name.equals(site.method()) && method.desc.equals(site.descriptor())
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && method.instructions != null && method.instructions.size() > 0;
    }

    private static boolean hasHook(String owner, MethodNode method, Site site) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.getOpcode() == Opcodes.INVOKESTATIC && BRIDGE.equals(call.owner)
                    && site.callback().equals(call.name) && site.callbackDescriptor().equals(call.desc)) return true;
            if (owner.equals(call.owner) && call.name.contains(site.wovenMarker())) return true;
        }
        return false;
    }

    private static InsnList buildCall(MethodNode method, Site site) {
        InsnList call = new InsnList();
        if (site.argument() != null) {
            int slot = slotOf(method, site.argument());
            if (slot < 0) throw new IllegalArgumentException("missing " + site.argument());
            call.add(new VarInsnNode(Opcodes.ALOAD, slot));
            if (site.callback().equals("onKeyPress")) call.add(new VarInsnNode(Opcodes.ILOAD, slot - 1));
        }
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, site.callback(), site.callbackDescriptor(), false));
        if (site.callback().equals("onKeyPress")) {
            LabelNode body = new LabelNode();
            call.add(new JumpInsnNode(Opcodes.IFEQ, body));
            call.add(new InsnNode(Opcodes.RETURN));
            call.add(body);
            call.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        return call;
    }

    private static int slotOf(MethodNode method, String simpleName) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getClassName().endsWith(simpleName)) return slot;
            slot += argument.getSize();
        }
        return -1;
    }
}
