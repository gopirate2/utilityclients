// language: Java, file: TestBridge.java, target: Fabric 26.2
package com.baggute.cheatclient.testing;

import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.misc.Debug;
import com.baggute.cheatclient.settings.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback HTTP surface for the automated audit.
 *
 * <p>Off unless asked for: {@code -Dutilityclient.bridge.port=<n>} is the only thing
 * that starts it. A shipped client never sets that property, so this class compiles
 * into the jar and never opens a socket.
 *
 * <p><b>Why HTTP and not a socket protocol.</b> The consumer is a Node MCP server,
 * and every HTTP client worth having already speaks to a URL. The alternative - a
 * hand-rolled line protocol - would be one more parser to get wrong for no gain.
 *
 * <p><b>Threading.</b> {@code HttpServer} runs handlers on its own pool. Nothing in
 * this class touches the level from there: every read and every write goes through
 * {@link #onClient}, which hands the work to the client thread via
 * {@code Minecraft#submit} and waits with a bound. A module's {@code setEnabled}
 * fires its {@code onDisable}, which can touch the level, so calling it from an HTTP
 * thread would be a data race against the tick loop.
 *
 * <p><b>Bound, not hang.</b> Every marshalled call has a timeout. A client thread
 * stuck in a world load answers {@code ok:false} instead of leaving the audit waiting
 * forever, which is the difference between a report and a wedged run.
 *
 * <p>Bound to loopback only. There is no auth because there is nothing to
 * authenticate to: the socket is not reachable off the machine.
 */
public final class TestBridge {

    /** System property that turns the bridge on. Absent or blank means off. */
    public static final String PORT_PROPERTY = "utilityclient.bridge.port";

    /**
     * Upper bound on any single marshalled call.
     *
     * <p>30 s, not 5. The first version used 5 s and starved during the initial
     * chunk load: the client logged "Can't keep up! Running 30890ms or 617 ticks
     * behind", both pool threads timed out waiting on the render thread, and every
     * request after that failed. A bound exists to stop a wedged run, not to fail a
     * busy one - the audit has no deadline of its own, and a slow answer is still an
     * answer.
     */
    private static final long CLIENT_TIMEOUT_MS = 30_000L;

    /** Default tail length for {@code /log}. */
    private static final int DEFAULT_LOG_LINES = 200;

    /** Hard ceiling for {@code /log}, so one request cannot read a whole file into a string. */
    private static final int MAX_LOG_LINES = 5_000;

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile HttpServer server;
    private static volatile int port = -1;

    private TestBridge() {}

    /** Starts the bridge when the property is set. A no-op otherwise, and idempotent. */
    public static void startIfRequested() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw == null || raw.isBlank()) return;
        if (!STARTED.compareAndSet(false, true)) return;

        int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException malformed) {
            STARTED.set(false);
            com.baggute.cheatclient.UtilityClient.LOGGER.warn(
                    "[TestBridge] {} is not a port: {}", PORT_PROPERTY, raw);
            return;
        }

        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), requested), 0);
            created.createContext("/", TestBridge::handle);
            // A small pool: the audit is sequential and never issues two calls at
            // once, so more threads only add contention on the client thread. Four
            // rather than two so a single stalled handler cannot occupy the whole
            // server while a health check is in flight.
            created.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "uc-test-bridge");
                thread.setDaemon(true);
                return thread;
            }));
            created.start();

            server = created;
            port = created.getAddress().getPort();
            com.baggute.cheatclient.UtilityClient.LOGGER.info(
                    "[TestBridge] listening on 127.0.0.1:{}", port);
        } catch (IOException failed) {
            STARTED.set(false);
            com.baggute.cheatclient.UtilityClient.LOGGER.error(
                    "[TestBridge] could not bind port {}", requested, failed);
        }
    }

    /** The bound port, or -1 when the bridge is not running. */
    public static int port() { return port; }

    /** Stops the bridge. Called on unload so a dev client does not leave a socket open. */
    public static void stop() {
        HttpServer running = server;
        if (running != null) {
            running.stop(0);
            server = null;
            port = -1;
        }
        STARTED.set(false);
    }

    // ---- routing ----

    private static void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            switch (path) {
                case "/combo-checks" -> write(exchange, onClient(() -> method.equals("POST")
                        ? ComboRegressionChecks.start("xbow".equals(query.get("mode"))) : ComboRegressionChecks.status()));
                case "/regressions" -> write(exchange, method.equals("POST")
                        ? onClient(LiveRegressionChecks::run) : failure("POST required"));
                case "/exit-client" -> {
                    if (!method.equals("POST")) { write(exchange, failure("POST required")); break; }
                    write(exchange, ok(new JsonObject()));
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                }
                case "/health" -> write(exchange, health());
                case "/state" -> write(exchange, state());
                case "/world" -> write(exchange, world(query));
                case "/modules" -> write(exchange, modules());
                case "/module" -> write(exchange, method.equals("POST")
                        ? toggle(query)
                        : failure("POST required"));
                case "/stages" -> write(exchange, stages());
                case "/reset" -> write(exchange, method.equals("POST")
                        ? resetCounters(query)
                        : failure("POST required"));
                case "/input" -> write(exchange, method.equals("POST")
                        ? input(query)
                        : failure("POST required"));
                case "/command" -> write(exchange, method.equals("POST")
                        ? command(query)
                        : failure("POST required"));
                case "/log" -> write(exchange, log(query));
                case "/shutdown" -> {
                    // Deliberately not a client-thread call: this only stops the listener.
                    write(exchange, ok(new JsonObject()));
                    stop();
                }
                default -> write(exchange, failure("unknown path: " + path));
            }
        } catch (Throwable failure) {
            try {
                write(exchange, failure("bridge error: " + describe(failure)));
            } catch (Throwable ignored) {
                // The exchange is already gone; there is nowhere left to report to.
            }
        } finally {
            exchange.close();
        }
    }

    // ---- endpoints ----

    /** Liveness and identity. Answers from any thread; touches nothing in the world. */
    private static JsonObject health() {
        JsonObject body = new JsonObject();
        body.addProperty("pid", ProcessHandle.current().pid());
        body.addProperty("port", port);
        body.addProperty("uptimeMs", System.currentTimeMillis() - ModuleStats.startedAt());
        body.addProperty("clientTicks", ModuleStats.clientTicks());
        body.addProperty("clientFrames", ModuleStats.clientFrames());
        return ok(body);
    }

    /** Player and world snapshot - the "did anything move" half of the audit signal. */
    private static JsonObject state() {
        JsonObject data = onClient(TestBridge::stateNow);
        return data == null ? failure("client thread did not answer in time") : ok(data);
    }

    private static JsonObject stateNow() {
        JsonObject data = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        data.addProperty("inWorld", player != null && mc.level != null);
        data.addProperty("paused", mc.isPaused());
        data.addProperty("fps", mc.getFps());
        data.addProperty("clientTicks", ModuleStats.clientTicks());
        data.addProperty("clientFrames", ModuleStats.clientFrames());

        if (player == null || mc.level == null) return data;

        data.addProperty("dimension", mc.level.dimension().identifier().toString());
        data.addProperty("x", round(player.getX()));
        data.addProperty("y", round(player.getY()));
        data.addProperty("z", round(player.getZ()));
        data.addProperty("yaw", round(player.getYRot()));
        data.addProperty("pitch", round(player.getXRot()));
        data.addProperty("health", round(player.getHealth()));
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("sprinting", player.isSprinting());
        data.addProperty("onGround", player.onGround());
        data.addProperty("sneaking", player.isShiftKeyDown());
        data.addProperty("vehicle", player.getVehicle() != null);

        int slot = player.getInventory().getSelectedSlot();
        data.addProperty("selectedSlot", slot);
        data.addProperty("heldItem", itemPath(player.getInventory().getItem(slot)));

        // The whole hotbar, not just the held slot: most automation modules need a
        // specific item somewhere in the row, and "the kit is not there" is a
        // different answer from "the module is broken".
        JsonArray hotbar = new JsonArray();
        for (int index = 0; index < 9; index++) {
            hotbar.add(itemPath(player.getInventory().getItem(index)));
        }
        data.add("hotbar", hotbar);

        BlockPos pos = player.blockPosition();
        data.addProperty("blockX", pos.getX());
        data.addProperty("blockY", pos.getY());
        data.addProperty("blockZ", pos.getZ());

        // The four settings a large share of the modules exist to flip. Reported as
        // booleans rather than as raw key state so the audit reads effects, not inputs.
        data.addProperty("keyAttack", mc.options.keyAttack.isDown());
        data.addProperty("keyUse", mc.options.keyUse.isDown());
        data.addProperty("keyJump", mc.options.keyJump.isDown());
        data.addProperty("keySprint", mc.options.keySprint.isDown());

        // StepUp's whole effect is a static the movement mixin reads, so the block
        // the player can walk up is the only observable it has. Reported raw rather
        // than as "enabled" because a module that sets the wrong number is broken in
        // a way a boolean cannot show.
        data.addProperty("stepHeight", com.baggute.cheatclient.module.movement.StepUp.stepHeight);

        return data;
    }

    /**
     * Holds or releases a vanilla key.
     *
     * <p>Half the combat modules are gated on {@code keyAttack} and half the movement
     * modules on a direction, and a module that never sees its input never runs. An
     * audit that cannot press a key can only measure the modules that act unprompted,
     * which is the smallest half of the catalogue.
     *
     * <p>This sets the mapping's state, not the OS key. {@code KeyMapping#setDown} is
     * the same call the input handler makes, so every consumer - vanilla and module
     * alike - reads it exactly as it reads a real press.
     */
    private static JsonObject input(Map<String, String> query) {
        String key = query.get("key");
        String down = query.get("down");
        if (key == null || key.isBlank()) return failure("missing ?key=");
        if (down == null || down.isBlank()) return failure("missing ?down=true|false");
        boolean pressed = Boolean.parseBoolean(down.trim());

        JsonObject result = onClient(() -> inputNow(key.trim(), pressed));
        return result == null ? failure("client thread did not answer in time") : result;
    }

    private static JsonObject inputNow(String key, boolean pressed) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        net.minecraft.client.KeyMapping mapping = switch (key.toLowerCase(Locale.ROOT)) {
            case "attack", "left" -> mc.options.keyAttack;
            case "use", "right" -> mc.options.keyUse;
            case "jump" -> mc.options.keyJump;
            case "sprint" -> mc.options.keySprint;
            case "sneak", "shift" -> mc.options.keyShift;
            case "forward", "up" -> mc.options.keyUp;
            case "back", "down" -> mc.options.keyDown;
            case "left_strafe" -> mc.options.keyLeft;
            case "right_strafe" -> mc.options.keyRight;
            default -> null;
        };
        if (mapping == null) {
            body.addProperty("ok", false);
            body.addProperty("error", "unknown key: " + key);
            return body;
        }
        mapping.setDown(pressed);
        body.addProperty("ok", true);
        body.addProperty("key", key);
        body.addProperty("down", mapping.isDown());
        return body;
    }

    /**
     * Runs a vanilla command as the player.
     *
     * <p>The audit needs a world it can test in: a kit in the hotbar, a target to aim
     * at, a block to walk into. Building that from Java would mean reimplementing
     * what {@code /give}, {@code /summon} and {@code /setblock} already do correctly,
     * and a world built by a second implementation is a world whose behaviour proves
     * nothing about the real one.
     *
     * <p>Requires cheats enabled in the world. When they are not, the server refuses
     * and the audit reports SKIP rather than inventing a verdict.
     */
    private static JsonObject command(Map<String, String> query) {
        String raw = query.get("cmd");
        if (raw == null || raw.isBlank()) return failure("missing ?cmd=");

        JsonObject result = onClient(() -> commandNow(raw.trim()));
        return result == null ? failure("client thread did not answer in time") : result;
    }

    private static JsonObject commandNow(String raw) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        if (mc.player == null || mc.player.connection == null) {
            body.addProperty("ok", false);
            body.addProperty("error", "not connected");
            return body;
        }
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        try {
            mc.player.connection.sendCommand(command);
        } catch (Throwable failure) {
            body.addProperty("ok", false);
            body.addProperty("error", describe(failure));
            return body;
        }
        body.addProperty("ok", true);
        body.addProperty("command", command);
        return body;
    }

    /**
     * A change detector for the world around the player.
     *
     * <p>The audit needs to know whether a placement module actually placed
     * something. Position does not move, health does not change, and the module's
     * own counters only prove its tick ran. A rolling hash of every block in a cube
     * around the player does change, and it changes for exactly one reason.
     *
     * <p>{@code System.identityHashCode} on the {@code BlockState} is stable here
     * because block states are interned per block-and-variant and live for the whole
     * session. Two reads of an unchanged cell return the same instance; a placed
     * block returns a different one. That is all a detector needs, and it avoids a
     * registry lookup per cell.
     */
    private static JsonObject world(Map<String, String> query) {
        int radius = 8;
        String raw = query.get("radius");
        if (raw != null && !raw.isBlank()) {
            try {
                radius = Math.max(1, Math.min(16, Integer.parseInt(raw.trim())));
            } catch (NumberFormatException ignored) {
                // Keep the default.
            }
        }
        final int span = radius;
        JsonObject data = onClient(() -> worldNow(span));
        return data == null ? failure("client thread did not answer in time") : ok(data);
    }

    private static JsonObject worldNow(int radius) {
        JsonObject data = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            data.addProperty("inWorld", false);
            return data;
        }

        data.addProperty("inWorld", true);

        BlockPos centre = player.blockPosition();
        BlockPos from = centre.offset(-radius, -radius, -radius);
        BlockPos to = centre.offset(radius, radius, radius);

        long hash = 1125899906842597L;
        int cells = 0;
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            hash = 31L * hash + System.identityHashCode(mc.level.getBlockState(pos));
            cells++;
        }
        data.addProperty("hash", hash);
        data.addProperty("cells", cells);
        data.addProperty("radius", radius);

        int entities = 0;
        for (net.minecraft.world.entity.Entity ignored : mc.level.entitiesForRendering()) entities++;
        data.addProperty("entities", entities);

        // Total health of every living entity near the player, hashed.
        //
        // Without this the world snapshot could not see damage at all: entity count
        // only changes when something dies, so a module whose whole effect is a hit -
        // Triggerbot, KillAura, AntiMiss, Reach - looked like it did nothing until it
        // happened to kill the target. A sum rather than per-entity detail keeps the
        // payload small and still moves whenever any nearby mob loses a heart.
        long entityHealth = 0L;
        int living = 0;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity livingEntity)) continue;
            if (livingEntity == player) continue;
            if (livingEntity.distanceToSqr(player) > 64.0) continue;
            entityHealth += Math.round(livingEntity.getHealth() * 100.0f);
            living++;
        }
        data.addProperty("entityHealth", entityHealth);
        data.addProperty("livingEntities", living);

        // Hotbar contents, hashed the same way: a swap module that never sends a
        // carried-item packet still moves this, and one that does moves the held item.
        long inventoryHash = 17L;
        for (int slot = 0; slot < 9; slot++) {
            inventoryHash = 31L * inventoryHash
                    + itemPath(player.getInventory().getItem(slot)).hashCode();
        }
        data.addProperty("inventoryHash", inventoryHash);

        return data;
    }

    /** Every module: identity, description, state, counters, stage, settings. */
    private static JsonObject modules() {
        JsonObject data = onClient(TestBridge::modulesNow);
        return data == null ? failure("client thread did not answer in time") : ok(data);
    }

    private static JsonObject modulesNow() {
        JsonObject data = new JsonObject();
        ModuleManager manager = com.baggute.cheatclient.UtilityClient.moduleManager;
        if (manager == null) {
            data.addProperty("count", 0);
            data.add("modules", new JsonArray());
            return data;
        }

        List<Module> all = manager.getModules();
        JsonArray array = new JsonArray();
        for (Module module : all) array.add(describeModule(module));

        data.addProperty("count", all.size());
        data.add("modules", array);
        return data;
    }

    private static JsonObject describeModule(Module module) {
        JsonObject entry = new JsonObject();
        String name = module.getName();

        entry.addProperty("name", name);
        entry.addProperty("description", module.getDescription());
        entry.addProperty("category", module.getCategory().name());
        entry.addProperty("enabled", module.isEnabled());
        // The concrete class is what tells the audit a label-only stub from a module
        // that has an implementation behind it. Two modules can carry the same name
        // shape and only one of them does anything.
        entry.addProperty("class", module.getClass().getSimpleName());

        ModuleStats.Counters counters = ModuleStats.of(name);
        entry.addProperty("ticks", counters.ticks());
        entry.addProperty("renders", counters.renders());
        entry.addProperty("errors", counters.errors());
        entry.addProperty("lastError", counters.lastError());
        entry.addProperty("lastErrorAt", counters.lastErrorAt());

        entry.addProperty("stage", Debug.currentStage(name));
        entry.addProperty("transitions", Debug.transitions(name));
        JsonArray history = new JsonArray();
        for (Debug.Sample sample : Debug.history(name)) history.add(sample.text());
        entry.add("history", history);

        JsonArray settings = new JsonArray();
        for (Setting setting : module.getSettings()) {
            JsonObject described = new JsonObject();
            described.addProperty("name", setting.getName());
            described.addProperty("description", setting.getDescription());
            described.addProperty("type", setting.getClass().getSimpleName()
                    .replace("Setting", "").toLowerCase(Locale.ROOT));
            JsonElement value;
            try {
                value = setting.toJson();
            } catch (Throwable unreadable) {
                value = JsonNull.INSTANCE;
            }
            described.add("value", value == null ? JsonNull.INSTANCE : value);
            settings.add(described);
        }
        entry.add("settings", settings);

        return entry;
    }

    /**
     * Switches one module on or off.
     *
     * <p>{@code reset=1} (the default) zeroes the module's counters and stage history
     * first, so the caller's next read is a delta from this moment. That is the whole
     * point of the endpoint: the audit enables, waits, reads, and the read must
     * describe only the window it just opened.
     */
    private static JsonObject toggle(Map<String, String> query) {
        String name = query.get("name");
        String on = query.get("on");
        if (name == null || name.isBlank()) return failure("missing ?name=");
        if (on == null || on.isBlank()) return failure("missing ?on=true|false");
        boolean enabled = Boolean.parseBoolean(on.trim());
        boolean reset = !"0".equals(query.get("reset"));

        JsonObject result = onClient(() -> toggleNow(name, enabled, reset));
        return result == null ? failure("client thread did not answer in time") : result;
    }

    private static JsonObject toggleNow(String name, boolean enabled, boolean reset) {
        ModuleManager manager = com.baggute.cheatclient.UtilityClient.moduleManager;
        JsonObject body = new JsonObject();
        if (manager == null) {
            body.addProperty("ok", false);
            body.addProperty("error", "module manager not ready");
            return body;
        }

        Module module = manager.getByName(name);
        if (module == null) {
            body.addProperty("ok", false);
            body.addProperty("error", "no module named " + name);
            return body;
        }

        boolean wasEnabled = module.isEnabled();
        if (reset) {
            ModuleStats.reset(name);
            Debug.clear(name);
        }

        try {
            module.setEnabled(enabled);
        } catch (Throwable failure) {
            // A module whose onEnable throws is exactly what the audit is looking for.
            ModuleStats.error(name, failure);
            body.addProperty("ok", false);
            body.addProperty("error", "setEnabled threw: " + describe(failure));
            body.addProperty("wasEnabled", wasEnabled);
            body.addProperty("enabled", module.isEnabled());
            return body;
        }

        ModuleStats.markEnabled(name, module.isEnabled());

        body.addProperty("ok", true);
        body.addProperty("name", name);
        body.addProperty("wasEnabled", wasEnabled);
        body.addProperty("enabled", module.isEnabled());
        body.addProperty("accepted", module.isEnabled() == enabled);
        return body;
    }

    /** The stage board, plus history and transition counts. */
    private static JsonObject stages() {
        JsonObject data = new JsonObject();

        JsonArray overlay = new JsonArray();
        for (String line : Debug.snapshot()) overlay.add(line);
        data.add("overlay", overlay);

        JsonObject byModule = new JsonObject();
        for (String name : Debug.trackedModules()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("current", Debug.currentStage(name));
            entry.addProperty("transitions", Debug.transitions(name));
            JsonArray history = new JsonArray();
            for (Debug.Sample sample : Debug.history(name)) history.add(sample.text());
            entry.add("history", history);
            byModule.add(name, entry);
        }
        data.add("modules", byModule);

        return ok(data);
    }

    /** Zeroes counters for one module, or for every module when {@code ?name=} is absent. */
    private static JsonObject resetCounters(Map<String, String> query) {
        String name = query.get("name");
        JsonObject data = new JsonObject();
        if (name == null || name.isBlank()) {
            int cleared = 0;
            for (String tracked : ModuleStats.all().keySet()) {
                ModuleStats.reset(tracked);
                cleared++;
            }
            for (String tracked : Debug.trackedModules()) {
                Debug.clear(tracked);
                cleared++;
            }
            data.addProperty("cleared", cleared);
            return ok(data);
        }
        ModuleStats.reset(name);
        Debug.clear(name);
        data.addProperty("cleared", 1);
        data.addProperty("name", name);
        return ok(data);
    }

    /**
     * Tail of the client log.
     *
     * <p>Read from disk rather than from an in-memory ring because {@code LogManager}
     * is the only writer and it already owns the file. Duplicating the sink would be a
     * second copy that can disagree with the artifact a developer actually reads.
     */
    private static JsonObject log(Map<String, String> query) {
        int lines = DEFAULT_LOG_LINES;
        String raw = query.get("lines");
        if (raw != null && !raw.isBlank()) {
            try {
                lines = Math.max(1, Math.min(MAX_LOG_LINES, Integer.parseInt(raw.trim())));
            } catch (NumberFormatException ignored) {
                // Keep the default; a malformed count is not worth an error response.
            }
        }

        JsonObject data = new JsonObject();
        Path file;
        try {
            file = com.baggute.cheatclient.stealth.LogManager.logFile();
        } catch (Throwable unresolved) {
            data.addProperty("ok", false);
            data.addProperty("error", "log path unavailable: " + describe(unresolved));
            return data;
        }

        data.addProperty("path", file.toString());
        data.addProperty("exists", Files.isRegularFile(file));

        try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long start = Math.max(0L, reader.length() - 1024L * 1024L);
            reader.seek(start);
            byte[] bytes = new byte[(int) (reader.length() - start)];
            reader.readFully(bytes);
            String sample = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstBreak = sample.indexOf('\n');
                sample = firstBreak < 0 ? "" : sample.substring(firstBreak + 1);
            }
            String[] all = sample.split("\n");
            JsonArray tail = new JsonArray();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) tail.add(all[i]);
            data.add("lines", tail);
            data.addProperty("total", all.length);
            data.addProperty("truncated", start > 0);
        } catch (IOException unreadable) {
            data.addProperty("ok", false);
            data.addProperty("error", "log unreadable: " + describe(unreadable));
            return data;
        }
        return ok(data);
    }

    // ---- marshalling ----

    /**
     * Runs {@code work} on the client thread and waits for it, bounded.
     *
     * <p>{@code Minecraft#submit} already returns a future and runs inline when the
     * caller is the client thread, so there is no thread check here. The timeout is
     * the only thing this adds: an unanswered call becomes {@code null} rather than a
     * stuck HTTP request.
     */
    private static <T> T onClient(java.util.function.Supplier<T> work) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLIENT_TIMEOUT_MS);
        CompletableFuture<T> future = Minecraft.getInstance().submit(() -> {
            if (cancelled.get() || System.nanoTime() >= deadline) return null;
            return work.get();
        });
        try {
            return future.get(CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            cancelled.set(true);
            future.cancel(false);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            com.baggute.cheatclient.UtilityClient.LOGGER.warn("[TestBridge] client task failed", failure);
            return null;
        }
    }

    // ---- helpers ----

    private static String itemPath(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static JsonObject ok(JsonObject body) {
        body.addProperty("ok", true);
        return body;
    }

    private static JsonObject failure(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error", message);
        return body;
    }

    private static void write(HttpExchange exchange, JsonObject body) throws IOException {
        if (body == null) body = failure("Client task failed or timed out; inspect the development log");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }
}
