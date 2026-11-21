package com.baggute.cheatclient;

import com.baggute.cheatclient.util.WindChargeIntercept;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Independent projectile simulation, plus the physics order in the actual game classes. */
public final class PearlInterceptChecks {
    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        int hits = 0, unreachable = 0;
        Vec3 eye = new Vec3(13, 72.62, -7);
        for (int yaw = -180; yaw < 180; yaw += 30) {
            for (int pitch = -90; pitch <= 90; pitch += 10) {
                for (int age : new int[]{1, 2, 4, 8}) {
                    for (Vec3 inherited : new Vec3[]{Vec3.ZERO, new Vec3(.2, 0, -.12), new Vec3(-.15, .3, .22)}) {
                        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                        Vec3 velocity = new Vec3(-Math.sin(y) * Math.cos(p) * 1.5,
                                -Math.sin(p) * 1.5, Math.cos(y) * Math.cos(p) * 1.5).add(inherited);
                        Vec3[] state = simulate(eye.add(0, .025, 0), velocity, .99f, .03, age);
                        Vec3 windOrigin = eye.add(inherited.scale(age));
                        var launch = WindChargeIntercept.solve(windOrigin, inherited, state[0], state[1], .99f, .03);
                        if (launch == null) { unreachable++; continue; }
                        Vec3[] actual = simulate(state[0], state[1], .99f, .03, launch.ticks());
                        Vec3 wind = windOrigin.add(launch.direction().scale(1.5).add(inherited).scale(launch.ticks()));
                        require(wind.distanceTo(actual[0]) <= .200001, "predicted shot enters pearl hitbox");
                        require(Math.abs(launch.direction().length() - 1) < 1e-9, "shot speed remains vanilla");
                        Vec3 restored = direction(launch.yaw(), launch.pitch());
                        require(restored.distanceTo(launch.direction()) < 1e-6, "packet yaw/pitch reconstruct the launch");
                        // Before the chosen intercept, even the optimal direction cannot reach it.
                        double before = launch.ticks() - .125;
                        if (before > 0) {
                            Vec3 earlier = simulate(state[0], state[1], .99f, .03, before)[0];
                            require(earlier.subtract(windOrigin).subtract(inherited.scale(before)).length()
                                    > 1.5 * before + .19999, "earliest reachable intercept selected");
                        }
                        hits++;
                    }
                }
            }
        }
        require(hits > 1000 && unreachable > 0, "both reachable and impossible pearl angles exercised");
        require(WindChargeIntercept.solve(Vec3.ZERO, Vec3.ZERO, new Vec3(20, 0, 0),
                new Vec3(3, 0, 0), 1, 0) == null, "faster receding projectile rejected");
        require(WindChargeIntercept.solve(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, Vec3.ZERO,
                Vec3.ZERO, .99, .03) == null, "nonfinite origin rejected");
        require(WindChargeIntercept.solve(Vec3.ZERO, Vec3.ZERO, new Vec3(1, 0, 0),
                Vec3.ZERO, 0, .03) == null, "invalid physics rejected");
        require(WindChargeIntercept.solve(Vec3.ZERO, Vec3.ZERO, new Vec3(1, 0, 0),
                Vec3.ZERO, .99, .03, 81) == null, "prediction budget bounded");
        for (double drag : new double[]{1, .8f, .99f}) {
            Vec3 initial = new Vec3(0, 20, 0), velocity = new Vec3(.3, -.2, .8);
            var actual = simulate(initial, velocity, drag, .03, 40);
            var predicted = WindChargeIntercept.advance(initial, velocity, drag, .03, 40);
            require(predicted.position().distanceTo(actual[0]) < 1e-9, "air/water/no-drag prediction");
        }
        require(WindChargeIntercept.inherited(new Vec3(1, 2, 3), true).equals(new Vec3(1, 0, 3)), "grounded vertical motion excluded");
        require(WindChargeIntercept.inherited(new Vec3(1, 2, 3), false).equals(new Vec3(1, 2, 3)), "airborne motion inherited");
        gamePhysics();
        System.out.println("PASS: " + hits + " moving-pearl intercepts / " + unreachable
                + " impossible angles, packet aim, earliest hit, inherited motion and actual 26.2 physics");
    }

    private static Vec3 direction(float yaw, float pitch) {
        double y = yaw * Math.PI / 180, p = pitch * Math.PI / 180;
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static Vec3[] simulate(Vec3 position, Vec3 velocity, double drag, double gravity, double ticks) {
        int whole = (int) ticks;
        for (int i = 0; i < whole; i++) {
            velocity = new Vec3(velocity.x * drag, (velocity.y - gravity) * drag, velocity.z * drag);
            position = new Vec3(position.x + velocity.x, position.y + velocity.y, position.z + velocity.z);
        }
        double fraction = ticks - whole;
        if (fraction > 0) position = new Vec3(position.x + velocity.x * drag * fraction,
                position.y + (velocity.y - gravity) * drag * fraction, position.z + velocity.z * drag * fraction);
        return new Vec3[]{position, velocity};
    }

    private static void gamePhysics() throws Exception {
        Path root = Path.of(System.getProperty("uc.regression.classRoot",
                "../utilityclient-repair/exe-manual-build/classpath"));
        ClassNode pearl = read(root, "net/minecraft/world/entity/projectile/ThrowableProjectile");
        MethodNode tick = pearl.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElseThrow();
        int gravity = -1, drag = -1, movement = -1, index = 0;
        for (AbstractInsnNode insn : tick.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.name.equals("applyGravity")) gravity = index;
                if (call.name.equals("applyInertia")) drag = index;
                if (call.name.equals("getHitResultOnMoveVector")) movement = index;
            }
            index++;
        }
        require(gravity >= 0 && gravity < drag && drag < movement, "game applies gravity then drag before movement");
        require(constant(pearl, "getDefaultGravity", .03), "pearl gravity matches game");
        require(constant(pearl, "getAirDrag", .99f), "pearl air drag matches game");
        require(constant(read(root, "net/minecraft/world/item/WindChargeItem"), "use", 1.5f), "wind launch speed matches game");
        ClassNode wind = read(root, "net/minecraft/world/entity/projectile/hurtingprojectile/windcharge/AbstractWindCharge");
        require(wind.methods.stream().filter(m -> m.name.equals("getInertia")).anyMatch(m -> {
            for (AbstractInsnNode insn : m.instructions) if (insn.getOpcode() == org.objectweb.asm.Opcodes.FCONST_1) return true;
            return false;
        }), "wind speed remains constant");
    }

    private static boolean constant(ClassNode node, String method, Number expected) {
        return node.methods.stream().filter(m -> m.name.equals(method)).anyMatch(m -> {
            for (AbstractInsnNode insn : m.instructions) if (insn instanceof LdcInsnNode ldc
                    && ldc.cst instanceof Number n && n.doubleValue() == expected.doubleValue()) return true;
            return false;
        });
    }
    private static ClassNode read(Path root, String name) throws Exception {
        ClassNode node = new ClassNode(); new ClassReader(Files.readAllBytes(root.resolve(name + ".class"))).accept(node, 0); return node;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
