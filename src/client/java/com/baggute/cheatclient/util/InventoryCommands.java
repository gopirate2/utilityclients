package com.baggute.cheatclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;

/** Drops use acknowledged inventory actions; UUID lookup uses the server's tab list. */
public final class InventoryCommands {
    private InventoryCommands() {}
    private static net.minecraft.client.player.LocalPlayer owner;
    private static ItemStack item;
    private static int remaining, dropped;
    public static void uuid(String[] args) {
        var mc = Minecraft.getInstance();
        if (args.length != 2 || mc.getConnection() == null) { say("Usage: .uuid <online player>"); return; }
        for (var info : mc.getConnection().getOnlinePlayers()) if (info.getProfile().name().equalsIgnoreCase(args[1])) {
            String uuid = info.getProfile().id().toString();
            mc.keyboardHandler.setClipboard(uuid);
            say(info.getProfile().name() + " UUID copied: " + uuid); return;
        }
        String name=args[1];
        if(!name.matches("[A-Za-z0-9_]{1,16}")) { say("Invalid Minecraft player name."); return; }
        say("Looking up UUID for " + name + "...");
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/"+name))
            .timeout(java.time.Duration.ofSeconds(8)).GET().build();
        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build()
            .sendAsync(request,java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->mc.execute(()->{
                if(error!=null||response.statusCode()!=200) { say("No UUID found for "+name+"."); return; }
                try {
                    var json=com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                    String id=json.get("id").getAsString();
                    if(!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException();
                    String uuid=id.substring(0,8)+"-"+id.substring(8,12)+"-"+id.substring(12,16)+"-"+id.substring(16,20)+"-"+id.substring(20);
                    mc.keyboardHandler.setClipboard(uuid);say(name+" UUID copied: "+uuid);
                }catch(Exception failure){say("UUID lookup returned an invalid response.");}
            }));
    }
    public static void drop(String[] args) {
        var mc = Minecraft.getInstance();
        if (args.length < 3 || mc.player == null) { say("Usage: .drop <1|10|100|all> <item or held>"); return; }
        if (owner != null) { say("A drop is already running."); return; }
        int amount;
        try { amount = args[1].equalsIgnoreCase("all") ? Integer.MAX_VALUE : Integer.parseInt(args[1]); }
        catch (NumberFormatException error) { say("Use 1, 10, 100 or all."); return; }
        if (amount != 1 && amount != 10 && amount != 100 && amount != Integer.MAX_VALUE) { say("Use 1, 10, 100 or all."); return; }
        String name = String.join("_", java.util.Arrays.copyOfRange(args,2,args.length)).toLowerCase(java.util.Locale.ROOT);
        ItemStack match = ItemStack.EMPTY;
        if (name.equals("held")) match = mc.player.getMainHandItem();
        else for (int slot = 0; slot < 41; slot++) {
            ItemStack candidate = mc.player.getInventory().getItem(slot);
            if (candidate.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString();
            if (id.equals(name) || id.equals("minecraft:" + name) || candidate.getHoverName().getString().replace(' ','_').equalsIgnoreCase(name)) { match = candidate; break; }
        }
        if (match.isEmpty()) { say("No matching inventory item: " + name); return; }
        owner = mc.player; item = match.copyWithCount(1); remaining = amount; dropped = 0;
    }
    public static void tick() {
        if (owner == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player != owner || mc.level == null || mc.gameMode == null) { owner = null; return; }
        // Chat is allowed; other inventory screens pause the operation.
        if (owner.containerMenu != owner.inventoryMenu || !owner.inventoryMenu.getCarried().isEmpty() || Stealth.swapBlocked()) return;
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = owner.getInventory().getItem(slot);
            if (!stack.is(item.getItem())) continue;
            int count = stack.getCount();
            boolean whole = remaining >= count;
            if (!Stealth.swapSlot(mc, com.baggute.cheatclient.config.KitManager.menuSlotFor(slot), whole ? 1 : 0, ContainerInput.THROW)) return;
            int spent = Math.max(0, count - owner.getInventory().getItem(slot).getCount());
            if (spent == 0) { say("Drop refused for " + stack.getHoverName().getString() + "; stopped."); owner = null; return; }
            remaining -= spent; dropped += spent;
            if (remaining == 0) finish();
            return;
        }
        finish();
    }
    private static void finish() { say("Dropped " + dropped + " " + item.getHoverName().getString() + "."); owner = null; item = null; }
    private static void say(String text) { ChatCommands.say(text, ChatFormatting.AQUA); }
}
