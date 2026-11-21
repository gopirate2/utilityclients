package com.baggute.cheatclient;

import com.baggute.cheatclient.module.apex.ApexImpl;
import com.baggute.cheatclient.module.player.InvManager;
import com.baggute.cheatclient.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Concrete repair regressions, using real inventory/effect/ability helpers. */
public final class RepairChecks {
    public static void run() throws Exception {
        atomicSaves();
        abilityOwnership();
        scanLimits();
        databaseAcknowledgement();
        MinecraftBootstrap.initialize();
        DeepRepairChecks.run();
        BaritoneRepairChecks.run();
        inventoryDuplicates();
        healingSelection();
        BlockPos feet = new BlockPos(4, 65, 8);
        require(ApexImpl.webTarget(feet, false, true).equals(feet.above()), "head-only web selected");
        require(ApexImpl.webTarget(feet, true, true).equals(feet), "feet-first web selected");
        require(ApexImpl.webTarget(feet, false, false) == null, "unrelated block never selected");
        System.out.println("PASS: atomic saves, flight ownership, scan budgets/nearest bounds, POST acknowledgement, duplicate sorting, healing effects and matched cobweb");
    }

    private static void atomicSaves() throws Exception {
        Path directory = Files.createTempDirectory("uc-atomic-check-");
        try {
            Path target = directory.resolve("settings.json");
            AtomicFiles.writeString(target, "last good");
            try { AtomicFiles.commit(directory.resolve("missing.tmp"), target); throw new AssertionError("missing stage accepted"); }
            catch (java.io.IOException expected) { require(Files.readString(target).equals("last good"), "failed commit keeps old file"); }
            String replacement = "雪\n".repeat(100_000);
            AtomicFiles.writeString(target, replacement);
            require(Files.readString(target).equals(replacement), "complete UTF-8 save committed");
            Path blocked = Files.createDirectory(directory.resolve("blocked"));
            Files.writeString(blocked.resolve("preserved"), "old");
            try { AtomicFiles.writeString(blocked, "bad"); throw new AssertionError("directory replaced"); }
            catch (java.io.IOException expected) { require(Files.readString(blocked.resolve("preserved")).equals("old"), "failed write leaves destination intact"); }
            try (var paths = Files.list(directory)) {
                require(paths.noneMatch(p -> p.getFileName().toString().startsWith(".uc-")), "failed save cleans stages");
            }
        } finally { remove(directory); }
    }

    private static void abilityOwnership() {
        Object original = new Object(), replacement = new Object();
        Abilities abilities = new Abilities();
        abilities.mayfly = abilities.flying = true; abilities.setFlyingSpeed(0.17f);
        AbilityLease lease = new AbilityLease(original, abilities);
        lease.apply(0.052f); lease.restore(original);
        require(abilities.flying && abilities.mayfly && abilities.getFlyingSpeed() == 0.17f, "creative flight and custom speed retained");
        abilities.mayfly = true; abilities.flying = false;
        lease = new AbilityLease(original, abilities);
        lease.apply(0.052f); lease.restore(original);
        require(abilities.mayfly && !abilities.flying, "server flight grant retained");
        lease = new AbilityLease(original, abilities); lease.apply(0.052f);
        abilities.flying = abilities.mayfly = false; abilities.setFlyingSpeed(0.22f);
        lease.restore(original);
        require(!abilities.flying && !abilities.mayfly && abilities.getFlyingSpeed() == 0.22f, "external changes preserved");
        lease = new AbilityLease(original, abilities); lease.apply(0.052f);
        lease.restore(replacement);
        require(abilities.getFlyingSpeed() == 0.052f, "replacement identity cannot restore old lease");
        lease.restore(original);
    }

    private static void scanLimits() {
        ScanBudget budget = new ScanBudget(8192, Long.MAX_VALUE);
        int work = 0; while (budget.next()) work++;
        require(work == 8192 && budget.units() == 8192, "bounded per-frame work");
        require(!new ScanBudget(8192, 0).next(), "expired time budget yields immediately");
        NearestBlockSet set = new NearestBlockSet(BlockPos.ZERO, 3);
        for (int i = 100_000; i >= 1; i--) { set.add(new BlockPos(i, 0, 0)); set.add(new BlockPos(i, 0, 0)); }
        require(set.snapshot().equals(List.of(new BlockPos(1,0,0), new BlockPos(2,0,0), new BlockPos(3,0,0))), "nearest results bounded and deduplicated");
    }

    private static void databaseAcknowledgement() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var redirects = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/post", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/landing");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/landing", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        server.createContext("/fail", exchange -> { exchange.sendResponseHeaders(500,-1); exchange.close(); });
        server.start();
        try {
            var field = kaptainwutax.seedcrackerX.util.Database.class.getDeclaredField("SUBMISSION_CLIENT");
            field.setAccessible(true);
            var http = (java.net.http.HttpClient) field.get(null);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/post"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("test=local")).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            require(kaptainwutax.seedcrackerX.util.Database.submissionAccepted(response), "302 POST acknowledgement retained");
            require(redirects.get() == 0, "acknowledgement not hidden behind landing page");
            response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/fail")).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            require(!kaptainwutax.seedcrackerX.util.Database.submissionAccepted(response), "failure status rejected");
        } finally { server.stop(0); }
    }

    private static void inventoryDuplicates() {
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.STONE), new ItemStack(Items.DIAMOND_SWORD)));
        int[] swap = InvManager.nextSortSwap(inventory);
        require(swap != null && swap[0] == 2 && swap[1] == 1, "duplicate comes from unfilled suffix");
        Collections.swap(inventory, swap[0], swap[1]);
        require(InvManager.nextSortSwap(inventory) == null, "duplicate sorting converges");
        var random = new java.util.Random(0xBABB1E);
        for (int run=0; run<200; run++) {
            inventory = new ArrayList<>();
            var choices = List.of(Items.DIAMOND_SWORD, Items.STONE, Items.BOW, Items.GOLDEN_APPLE);
            for (int i=0; i<36; i++) inventory.add(random.nextInt(5)==0 ? ItemStack.EMPTY : new ItemStack(choices.get(random.nextInt(choices.size()))));
            int steps=0;
            while ((swap=InvManager.nextSortSwap(inventory)) != null) {
                require(++steps <= 9, "all hotbar prefixes converge with duplicates");
                Collections.swap(inventory,swap[0],swap[1]);
            }
        }
    }

    private static void healingSelection() {
        require(HealPotions.delivery(PotionContents.createItemStack(Items.POTION, Potions.HEALING)) == HealPotions.Delivery.DRINK, "healing drink accepted");
        require(HealPotions.delivery(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.REGENERATION)) == HealPotions.Delivery.SPLASH, "regeneration splash accepted");
        for (var potion : List.of(Potions.POISON, Potions.HARMING, Potions.WATER, Potions.SWIFTNESS))
            for (var form : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION))
                require(HealPotions.delivery(PotionContents.createItemStack(form,potion)) == HealPotions.Delivery.NONE, "non-healing potion rejected");
        ItemStack mixed = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        mixed.set(DataComponents.POTION_CONTENTS, mixed.get(DataComponents.POTION_CONTENTS).withEffectAdded(new MobEffectInstance(MobEffects.POISON,100)));
        require(HealPotions.delivery(mixed) == HealPotions.Delivery.NONE, "mixed harmful effects rejected");
    }

    private static void remove(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
