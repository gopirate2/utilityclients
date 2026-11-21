package com.baggute.cheatclient.injector.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Executes every essential bridge site in a JVM and checks supported game bytecode. */
public final class BridgePatchChecks implements Opcodes {
    private static final String BRIDGE = "com/baggute/cheatclient/LateBridge";
    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String WORLD = "net/minecraft/client/renderer/LevelRenderer";
    private static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";
    private static final Map<String, Integer> TARGETS = Map.of(MC, 2, HUD, 1, WORLD, 1, KEYBOARD, 1);
    private static final String[] ARGUMENTS = {
            "net/minecraft/client/gui/GuiGraphicsExtractor", "net/minecraft/client/DeltaTracker",
            "net/minecraft/client/renderer/state/level/LevelRenderState", "net/minecraft/client/renderer/SubmitNodeCollector",
            "net/minecraft/client/input/KeyEvent"};

    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        Loader loader = loader();
        Class<?> helper = Class.forName(BRIDGE.replace('/', '.'), true, loader);
        Map<String, Class<?>> patched = new HashMap<>();
        for (String target : TARGETS.keySet()) {
            byte[] original = fixture(target), bytes = BridgePatcher.rewrite(target, original);
            require(bytes != null && BridgePatcher.matchingSites(target, bytes).size() == TARGETS.get(target), "all sites: " + target);
            require(BridgePatcher.rewrite(target, bytes) == null, "repeat transform is idempotent: " + target);
            sameShape(original, bytes);
            patched.put(target, loader.define(target, bytes));
        }
        Class<?> minecraft = patched.get(MC); Object game = minecraft.getConstructor().newInstance();
        minecraft.getMethod("renderFrame", boolean.class).invoke(game, false);
        minecraft.getMethod("renderFrame", boolean.class).invoke(game, true);
        minecraft.getMethod("renderFrame").invoke(game);
        require(count(helper, "onRenderFrame") == 2, "exact render descriptor leaves overload untouched");
        minecraft.getMethod("tick").invoke(game);
        minecraft.getField("alternateReturn").setBoolean(game, true); minecraft.getMethod("tick").invoke(game);
        require(count(helper, "onClientTick") == 2, "tick callback reaches both normal returns once");
        Class<?> graphics = Class.forName(ARGUMENTS[0].replace('/', '.'), true, loader);
        Class<?> delta = Class.forName(ARGUMENTS[1].replace('/', '.'), true, loader);
        Object argument = graphics.getConstructor().newInstance();
        Class<?> hud = patched.get(HUD); Object display = hud.getConstructor().newInstance();
        hud.getMethod("extractRenderState", graphics, delta).invoke(display, argument, null);
        hud.getField("alternateReturn").setBoolean(display, true);
        hud.getMethod("extractRenderState", graphics, delta).invoke(display, argument, null);
        require(count(helper, "onHudRender") == 2 && helper.getField("lastArgument").get(null) == argument, "HUD tail forwards graphics through both returns");
        Class<?> state = Class.forName(ARGUMENTS[2].replace('/', '.'), true, loader);
        Class<?> collector = Class.forName(ARGUMENTS[3].replace('/', '.'), true, loader);
        argument = state.getConstructor().newInstance();
        Class<?> world = patched.get(WORLD); Object renderer = world.getConstructor().newInstance();
        world.getMethod("submitFeatures", state, collector, boolean.class).invoke(renderer, argument, null, false);
        require(count(helper, "onSubmitFeatures") == 1 && helper.getField("lastArgument").get(null) == argument, "world head forwards render state");
        Class<?> key = Class.forName(ARGUMENTS[4].replace('/', '.'), true, loader);
        argument = key.getConstructor().newInstance();
        Class<?> keyboard = patched.get(KEYBOARD); Object handler = keyboard.getConstructor().newInstance();
        keyboard.getMethod("keyPress", long.class, int.class, key).invoke(handler, 99L, 1, argument);
        require(helper.getField("lastAction").getInt(null) == 1 && helper.getField("lastArgument").get(null) == argument, "keyboard argument slots include wide window handle");
        helper.getField("blocked").setBoolean(null, true);
        keyboard.getMethod("keyPress", long.class, int.class, key).invoke(handler, 99L, 0, argument);
        require(count(helper, "onKeyPress") == 2 && keyboard.getField("calls").getInt(handler) == 1, "keyboard consumes only handled input with valid JVM frames");
        startupAndPartial(); readiness(); gameSites();
        System.out.println("PASS: five exact bridge sites, JVM branches/returns, forwarded arguments, startup and repeated-attach deduplication, failed-retransform readiness and actual game sites");
    }

    private static int count(Class<?> type, String callback) throws Exception { return type.getField(callback + "Calls").getInt(null); }
    private static void sameShape(byte[] original, byte[] patched) {
        ClassNode before = node(original), after = node(patched);
        require(before.fields.size() == after.fields.size() && before.methods.size() == after.methods.size(), "retransform never changes class shape");
    }
    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] write(ClassNode node) { ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(out); return out.toByteArray(); }
    private static void startupAndPartial() {
        for (boolean startup : List.of(true, false)) {
            ClassNode game = node(fixture(MC)); MethodNode tick = game.methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();
            if (startup) {
                String name = "handler$fixture$utilityclient$onClientTick";
                MethodNode marker = new MethodNode(ACC_PRIVATE, name, "()V", null, null);
                marker.instructions.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, "onClientTick", "()V", false));
                marker.instructions.add(new InsnNode(RETURN)); marker.maxLocals = 1; game.methods.add(marker);
                InsnList call = new InsnList(); call.add(new VarInsnNode(ALOAD, 0)); call.add(new MethodInsnNode(INVOKESPECIAL, MC, name, "()V", false)); tick.instructions.insert(call);
            } else tick.instructions.insert(new MethodInsnNode(INVOKESTATIC, BRIDGE, "onClientTick", "()V", false));
            byte[] original = write(game), patch = BridgePatcher.rewrite(MC, original);
            require(patch != null && BridgePatcher.matchingSites(MC, patch).size() == 2, "missing frame repaired beside existing " + (startup ? "startup" : "runtime") + " tick");
            MethodNode output = node(patch).methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();
            long direct = 0;
            for (AbstractInsnNode insn : output.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(BRIDGE) && call.name.equals("onClientTick")) direct++;
            require(direct == (startup ? 0 : 1) && BridgePatcher.rewrite(MC, patch) == null, "existing tick never duplicated");
            sameShape(original, patch);
        }
        byte[] overload = fixture("unrelated/OnlyOverload");
        require(BridgePatcher.rewrite(MC, overload) == null, "wrong descriptors never selected");
        ClassNode unusedMarker = node(fixture(MC));
        MethodNode unused = new MethodNode(ACC_PRIVATE, "handler$unused$utilityclient$onClientTick", "()V", null, null);
        unused.instructions.add(new InsnNode(RETURN)); unused.maxLocals = 1; unusedMarker.methods.add(unused);
        require(BridgePatcher.matchingSites(MC, BridgePatcher.rewrite(MC, write(unusedMarker))).size() == 2, "unused marker does not conceal missing call");
    }

    private static void readiness() throws Exception {
        Loader loader = loader(); Map<String, byte[]> originals = fixtures();
        for (String owner : TARGETS.keySet()) loader.define(owner, originals.get(owner));
        Class<?> mc = Class.forName(MC.replace('/', '.'), false, loader);
        Counter counter = new Counter(loader, mc, originals); Instrumentation inst = counter.instrumentation();
        require(!BridgePatcher.ready(inst) && BridgePatcher.missing(inst).size() == 5, "unpatched attach never ready");
        require(BridgePatcher.patch(inst) == 5 && BridgePatcher.ready(inst), "all committed sites ready");
        require(BridgePatcher.patch(inst) == 5 && counter.transformers.size() == 1, "repeat attachment reuses transformer");
        counter.unmodifiable = HUD;
        require(BridgePatcher.patch(inst) == 4 && !BridgePatcher.ready(inst) && BridgePatcher.missing(inst).getFirst().contains("extractRenderState"), "one refused HUD site makes attach fail");
        counter.unmodifiable = null; counter.rejectAfterTransform = MC;
        require(BridgePatcher.patch(inst) == 3 && !BridgePatcher.ready(inst)
                && BridgePatcher.missing(inst).stream().anyMatch(s -> s.endsWith("#tick")), "JVM refusal clears speculative tick and frame verification");
        counter.rejectAfterTransform = null;
        require(BridgePatcher.patch(inst) == 5 && BridgePatcher.ready(inst), "valid retry repairs missing sites");
    }
    private static final class Counter {
        final Loader loader; final Class<?> minecraft; final Map<String, byte[]> bytes;
        final List<ClassFileTransformer> transformers = new ArrayList<>(); String unmodifiable, rejectAfterTransform;
        Counter(Loader loader, Class<?> mc, Map<String, byte[]> bytes) { this.loader = loader; minecraft = mc; this.bytes = new HashMap<>(bytes); }
        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(Instrumentation.class.getClassLoader(), new Class<?>[]{Instrumentation.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isRetransformClassesSupported" -> true;
                case "isModifiableClass" -> !((Class<?>) args[0]).getName().replace('.', '/').equals(unmodifiable);
                case "getAllLoadedClasses" -> new Class<?>[]{minecraft};
                case "addTransformer" -> { transformers.add((ClassFileTransformer) args[0]); yield null; }
                case "retransformClasses" -> {
                    for (Class<?> target : (Class<?>[]) args[0]) {
                        String owner = target.getName().replace('.', '/'); byte[] patched = bytes.get(owner);
                        for (ClassFileTransformer transformer : transformers) {
                            byte[] result = transformer.transform(null, loader, owner, target, null, patched);
                            if (result != null) patched = result;
                        }
                        if (owner.equals(rejectAfterTransform)) throw new UnmodifiableClassException("fixture JVM refusal after transform");
                        bytes.put(owner, patched);
                    }
                    yield null;
                }
                case "toString" -> "bridge-fixture-instrumentation";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        }
    }

    private static void gameSites() throws Exception {
        Map<String, byte[]> classes = new HashMap<>(); String root = System.getProperty("uc.regression.classRoot");
        if (root != null) for (String owner : TARGETS.keySet()) classes.put(owner, Files.readAllBytes(Path.of(root).resolve(owner + ".class")));
        else {
            Path cache = Path.of("..", ".gradle", "loom-cache", "minecraftMaven", "net", "minecraft");
            if (!Files.isDirectory(cache)) cache = Path.of(".gradle", "loom-cache", "minecraftMaven", "net", "minecraft");
            try (var files = Files.walk(cache)) {
                for (Path path : files.filter(p -> p.toString().endsWith(".jar")).toList()) try (JarFile jar = new JarFile(path.toFile())) {
                    for (String owner : TARGETS.keySet()) {
                        var entry = jar.getJarEntry(owner + ".class");
                        if (entry != null && !classes.containsKey(owner)) try (var input = jar.getInputStream(entry)) { classes.put(owner, input.readAllBytes()); }
                    }
                }
            }
        }
        for (String owner : TARGETS.keySet()) {
            byte[] original = classes.get(owner); require(original != null, "actual game class exists: " + owner);
            byte[] patch = BridgePatcher.rewrite(owner, original);
            require(patch != null && BridgePatcher.matchingSites(owner, patch).size() == TARGETS.get(owner), "actual 26.2 descriptors: " + owner);
            sameShape(original, patch); require(BridgePatcher.rewrite(owner, patch) == null, "actual game patch idempotence");
        }
    }

    static Map<String, byte[]> fixtures() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String argument : ARGUMENTS) out.put(argument, fixture(argument));
        for (String owner : TARGETS.keySet()) out.put(owner, fixture(owner)); return out;
    }
    static byte[] fixture(String owner) {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        out.visit(V17, ACC_PUBLIC, owner, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC, "calls", "I", null, null).visitEnd(); out.visitField(ACC_PUBLIC, "alternateReturn", "Z", null, null).visitEnd(); constructor(out);
        if (owner.equals(MC)) {
            out.visitField(ACC_PRIVATE | ACC_STATIC, "instance", "L" + MC + ";", null, null).visitEnd();
            out.visitField(ACC_PUBLIC | ACC_STATIC, "executions", "I", null, null).visitEnd();
            MethodVisitor init = out.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null); init.visitCode();
            init.visitTypeInsn(NEW, MC); init.visitInsn(DUP); init.visitMethodInsn(INVOKESPECIAL, MC, "<init>", "()V", false); init.visitFieldInsn(PUTSTATIC, MC, "instance", "L" + MC + ";"); init.visitInsn(RETURN); end(init);
            MethodVisitor get = out.visitMethod(ACC_PUBLIC | ACC_STATIC, "getInstance", "()L" + MC + ";", null, null); get.visitCode(); get.visitFieldInsn(GETSTATIC, MC, "instance", "L" + MC + ";"); get.visitInsn(ARETURN); end(get);
            MethodVisitor execute = out.visitMethod(ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null); execute.visitCode();
            execute.visitFieldInsn(GETSTATIC, MC, "executions", "I"); execute.visitInsn(ICONST_1); execute.visitInsn(IADD); execute.visitFieldInsn(PUTSTATIC, MC, "executions", "I");
            execute.visitVarInsn(ALOAD, 1); execute.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true); execute.visitInsn(RETURN); end(execute);
            body(out, owner, "renderFrame", "(Z)V"); body(out, owner, "renderFrame", "()V"); body(out, owner, "tick", "()V");
        } else if (owner.equals(HUD)) body(out, owner, "extractRenderState", "(L" + ARGUMENTS[0] + ";L" + ARGUMENTS[1] + ";)V");
        else if (owner.equals(WORLD)) body(out, owner, "submitFeatures", "(L" + ARGUMENTS[2] + ";L" + ARGUMENTS[3] + ";Z)V");
        else if (owner.equals(KEYBOARD)) body(out, owner, "keyPress", "(JIL" + ARGUMENTS[4] + ";)V");
        else if (owner.equals("unrelated/OnlyOverload")) body(out, owner, "renderFrame", "()V");
        out.visitEnd(); return out.toByteArray();
    }
    private static void body(ClassWriter out, String owner, String name, String descriptor) {
        MethodVisitor method = out.visitMethod(ACC_PUBLIC, name, descriptor, null, null); method.visitCode();
        method.visitVarInsn(ALOAD, 0); method.visitInsn(DUP); method.visitFieldInsn(GETFIELD, owner, "calls", "I");
        method.visitInsn(ICONST_1); method.visitInsn(IADD); method.visitFieldInsn(PUTFIELD, owner, "calls", "I");
        Label other = new Label(); method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, owner, "alternateReturn", "Z"); method.visitJumpInsn(IFEQ, other);
        method.visitInsn(RETURN); method.visitLabel(other); method.visitInsn(RETURN); end(method);
    }
    private static void constructor(ClassWriter out) { MethodVisitor method = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode(); method.visitVarInsn(ALOAD, 0); method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); method.visitInsn(RETURN); end(method); }
    private static void end(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static Loader loader() {
        Loader loader = new Loader();
        for (String helper : List.of("LateBridge", "GameplayHooks", "ContainerMouseHooks")) loader.define("com/baggute/cheatclient/" + helper, HookLinkageChecks.helper(helper, false, false));
        for (String argument : ARGUMENTS) loader.define(argument, fixture(argument)); return loader;
    }
    private static final class Loader extends ClassLoader {
        Loader() { super(ClassLoader.getPlatformClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
