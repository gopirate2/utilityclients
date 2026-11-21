package com.baggute.cheatclient.util;

import com.mojang.brigadier.suggestion.*;
import com.baggute.cheatclient.gui.PotionRecipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import java.lang.reflect.Field;
import java.util.*;

/** Local suggestions use vanilla's popup and Tab handling, including late injection. */
public final class CommandAutocomplete {
    public static final List<String> ROOTS = List.of("version", "ver", "lookup", "uuid", "drop", "potion");
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static Object lastScreen;
    private static String lastText = "";
    private static int lastCursor = -1;
    private CommandAutocomplete() {}

    public static Suggestions suggest(String text, int cursor, Collection<String> players, Collection<String> items) {
        if (text == null || !text.startsWith(".") || cursor < 1 || cursor > text.length()) return Suggestions.empty().join();
        String before = text.substring(0, cursor);
        int start = cursor;
        while (start > 1 && !Character.isWhitespace(before.charAt(start - 1))) start--;
        String[] parts = before.substring(1).split("\\s+", -1);
        String root = parts[0].toLowerCase(Locale.ROOT);
        Collection<String> choices = List.of();
        if (parts.length == 1) choices = ROOTS;
        else if (parts.length == 2) choices = switch (root) {
            case "lookup" -> { var values = new ArrayList<>(players); values.add("list"); yield values; }
            case "uuid" -> players;
            case "drop" -> List.of("1", "10", "100", "all");
            case "potion" -> potionNames();
            default -> List.of();
        };
        else if (parts.length == 3 && root.equals("drop")) {
            var values = new ArrayList<>(items); values.add("held"); choices = values;
        }
        var builder = new SuggestionsBuilder(text.substring(0, cursor), start);
        String prefix = before.substring(start).toLowerCase(Locale.ROOT);
        choices.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER)
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).forEach(builder::suggest);
        return builder.build();
    }

    private static List<String> potionNames() {
        var names = new ArrayList<String>(List.of("all", "list", "help", "str1", "str2", "str8", "spd1", "spd2", "invis", "nv", "fr", "wb", "jump", "regen", "heal", "harm", "weak", "slowfall"));
        for (String name : PotionRecipeScreen.POTIONS) {
            names.add(name); names.add(name + "1");
            var recipe = PotionRecipeScreen.recipe(name);
            if (recipe.strong() != null) names.add(name + (name.equals("slowness") ? "4" : "2"));
            if (recipe.extended() != null) names.add("long" + name);
        }
        return names;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        String key = type.getName() + "/" + name;
        Field cached = FIELDS.get(key);
        if (cached == null) { cached = type.getDeclaredField(name); cached.setAccessible(true); FIELDS.put(key, cached); }
        return cached;
    }

    public static boolean update(CommandSuggestions owner, EditBox input) {
        String text = input.getValue();
        if (!text.startsWith(".") || com.baggute.cheatclient.UtilityClient.isUnloaded()) return false;
        try {
            if (field(CommandSuggestions.class, "keepSuggestions").getBoolean(owner)) return true;
            var mc = Minecraft.getInstance();
            var players = new ArrayList<String>(); var items = new ArrayList<String>();
            if (mc.getConnection() != null) for (var info : mc.getConnection().getOnlinePlayers()) players.add(info.getProfile().name());
            if (mc.player != null) for (int slot = 0; slot < 41; slot++) {
                var stack = mc.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) { var id = BuiltInRegistries.ITEM.getKey(stack.getItem()); items.add(id.toString()); items.add(id.getPath()); }
            }
            input.setSuggestion(null);
            field(CommandSuggestions.class, "suggestions").set(owner, null);
            field(CommandSuggestions.class, "currentParse").set(owner, null);
            field(CommandSuggestions.class, "currentParseIsCommand").setBoolean(owner, false);
            field(CommandSuggestions.class, "currentParseIsMessage").setBoolean(owner, true);
            ((List<?>) field(CommandSuggestions.class, "commandUsage").get(owner)).clear();
            field(CommandSuggestions.class, "pendingSuggestions").set(owner, java.util.concurrent.CompletableFuture.completedFuture(
                suggest(text, input.getCursorPosition(), players, items)));
            owner.showSuggestions(false);
            return true;
        } catch (ReflectiveOperationException failure) { ClientErrors.report("command autocomplete", failure); return false; }
    }

    /** Poll only when injected after vanilla screens were loaded. Never resets Tab cycling. */
    public static void tick(Minecraft mc) {
        if (!com.baggute.cheatclient.UtilityClient.LATE_INIT) return;
        if (!(mc.gui.screen() instanceof ChatScreen screen)) { lastScreen = null; return; }
        try {
            var input = (EditBox) field(ChatScreen.class, "input").get(screen);
            if (input == null) return;
            String text = input.getValue(); int cursor = input.getCursorPosition();
            if (lastScreen == screen && lastText.equals(text) && cursor == lastCursor) return;
            lastScreen = screen; lastText = text; lastCursor = cursor;
            update((CommandSuggestions) field(ChatScreen.class, "commandSuggestions").get(screen), input);
        } catch (ReflectiveOperationException failure) { ClientErrors.report("late command autocomplete", failure); }
    }
}
