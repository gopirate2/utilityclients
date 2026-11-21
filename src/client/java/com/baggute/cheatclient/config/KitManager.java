// language: Java, file: KitManager.java, target: Fabric 26.2
package com.baggute.cheatclient.config;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.util.InventoryUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inventory kits: the exact contents of the player's 41 slots, saved as JSON.
 *
 * <p>Each entry is {@code {slot, itemId, count, nbt}} where {@code nbt} is the
 * item's full component patch as JSON - which is what modern versions call the
 * data that used to live under the "tag" key. {@code itemId} and {@code count}
 * are duplicated alongside it on purpose: a kit whose components cannot be
 * decoded still rebuilds the base item instead of losing the row.
 *
 * <p>Slot numbering is the player inventory's own: 0-8 hotbar, 9-35 main,
 * 36-39 armour, 40 offhand. Creative placement maps those onto
 * {@code InventoryMenu} slot ids, which the server expects for
 * {@code ServerboundSetCreativeModeSlotPacket}.
 */
public final class KitManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand. */
    public static final int INVENTORY_SLOTS = 41;

    private KitManager() {}

    // ---- storage ----

    private static Path kitDir() {
        return Storage.root().resolve("kits");
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        Path dir = kitDir();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                String file = path.getFileName().toString();
                out.add(file.substring(0, file.length() - 5));
            }
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Kit] failed to list {}", dir, exception);
        }
        Collections.sort(out);
        return out;
    }

    public static boolean delete(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        try {
            return Files.deleteIfExists(kitDir().resolve(safe + ".json"));
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Kit] failed to delete {}", safe, exception);
            return false;
        }
    }

    // ---- capture ----

    /** Snapshots the current inventory under {@code name}. */
    public static boolean saveCurrent(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        JsonObject root = capture(mc);
        Path target = kitDir().resolve(safe + ".json");
        try {
            Files.createDirectories(target.getParent());
            com.baggute.cheatclient.util.AtomicFiles.writeString(target, GSON.toJson(root));
            return true;
        } catch (IOException exception) {
            UtilityClient.LOGGER.error("[Kit] failed to write {}", target, exception);
            return false;
        }
    }

    /** The player's inventory as a kit object, without touching the disk. */
    public static JsonObject capture(Minecraft mc) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("saved", System.currentTimeMillis());

        JsonArray slots = new JsonArray();
        if (mc.player != null) {
            var inventory = mc.player.getInventory();
            int size = Math.min(INVENTORY_SLOTS, inventory.getContainerSize());
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot);

                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                entry.addProperty("itemId", id == null ? "minecraft:air" : id.toString());
                entry.addProperty("count", stack.getCount());

                JsonElement components = encodeStack(mc, stack);
                if (components != null) entry.add("nbt", components);
                slots.add(entry);
            }
        }
        root.add("slots", slots);
        return root;
    }

    // ---- load ----

    /**
     * Puts a saved kit back into the inventory.
     *
     * <p>Creative mode or the owned singleplayer server replaces all 41 slots,
     * including clearing positions omitted from the saved kit. A remote survival
     * server must authorize creating items; no local-only inventory is fabricated.
     *
     * @return the number of slots that could not be filled
     */
    public static int load(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return -1;
        Path source = kitDir().resolve(safe + ".json");
        if (!Files.exists(source)) return -1;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return -1;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            return apply(mc, root);
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Kit] failed to load {}", source, exception);
            return -1;
        }
    }

    /**
     * A saved kit decoded into inventory slots, for the click GUI's preview grid.
     *
     * <p>Always returns the full {@link #INVENTORY_SLOTS} array with every
     * unset entry already {@link ItemStack#EMPTY}, so the caller can index any
     * slot without a null check. A missing file, malformed JSON and an
     * undecodable component all degrade to an empty slot rather than an
     * exception: the preview is a picture of the kit, not the load path, and it
     * must never be able to break the screen it is drawn on.
     */
    public static ItemStack[] read(String name) {
        ItemStack[] out = new ItemStack[INVENTORY_SLOTS];
        java.util.Arrays.fill(out, ItemStack.EMPTY);

        String safe = sanitize(name);
        if (safe.isEmpty()) return out;
        Path source = kitDir().resolve(safe + ".json");
        if (!Files.exists(source)) return out;

        Minecraft mc = Minecraft.getInstance();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            JsonArray slots = root.getAsJsonArray("slots");
            if (slots == null) return out;
            for (JsonElement element : slots) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                int slot = entry.has("slot") ? entry.get("slot").getAsInt() : -1;
                if (slot < 0 || slot >= INVENTORY_SLOTS) continue;
                out[slot] = decodeStack(mc, entry);
            }
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Kit] failed to read {} for preview", source, exception);
        }
        return out;
    }

    private static Job pending;
    private static final class Job {
        final net.minecraft.client.player.LocalPlayer player;
        final ItemStack[] wanted = new ItemStack[INVENTORY_SLOTS];
        final boolean[] reserved = new boolean[INVENTORY_SLOTS];
        final java.util.ArrayDeque<int[]> clicks = new java.util.ArrayDeque<>();
        ItemStack expectedCursor = ItemStack.EMPTY;
        ItemStack[] expectedInventory;
        int origin = -1, ticks, delay;
        Job(net.minecraft.client.player.LocalPlayer player) { this.player = player; java.util.Arrays.fill(wanted, ItemStack.EMPTY); }
    }

    /** Replaces the entire inventory where the client has authoritative access. */
    public static int apply(Minecraft mc, JsonObject kit) {
        if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty() || pending != null) return -1;
        JsonArray slots = kit.getAsJsonArray("slots");
        if (slots == null) return -1;
        Job job = new Job(mc.player);
        for (JsonElement element : slots) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            int slot = entry.has("slot") ? entry.get("slot").getAsInt() : -1;
            if (slot >= 0 && slot < INVENTORY_SLOTS) job.wanted[slot] = decodeStack(mc, entry);
        }
        if (mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            var id = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(id);
                if (player == null) return;
                for (int slot = 0; slot < INVENTORY_SLOTS; slot++) player.getInventory().setItem(slot, job.wanted[slot].copy());
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
            });
            return 0;
        }
        if (canGenerateItems(mc)) {
            for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
                ItemStack stack = job.wanted[slot];
                mc.gameMode.handleCreativeModeItemAdd(stack, menuSlotFor(slot));
                mc.player.getInventory().setItem(slot, stack.copy());
            }
            return 0;
        }
        com.baggute.cheatclient.module.misc.Notifications.push("Kit replacement requires creative mode or your own singleplayer world; inventory kept.", false);
        return -1;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    /** One acknowledged menu click at a time; each filled destination is reserved. */
    public static void tick(Minecraft mc) {
        Job job = pending;
        if (job == null) return;
        if (mc.player != job.player || mc.gameMode == null || mc.level == null) { pending = null; return; }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (++job.ticks > 4096) {
            ItemStack carried = mc.player.inventoryMenu.getCarried();
            if (!carried.isEmpty()) {
                for (int slot = 9; slot <= 44; slot++) {
                    var target = mc.player.inventoryMenu.getSlot(slot);
                    ItemStack stored = target.getItem();
                    if (target.mayPlace(carried) && (stored.isEmpty() || (same(stored, carried)
                            && stored.getCount() + carried.getCount() <= target.getMaxStackSize(carried)))) {
                        com.baggute.cheatclient.util.Stealth.swapSlot(mc, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
                        break;
                    }
                }
            }
            if (mc.player.inventoryMenu.getCarried().isEmpty()) pending = null;
            return;
        }
        if (job.delay-- > 0 || com.baggute.cheatclient.util.Stealth.swapBlocked()) return;
        if (!job.clicks.isEmpty()) {
            ItemStack cursor = mc.player.inventoryMenu.getCarried();
            if (!exact(cursor, job.expectedCursor) || !inventoryMatches(mc, job.expectedInventory)) {
                job.clicks.clear();
                job.ticks = 4096;
                UtilityClient.LOGGER.warn("[Kit] inventory changed during a move; cancelling remaining clicks");
                return;
            }
            int[] click = job.clicks.peek();
            if (!com.baggute.cheatclient.util.Stealth.swapSlot(mc, click[0], click[1], net.minecraft.world.inventory.ContainerInput.PICKUP)) return;
            job.clicks.remove();
            job.expectedCursor = mc.player.inventoryMenu.getCarried().copy();
            snapshot(mc, job);
            job.delay = 1;
            return;
        }
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) return;
        for (int destination = 0; destination < INVENTORY_SLOTS; destination++) {
            ItemStack wanted = job.wanted[destination];
            if (job.reserved[destination] || wanted.isEmpty()) continue;
            ItemStack current = mc.player.getInventory().getItem(destination);
            if (same(current, wanted) && current.getCount() == wanted.getCount()) {
                job.reserved[destination] = true; continue;
            }
            if (!current.isEmpty() && !same(current, wanted)) {
                int empty = emptySlot(mc, job, destination);
                if (empty >= 0) move(mc, job, destination, empty, current.getCount(), current.getCount());
                else {
                    int source = findHolding(mc, wanted, job.reserved, destination);
                    if (source < 0 || !mc.player.inventoryMenu.getSlot(menuSlotFor(source)).mayPlace(current)
                            || !mc.player.inventoryMenu.getSlot(menuSlotFor(destination)).mayPlace(wanted)) continue;
                    exchange(mc, job, source, destination);
                }
                return;
            }
            if (same(current, wanted) && current.getCount() > wanted.getCount()) {
                int storage = storageSlot(mc, job, destination, current);
                if (storage < 0) continue;
                ItemStack stored = mc.player.getInventory().getItem(storage);
                int capacity = Math.min(current.getMaxStackSize(), mc.player.inventoryMenu
                        .getSlot(menuSlotFor(storage)).getMaxStackSize(current)) - stored.getCount();
                move(mc, job, destination, storage, Math.min(capacity,
                        current.getCount() - wanted.getCount()), current.getCount());
                return;
            }
            int source = findHolding(mc, wanted, job.reserved, destination);
            if (source < 0) continue;
            int have = mc.player.getInventory().getItem(source).getCount();
            if (!mc.player.inventoryMenu.getSlot(menuSlotFor(destination)).mayPlace(wanted)) continue;
            int capacity = mc.player.inventoryMenu.getSlot(menuSlotFor(destination)).getMaxStackSize(wanted) - current.getCount();
            if (capacity <= 0) continue;
            move(mc, job, source, destination, Math.min(capacity, Math.min(have, wanted.getCount() - current.getCount())), have);
            return;
        }
        int unfilled = 0;
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++)
            if (!job.wanted[slot].isEmpty() && !exact(mc.player.getInventory().getItem(slot), job.wanted[slot])) unfilled++;
        if (unfilled > 0) UtilityClient.LOGGER.warn("[Kit] finished with {} unfilled slots (missing items or storage capacity)", unfilled);
        else UtilityClient.LOGGER.info("[Kit] queued kit completed");
        pending = null;
    }

    private static int emptySlot(Minecraft mc, Job job, int avoid) {
        for (int slot = 9; slot < 36; slot++)
            if (slot != avoid && !job.reserved[slot] && mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        return -1;
    }
    private static int storageSlot(Minecraft mc, Job job, int avoid, ItemStack stack) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = mc.player.getInventory().getItem(slot);
            if (slot != avoid && !job.reserved[slot] && (current.isEmpty() || same(current, stack))
                    && current.getCount() < stack.getMaxStackSize()) return slot;
        }
        return -1;
    }
    private static boolean exact(ItemStack a, ItemStack b) {
        return (a.isEmpty() && b.isEmpty()) || (same(a, b) && a.getCount() == b.getCount());
    }
    private static void snapshot(Minecraft mc, Job job) {
        job.expectedInventory = new ItemStack[INVENTORY_SLOTS];
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++)
            job.expectedInventory[slot] = mc.player.getInventory().getItem(slot).copy();
    }
    private static boolean inventoryMatches(Minecraft mc, ItemStack[] expected) {
        if (expected == null) return false;
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++)
            if (!exact(mc.player.getInventory().getItem(slot), expected[slot])) return false;
        return true;
    }
    private static void exchange(Minecraft mc, Job job, int source, int destination) {
        int from = menuSlotFor(source), to = menuSlotFor(destination);
        job.origin = from;
        job.expectedCursor = ItemStack.EMPTY;
        snapshot(mc, job);
        job.clicks.add(new int[]{from, 0});
        job.clicks.add(new int[]{to, 0});
        job.clicks.add(new int[]{from, 0});
    }
    private static void move(Minecraft mc, Job job, int source, int destination, int amount, int available) {
        if (amount <= 0) return;
        int from = menuSlotFor(source), to = menuSlotFor(destination);
        job.origin = from;
        job.expectedCursor = ItemStack.EMPTY;
        snapshot(mc, job);
        job.clicks.add(new int[]{from, 0});
        if (amount == available) job.clicks.add(new int[]{to, 0});
        else {
            for (int n = 0; n < amount; n++) job.clicks.add(new int[]{to, 1});
            job.clicks.add(new int[]{from, 0});
        }
    }

    public static boolean canGenerateItems(Minecraft mc) {
        return mc.player != null && mc.player.isCreative();
    }

    public static int menuSlotFor(int slot) {
        if (slot < 0 || slot > 40) return -1;
        if (slot < 9) return 36 + slot;
        if (slot < 36) return slot;
        if (slot < 40) return 8 - (slot - 36);
        return 45;
    }

    // ---- sharing ----

    public static boolean exists(String name) {
        String safe = sanitize(name);
        return !safe.isEmpty() && Files.exists(kitDir().resolve(safe + ".json"));
    }

    /** The kit on disk as a share string, or "" when it does not exist. */
    public static String share(String name) {
        String safe = sanitize(name);
        if (safe.isEmpty()) return "";
        Path source = kitDir().resolve(safe + ".json");
        if (!Files.exists(source)) return "";
        try {
            return ShareCode.encodeKit(JsonParser.parseString(Files.readString(source)));
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Kit] failed to share {}", safe, exception);
            return "";
        }
    }

    /** Imports a share string under {@code name}; false on a malformed payload. */
    public static boolean importCode(String name, String code) {
        String safe = sanitize(name);
        if (safe.isEmpty() || code == null || code.isBlank()) return false;
        try {
            JsonObject kit = decodeImport(code);
            Path target = kitDir().resolve(safe + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(kit), java.nio.file.StandardOpenOption.CREATE_NEW);
            return true;
        } catch (Exception exception) {
            UtilityClient.LOGGER.error("[Kit] failed to import {}", safe, exception);
            return false;
        }
    }

    /** Validates clipboard data before it is saved; no world or item registry is needed. */
    public static JsonObject decodeImport(String code) throws IOException {
        if (code == null || code.isBlank() || code.length() > 4 * 1024 * 1024)
            throw new IllegalArgumentException("kit code is empty or too large");
        String text = code.trim();
        JsonElement decoded = text.startsWith("{") ? JsonParser.parseString(text) : ShareCode.decodeKit(text);
        if (!decoded.isJsonObject()) throw new IllegalArgumentException("kit must be an object");
        JsonObject kit = decoded.getAsJsonObject();
        JsonElement rows = kit.get("slots");
        if (rows == null || !rows.isJsonArray() || rows.getAsJsonArray().size() > INVENTORY_SLOTS)
            throw new IllegalArgumentException("kit must contain at most 41 inventory slots");
        if (kit.has("format") && exactInteger(kit.get("format")) != 1)
            throw new IllegalArgumentException("unsupported kit format");
        boolean[] used = new boolean[INVENTORY_SLOTS];
        for (JsonElement row : rows.getAsJsonArray()) {
            if (!row.isJsonObject()) throw new IllegalArgumentException("invalid kit row");
            JsonObject entry = row.getAsJsonObject();
            int slot = exactInteger(entry.get("slot"));
            if (slot < 0 || slot >= INVENTORY_SLOTS || used[slot])
                throw new IllegalArgumentException("invalid or duplicate kit slot");
            used[slot] = true;
            JsonElement item = entry.get("itemId");
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || Identifier.tryParse(item.getAsString()) == null)
                throw new IllegalArgumentException("invalid item ID");
            if (entry.has("count") && exactInteger(entry.get("count")) <= 0)
                throw new IllegalArgumentException("item count must be positive");
            if (entry.has("nbt") && !entry.get("nbt").isJsonObject())
                throw new IllegalArgumentException("item component data must be an object");
        }
        return kit;
    }

    private static int exactInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("expected an integer");
        return value.getAsBigDecimal().intValueExact();
    }

    // ---- codec helpers ----

    private static JsonElement encodeStack(Minecraft mc, ItemStack stack) {
        if (mc.level == null) return null;
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
            return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        } catch (Exception exception) {
            UtilityClient.LOGGER.warn("[Kit] could not encode {}", stack, exception);
            return null;
        }
    }

    private static ItemStack decodeStack(Minecraft mc, JsonObject entry) {
        if (entry.has("nbt") && mc.level != null) {
            try {
                RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, mc.level.registryAccess());
                ItemStack parsed = ItemStack.CODEC.parse(ops, entry.get("nbt")).result().orElse(ItemStack.EMPTY);
                if (!parsed.isEmpty()) return parsed;
            } catch (Exception exception) {
                UtilityClient.LOGGER.warn("[Kit] component decode failed, falling back to item id", exception);
            }
        }
        String id = entry.has("itemId") ? entry.get("itemId").getAsString() : "minecraft:air";
        int count = entry.has("count") ? Math.max(1, entry.get("count").getAsInt()) : 1;
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(identifier);
        if (item == null) return ItemStack.EMPTY;
        return new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
    }

    private static int findHolding(Minecraft mc, ItemStack wanted, boolean[] reserved, int destination) {
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++)
            if (slot != destination && !reserved[slot] && same(mc.player.getInventory().getItem(slot), wanted)) return slot;
        return -1;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("[^a-zA-Z0-9 _-]", "").replace(' ', '_');
    }
}
