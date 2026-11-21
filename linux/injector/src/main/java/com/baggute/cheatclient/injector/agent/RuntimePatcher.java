package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Runtime counterparts for physics and SeedCracker hooks on already-loaded classes. */
final class RuntimePatcher implements ClassFileTransformer {
    private static final String BRIDGE = "com/baggute/cheatclient/LateBridge";
    private static final String GAMEPLAY = "com/baggute/cheatclient/GameplayHooks";
    private static final Map<Instrumentation, RuntimePatcher> ACTIVE = new IdentityHashMap<>();
    private final ClassLoader gameLoader;
    private final boolean restricted;

    RuntimePatcher() { this.gameLoader = null; this.restricted = false; }
    private RuntimePatcher(ClassLoader gameLoader) { this.gameLoader = gameLoader; this.restricted = true; }
    private static final Set<String> TARGETS = Set.of(
            "net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/client/multiplayer/ClientPacketListener",
            "net/minecraft/client/multiplayer/ClientLevel", "net/minecraft/client/player/LocalPlayer",
            "net/minecraft/client/player/KeyboardInput", "net/minecraft/client/Camera",
            "net/minecraft/client/renderer/LightmapRenderStateExtractor",
            "net/minecraft/client/renderer/GameRenderer", "net/minecraft/client/MouseHandler",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
            "net/minecraft/client/multiplayer/MultiPlayerGameMode");

    static synchronized void patch(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            Relay.log("runtime", "this JVM does not support runtime hooks");
            return;
        }
        ClassLoader gameLoader = null;
        boolean found = false;
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.getName().equals("net.minecraft.client.Minecraft")) {
                gameLoader = type.getClassLoader(); found = true; break;
            }
        }
        if (!found || !HookLinkage.ready(gameLoader)) return;
        RuntimePatcher transformer = ACTIVE.get(inst);
        if (transformer == null) {
            transformer = new RuntimePatcher(gameLoader);
            inst.addTransformer(transformer, true);
            ACTIVE.put(inst, transformer);
        }
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.getClassLoader() != gameLoader || !TARGETS.contains(type.getName().replace('.', '/'))
                    || !inst.isModifiableClass(type)) continue;
            try { inst.retransformClasses(type); }
            catch (Throwable failure) { Relay.log("runtime", type.getName() + ": " + failure); }
        }
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String name,
            Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if ((restricted && loader != gameLoader) || !TARGETS.contains(name == null ? "" : name)) return null;
        try {
            byte[] rewritten = rewrite(name, bytes);
            return rewritten != null && HookLinkage.visible(loader, name, rewritten) ? rewritten : null;
        }
        catch (Throwable failure) { Relay.log("runtime", "hook failed: " + name + ": " + failure); return null; }
    }

    static byte[] rewrite(String name, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        // Startup-loaded UtilityClient already owns these hooks. Applying runtime
        // counterparts too would scale impulses twice and duplicate input handling.
        if (node.methods.stream().anyMatch(m -> m.name.contains("utilityclient$"))) return null;
        boolean changed = false;
        for (MethodNode original : node.methods) {
            MethodNode method = original;
            boolean existing = false;
            for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof MethodInsnNode call && (call.owner.equals(BRIDGE) || call.owner.equals(GAMEPLAY)
                        || call.owner.equals("com/baggute/cheatclient/ContainerMouseHooks"))) existing = true;
            if (existing) continue;
            if (name.endsWith("/MultiPlayerGameMode") && method.name.equals("useItem")
                    && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;")) {
                // Expand only this method so its new snapshot local survives every existing branch frame.
                ClassNode expanded = new ClassNode();
                new ClassReader(bytes).accept(expanded, ClassReader.EXPAND_FRAMES);
                method = expanded.methods.stream().filter(m -> m.name.equals(original.name) && m.desc.equals(original.desc)).findFirst().orElseThrow();
                node.methods.set(node.methods.indexOf(original), method);
                trackItemUse(method);
                changed = true;
            }
            if (name.endsWith("/Entity") && method.name.equals("lerpMotion")
                    && method.desc.equals("(Lnet/minecraft/world/phys/Vec3;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(call("scaleMotion", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                code.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/phys/Vec3"));
                code.add(new VarInsnNode(Opcodes.ASTORE, 1));
                method.instructions.insert(code);
                changed = true;
            }
            if (name.endsWith("/LivingEntity") && method.name.equals("knockback")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode invoke && invoke.name.equals("setDeltaMovement")
                            && invoke.desc.equals("(DDD)V")) {
                        method.instructions.set(invoke, call("applyKnockback", "(Ljava/lang/Object;DDD)V"));
                        changed = true;
                    }
                }
            }
            if (name.endsWith("/ClientPacketListener")) {
                if (Set.of("handleEntityPositionSync", "handleTeleportEntity", "handleRemoveEntities").contains(method.name)) {
                    method.instructions.insert(call("flushMovement", "()V")); changed = true;
                }
                if (method.name.equals("handleExplosion")) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode invoke && invoke.name.equals("playerKnockback")
                                && invoke.desc.equals("()Ljava/util/Optional;")) {
                            method.instructions.set(invoke, call("explosionImpulse", "(Ljava/lang/Object;)Ljava/util/Optional;"));
                            changed = true;
                        }
                    }
                }
                String hook = switch (method.name) {
                    case "handleLevelChunkWithLight" -> "onSeedChunk";
                    case "handleLogin" -> "onSeedLogin";
                    case "handleRespawn" -> "onSeedRespawn";
                    default -> null;
                };
                if (hook != null && Type.getArgumentTypes(method.desc).length == 1) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn.getOpcode() != Opcodes.RETURN) continue;
                        InsnList code = new InsnList();
                        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        code.add(call(hook, "(Ljava/lang/Object;)V"));
                        method.instructions.insertBefore(insn, code);
                        changed = true;
                    }
                }
            }
            if ((name.endsWith("/LocalPlayer") && method.name.equals("tick") && method.desc.equals("()V"))
                    || (name.endsWith("/ClientLevel") && method.name.equals("disconnect"))) {
                method.instructions.insert(call(name.endsWith("/LocalPlayer") ? "onSeedTick" : "onSeedDisconnect", "()V"));
                changed = true;
            }
            String tail = null;
            if (name.endsWith("/KeyboardInput") && method.name.equals("tick") && method.desc.equals("()V")) tail = "freezeInput";
            if (name.endsWith("/Camera") && method.name.equals("update")) tail = "camera";
            if (tail != null) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(gameplay(tail, "(Ljava/lang/Object;)V"));
                    method.instructions.insertBefore(insn, code); changed = true;
                }
            }
            if (name.endsWith("/LivingEntity") && method.name.equals("maxUpStep") && method.desc.equals("()F")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.FRETURN) continue;
                    InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(gameplay("stepHeight", "(FLjava/lang/Object;)F"));
                    method.instructions.insertBefore(insn, code); changed = true;
                }
            }
            if (name.endsWith("/LightmapRenderStateExtractor") && method.name.equals("extract")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode invoke && invoke.owner.equals("net/minecraft/client/OptionInstance")
                            && invoke.name.equals("get") && invoke.desc.equals("()Ljava/lang/Object;")) {
                        method.instructions.set(insn, gameplay("lightmapOption", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                        changed = true;
                    }
                }
            }
            InsnList guard = null;
            if (name.endsWith("/ClientPacketListener") && method.name.equals("handleMoveEntity")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/ClientboundMoveEntityPacket;)V")) {
                guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(call("delayMovement", "(Ljava/lang/Object;)Z"));
            }
            if (name.endsWith("/AbstractContainerScreen") && Set.of("mouseClicked", "mouseDragged", "mouseReleased").contains(method.name)
                    && method.desc.startsWith("(Lnet/minecraft/client/input/MouseButtonEvent;")) {
                int phase = method.name.equals("mouseClicked") ? 0 : method.name.equals("mouseDragged") ? 1 : 2;
                InsnList mouse = new InsnList(); mouse.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mouse.add(new VarInsnNode(Opcodes.ALOAD, 1)); mouse.add(new InsnNode(Opcodes.ICONST_0 + phase));
                mouse.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/baggute/cheatclient/ContainerMouseHooks", "handle",
                        "(Ljava/lang/Object;Ljava/lang/Object;I)Z", false));
                {
                    LabelNode body = new LabelNode(); mouse.add(new JumpInsnNode(Opcodes.IFEQ, body));
                    mouse.add(new InsnNode(Opcodes.ICONST_1)); mouse.add(new InsnNode(Opcodes.IRETURN)); mouse.add(body);
                    mouse.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                }
                method.instructions.insert(mouse); changed = true;
            }
            if (name.endsWith("/Entity") && method.name.equals("turn") && method.desc.equals("(DD)V")) {
                guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new VarInsnNode(Opcodes.DLOAD, 1)); guard.add(new VarInsnNode(Opcodes.DLOAD, 3));
                guard.add(gameplay("turn", "(Ljava/lang/Object;DD)Z"));
            }
            if (name.endsWith("/GameRenderer") && method.name.equals("renderItemInHand")) {
                guard = new InsnList(); guard.add(gameplay("hideHand", "()Z"));
            }
            if (name.endsWith("/MouseHandler") && method.name.equals("onScroll") && method.desc.equals("(JDD)V")) {
                guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.DLOAD, 5));
                guard.add(gameplay("scroll", "(D)Z"));
            }
            if (name.endsWith("/MultiPlayerGameMode") && method.name.equals("attack")
                    && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V")) {
                guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.ALOAD, 1)); guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
                guard.add(gameplay("blockAttack", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
            }
            if (guard != null && Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) {
                LabelNode body = new LabelNode(); guard.add(new JumpInsnNode(Opcodes.IFEQ, body));
                guard.add(new InsnNode(Opcodes.RETURN)); guard.add(body);
                guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(guard); changed = true;
            }
        }
        if (!changed) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
    private static MethodInsnNode call(String method, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, method, descriptor, false);
    }

    private static void trackItemUse(MethodNode method) {
        int snapshot = method.maxLocals++;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode frame) {
                int slots = 0;
                for (Object local : frame.local) slots += local.equals(Opcodes.LONG) || local.equals(Opcodes.DOUBLE) ? 2 : 1;
                while (slots++ < snapshot) frame.local.add(Opcodes.TOP);
                frame.local.add("java/lang/Object");
            }
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, snapshot));
                tail.add(call("completeItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                tail.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/InteractionResult"));
                method.instructions.insertBefore(instruction, tail);
            }
        }
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 1)); head.add(new VarInsnNode(Opcodes.ALOAD, 2));
        head.add(call("captureItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        head.add(new VarInsnNode(Opcodes.ASTORE, snapshot));
        method.instructions.insert(head);
    }
    private static MethodInsnNode gameplay(String method, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, GAMEPLAY, method, descriptor, false);
    }
}
