package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the exact helper calls before they can enter a running game loop. */
final class HookLinkage {
    private static final String BRIDGE = "com/baggute/cheatclient/LateBridge";
    private static final String GAMEPLAY = "com/baggute/cheatclient/GameplayHooks";
    private static final String CONTAINER = "com/baggute/cheatclient/ContainerMouseHooks";

    private record Call(String owner, String method, String descriptor) {}

    private static final List<Call> CONTRACT = List.of(
            new Call(BRIDGE, "onHudRender", "(Ljava/lang/Object;)V"),
            new Call(BRIDGE, "onSubmitFeatures", "(Ljava/lang/Object;)V"),
            new Call(BRIDGE, "onRenderFrame", "()V"),
            new Call(BRIDGE, "onClientTick", "()V"),
            new Call(BRIDGE, "onKeyPress", "(Ljava/lang/Object;I)Z"),
            new Call(BRIDGE, "captureItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            new Call(BRIDGE, "completeItemUse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            new Call(BRIDGE, "scaleMotion", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            new Call(BRIDGE, "applyKnockback", "(Ljava/lang/Object;DDD)V"),
            new Call(BRIDGE, "explosionImpulse", "(Ljava/lang/Object;)Ljava/util/Optional;"),
            new Call(BRIDGE, "onSeedChunk", "(Ljava/lang/Object;)V"),
            new Call(BRIDGE, "onSeedLogin", "(Ljava/lang/Object;)V"),
            new Call(BRIDGE, "onSeedRespawn", "(Ljava/lang/Object;)V"),
            new Call(BRIDGE, "onSeedTick", "()V"),
            new Call(BRIDGE, "onSeedDisconnect", "()V"),
            new Call(BRIDGE, "delayMovement", "(Ljava/lang/Object;)Z"),
            new Call(BRIDGE, "flushMovement", "()V"),
            new Call(GAMEPLAY, "freezeInput", "(Ljava/lang/Object;)V"),
            new Call(GAMEPLAY, "camera", "(Ljava/lang/Object;)V"),
            new Call(GAMEPLAY, "stepHeight", "(FLjava/lang/Object;)F"),
            new Call(GAMEPLAY, "lightmapOption", "(Ljava/lang/Object;)Ljava/lang/Object;"),
            new Call(GAMEPLAY, "turn", "(Ljava/lang/Object;DD)Z"),
            new Call(GAMEPLAY, "hideHand", "()Z"),
            new Call(GAMEPLAY, "scroll", "(D)Z"),
            new Call(GAMEPLAY, "blockAttack", "(Ljava/lang/Object;Ljava/lang/Object;)Z"),
            new Call(GAMEPLAY, "blockClick", "(Ljava/lang/Object;)Z"),
            new Call(GAMEPLAY, "entityRange", "(DLjava/lang/Object;)D"),
            new Call(GAMEPLAY, "beforeTick", "(Ljava/lang/Object;)V"),
            new Call(GAMEPLAY, "cameraFov", "(F)F"),
            new Call(GAMEPLAY, "afterAttack", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
            new Call(GAMEPLAY, "beginBowRelease", "(Ljava/lang/Object;)Ljava/lang/Object;"),
            new Call(GAMEPLAY, "endBowRelease", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
            new Call(CONTAINER, "handle", "(Ljava/lang/Object;Ljava/lang/Object;I)Z"));

    private HookLinkage() {}

    /** Full preflight, including class initialization, before any hook is installed. */
    static boolean ready(ClassLoader loader) {
        // A parent-loaded helper cannot resolve Minecraft types from a child Knot.
        for (String name : List.of(BRIDGE, GAMEPLAY, CONTAINER)) {
            try {
                Class<?> helper = Class.forName(name.replace('/', '.'), false, loader);
                if (helper.getClassLoader() != loader) {
                    Relay.log("linkage", "refusing client hook preflight: " + name.replace('/', '.')
                            + " belongs to another classloader; restart with the matching client JAR");
                    return false;
                }
            } catch (Throwable failure) {
                Relay.log("linkage", "refusing client hook preflight: " + name.replace('/', '.') + ": " + failure);
                return false;
            }
        }
        return resolve(loader, CONTRACT, "client hook preflight", true);
    }

    /** A target may use another loader; validate every emitted call through that loader. */
    static boolean visible(ClassLoader loader, String target, byte[] rewritten) {
        ClassNode node = new ClassNode();
        new ClassReader(rewritten).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<Call> calls = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && (BRIDGE.equals(call.owner) || GAMEPLAY.equals(call.owner) || CONTAINER.equals(call.owner))) {
                    calls.add(new Call(call.owner, call.name, call.desc));
                }
            }
        }
        return resolve(loader, calls, target, false);
    }

    private static boolean resolve(ClassLoader loader, Iterable<Call> calls, String target, boolean initialize) {
        for (Call call : calls) {
            try {
                Class<?> owner = Class.forName(call.owner().replace('/', '.'), initialize, loader);
                MethodType signature = MethodType.fromMethodDescriptorString(call.descriptor(), loader);
                Method method = owner.getMethod(call.method(), signature.parameterArray());
                if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                        || !Modifier.isStatic(method.getModifiers()) || method.getReturnType() != signature.returnType()) {
                    throw new NoSuchMethodException("helper is not a public static method with the expected return type");
                }
                MethodHandles.publicLookup().findStatic(owner, call.method(), signature);
            } catch (Throwable failure) {
                Relay.log("linkage", "refusing " + target + ": " + call.owner().replace('/', '.') + "."
                        + call.method() + call.descriptor() + " is unavailable through "
                        + (loader == null ? "the bootstrap loader" : loader.getClass().getName()) + ": " + failure);
                return false;
            }
        }
        return true;
    }
}
