package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Verifies injected branch frames in the JVM and hook sites in the actual game jars. */
public final class RuntimePatchChecks implements Opcodes {
    private RuntimePatchChecks() {}
    private static final String HOOKS = "com/baggute/cheatclient/GameplayHooks";
    public static void run() throws Exception {
        Loader loader = new Loader();
        Class<?> hooks = loader.define(HOOKS, hooks());
        String entity = "net/minecraft/world/entity/Entity";
        byte[] original = fixture(entity, "turn", "(DD)V");
        byte[] patched = RuntimePatcher.rewrite(entity, original);
        require(patched != null, "entity turn patched");
        require(RuntimePatcher.rewrite(entity, patched) == null, "runtime patch idempotence");
        Class<?> type = loader.define(entity, patched);
        Object instance = type.getConstructor().newInstance();
        var method = type.getMethod("turn", double.class, double.class);
        method.invoke(instance, 1d, 2d);
        require(type.getField("calls").getInt(instance) == 1, "unblocked body runs");
        hooks.getField("blocked").setBoolean(null, true);
        method.invoke(instance, 1d, 2d);
        require(type.getField("calls").getInt(instance) == 1, "blocked body skipped with valid frames");
        hooks.getField("blocked").setBoolean(null, false);

        String mouse = "net/minecraft/client/MouseHandler";
        Class<?> mouseType = loader.define(mouse, RuntimePatcher.rewrite(mouse, fixture(mouse, "onScroll", "(JDD)V")));
        mouseType.getMethod("onScroll", long.class, double.class, double.class).invoke(mouseType.getConstructor().newInstance(), 1L, 2d, 3d);
        String render = "net/minecraft/client/renderer/GameRenderer";
        Class<?> renderType = loader.define(render, RuntimePatcher.rewrite(render, fixture(render, "renderItemInHand", "()V")));
        renderType.getMethod("renderItemInHand").invoke(renderType.getConstructor().newInstance());

        String input = "net/minecraft/client/player/KeyboardInput";
        Class<?> inputType = loader.define(input, RuntimePatcher.rewrite(input, fixture(input, "tick", "()V")));
        inputType.getMethod("tick").invoke(inputType.getConstructor().newInstance());
        require(hooks.getField("calls").getInt(null) == 1, "input tail callback executes");
        String living = "net/minecraft/world/entity/LivingEntity";
        Class<?> livingType = loader.define(living, RuntimePatcher.rewrite(living, fixture(living, "maxUpStep", "()F")));
        require((Float) livingType.getMethod("maxUpStep").invoke(livingType.getConstructor().newInstance()) == 1.6f,
                "step hook preserves return stack");

        // A woven startup class must stay untouched, even if it has no runtime bridge calls.
        ClassNode woven = new ClassNode(); new ClassReader(original).accept(woven, 0);
        MethodNode marker = new MethodNode(ACC_PRIVATE, "handler$abc$utilityclient$turn", "()V", null, null);
        marker.instructions.add(new InsnNode(RETURN)); marker.maxLocals = 1; woven.methods.add(marker);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); woven.accept(writer);
        require(RuntimePatcher.rewrite(entity, writer.toByteArray()) == null, "startup hooks never duplicated");
        itemUseFrames();
        checkGameSites();
        System.out.println("PASS: runtime JVM branch/return verification, idempotence, startup deduplication and actual game hook sites");
    }

    private static void checkGameSites() throws Exception {
        Map<String, List<String>> targets = Map.ofEntries(
                Map.entry("net/minecraft/world/entity/Entity", List.of("lerpMotion", "turn")),
                Map.entry("net/minecraft/world/entity/LivingEntity", List.of("knockback", "maxUpStep")),
                Map.entry("net/minecraft/client/multiplayer/ClientPacketListener", List.of("handleExplosion", "handleLogin", "handleRespawn", "handleLevelChunkWithLight", "handleMoveEntity", "handleEntityPositionSync", "handleTeleportEntity", "handleRemoveEntities")),
                Map.entry("net/minecraft/client/player/KeyboardInput", List.of("tick")),
                Map.entry("net/minecraft/client/Camera", List.of("update")),
                Map.entry("net/minecraft/client/renderer/LightmapRenderStateExtractor", List.of("extract")),
                Map.entry("net/minecraft/client/renderer/GameRenderer", List.of("renderItemInHand")),
                Map.entry("net/minecraft/client/MouseHandler", List.of("onScroll")),
                Map.entry("net/minecraft/client/multiplayer/MultiPlayerGameMode", List.of("attack", "useItem")),
                Map.entry("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", List.of("mouseClicked", "mouseDragged", "mouseReleased")));
        Map<String, byte[]> classes = new HashMap<>();
        Path cache = Path.of("..", ".gradle", "loom-cache", "minecraftMaven", "net", "minecraft");
        if (!Files.isDirectory(cache)) cache = Path.of(".gradle", "loom-cache", "minecraftMaven", "net", "minecraft");
        try (var files = Files.walk(cache)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                try (JarFile archive = new JarFile(jar.toFile())) {
                    for (String target : targets.keySet()) {
                        var entry = archive.getJarEntry(target + ".class");
                        if (entry != null && !classes.containsKey(target))
                            try (var stream = archive.getInputStream(entry)) { classes.put(target, stream.readAllBytes()); }
                    }
                }
            }
        }
        for (var entry : targets.entrySet()) {
            byte[] bytes = classes.get(entry.getKey());
            require(bytes != null, "game class found: " + entry.getKey());
            byte[] patch = RuntimePatcher.rewrite(entry.getKey(), bytes);
            require(patch != null, "game class rewritten: " + entry.getKey());
            ClassNode node = new ClassNode(); new ClassReader(patch).accept(node, 0);
            for (String name : entry.getValue()) {
                boolean hasHook = node.methods.stream().filter(m -> m.name.equals(name)).anyMatch(m -> {
                    for (AbstractInsnNode insn : m.instructions)
                        if (insn instanceof MethodInsnNode call && call.owner.startsWith("com/baggute/cheatclient/")) return true;
                    return false;
                });
                require(hasHook, "actual hook site: " + entry.getKey() + "." + name);
            }
            require(RuntimePatcher.rewrite(entry.getKey(), patch) == null, "game patch idempotence: " + entry.getKey());
        }
    }
    private static byte[] fixture(String name, String method, String desc) {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC, "calls", "I", null, null).visitEnd();
        constructor(out);
        MethodVisitor body = out.visitMethod(ACC_PUBLIC, method, desc, null, null); body.visitCode();
        if (desc.endsWith("F")) { body.visitLdcInsn(0.6f); body.visitInsn(FRETURN); }
        else {
            body.visitVarInsn(ALOAD, 0); body.visitInsn(DUP); body.visitFieldInsn(GETFIELD, name, "calls", "I");
            body.visitInsn(ICONST_1); body.visitInsn(IADD); body.visitFieldInsn(PUTFIELD, name, "calls", "I"); body.visitInsn(RETURN);
        }
        body.visitMaxs(0, 0); body.visitEnd(); out.visitEnd(); return out.toByteArray();
    }

    private static void itemUseFrames() throws Exception {
        Loader loader = new Loader();
        String player = "net/minecraft/world/entity/player/Player", hand = "net/minecraft/world/InteractionHand";
        String result = "net/minecraft/world/InteractionResult", mode = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
        for (String name : List.of(player, hand, result)) {
            ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_MAXS); type.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
            constructor(type); type.visitEnd(); loader.define(name, type.toByteArray());
        }
        String bridgeName = "com/baggute/cheatclient/LateBridge";
        ClassWriter bridge = new ClassWriter(ClassWriter.COMPUTE_MAXS); bridge.visit(V17, ACC_PUBLIC, bridgeName, null, "java/lang/Object", null);
        bridge.visitField(ACC_PUBLIC | ACC_STATIC, "snapshot", "Ljava/lang/Object;", null, null).visitEnd(); constructor(bridge);
        MethodVisitor capture = bridge.visitMethod(ACC_PUBLIC | ACC_STATIC, "captureItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        capture.visitCode(); capture.visitVarInsn(ALOAD, 0); capture.visitInsn(ARETURN); capture.visitMaxs(0, 0); capture.visitEnd();
        MethodVisitor complete = bridge.visitMethod(ACC_PUBLIC | ACC_STATIC, "completeItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        complete.visitCode(); complete.visitVarInsn(ALOAD, 1); complete.visitFieldInsn(PUTSTATIC, bridgeName, "snapshot", "Ljava/lang/Object;");
        complete.visitVarInsn(ALOAD, 0); complete.visitInsn(ARETURN); complete.visitMaxs(0, 0); complete.visitEnd(); bridge.visitEnd();
        Class<?> bridgeType = loader.define(bridgeName, bridge.toByteArray());
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); out.visit(V17, ACC_PUBLIC, mode, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC, "result", "L" + result + ";", null, null).visitEnd(); constructor(out);
        MethodVisitor use = out.visitMethod(ACC_PUBLIC, "useItem", "(L" + player + ";L" + hand + ";)L" + result + ";", null, null);
        use.visitCode(); Label ordinary = new Label(); use.visitVarInsn(ALOAD, 2); use.visitJumpInsn(IFNONNULL, ordinary);
        use.visitVarInsn(ALOAD, 0); use.visitFieldInsn(GETFIELD, mode, "result", "L" + result + ";"); use.visitInsn(ARETURN);
        use.visitLabel(ordinary); use.visitInsn(LCONST_1); use.visitVarInsn(LSTORE, 3);
        use.visitVarInsn(ALOAD, 0); use.visitFieldInsn(GETFIELD, mode, "result", "L" + result + ";"); use.visitVarInsn(ASTORE, 5);
        Label returned = new Label(); use.visitVarInsn(LLOAD, 3); use.visitInsn(LCONST_0); use.visitInsn(LCMP); use.visitJumpInsn(IFGT, returned);
        use.visitInsn(ACONST_NULL); use.visitInsn(ARETURN); use.visitLabel(returned); use.visitVarInsn(ALOAD, 5); use.visitInsn(ARETURN);
        use.visitMaxs(0, 0); use.visitEnd(); out.visitEnd();
        byte[] patched = RuntimePatcher.rewrite(mode, out.toByteArray());
        require(patched != null && RuntimePatcher.rewrite(mode, patched) == null, "item-use patch is installed once");
        Class<?> type = loader.define(mode, patched); Object instance = type.getConstructor().newInstance();
        Class<?> playerType = Class.forName(player.replace('/', '.'), true, loader), handType = Class.forName(hand.replace('/', '.'), true, loader);
        Object user = playerType.getConstructor().newInstance(), usedHand = handType.getConstructor().newInstance();
        Object expected = Class.forName(result.replace('/', '.'), true, loader).getConstructor().newInstance(); type.getField("result").set(instance, expected);
        var method = type.getMethod("useItem", playerType, handType);
        require(method.invoke(instance, user, null) == expected && bridgeType.getField("snapshot").get(null) == user,
                "early return keeps the capture local and original result");
        require(method.invoke(instance, user, usedHand) == expected && bridgeType.getField("snapshot").get(null) == user,
                "branched return with long and object locals verifies in the JVM");
    }
    private static byte[] hooks() {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, HOOKS, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC | ACC_STATIC, "blocked", "Z", null, null).visitEnd();
        out.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd(); constructor(out);
        for (var entry : Map.of("turn", "(Ljava/lang/Object;DD)Z", "scroll", "(D)Z", "hideHand", "()Z").entrySet()) {
            var body = out.visitMethod(ACC_PUBLIC | ACC_STATIC, entry.getKey(), entry.getValue(), null, null); body.visitCode();
            body.visitFieldInsn(GETSTATIC, HOOKS, "blocked", "Z"); body.visitInsn(IRETURN); body.visitMaxs(0, 0); body.visitEnd();
        }
        var freeze = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "freezeInput", "(Ljava/lang/Object;)V", null, null); freeze.visitCode();
        freeze.visitFieldInsn(GETSTATIC, HOOKS, "calls", "I"); freeze.visitInsn(ICONST_1); freeze.visitInsn(IADD);
        freeze.visitFieldInsn(PUTSTATIC, HOOKS, "calls", "I"); freeze.visitInsn(RETURN); freeze.visitMaxs(0, 0); freeze.visitEnd();
        var step = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "stepHeight", "(FLjava/lang/Object;)F", null, null); step.visitCode();
        step.visitVarInsn(FLOAD, 0); step.visitInsn(FCONST_1); step.visitInsn(FADD); step.visitInsn(FRETURN); step.visitMaxs(0, 0); step.visitEnd();
        out.visitEnd(); return out.toByteArray();
    }
    private static void constructor(ClassWriter out) {
        var init = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
    }
    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
