package com.baggute.cheatclient;

import com.baggute.cheatclient.util.CommandAutocomplete;
import java.util.List;

public final class CommandAutocompleteChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void run() {
        var players=List.of("Alex", "Steve"); var items=List.of("minecraft:ender_pearl", "ender_pearl", "stone");
        check(CommandAutocomplete.suggest(".",1,players,items).getList().size()==6,"all command roots");
        var root=CommandAutocomplete.suggest(".VER",4,players,items);
        check(root.getList().stream().anyMatch(s->s.apply(".VER").equals(".version")),"case-insensitive root retains dot");
        var player=CommandAutocomplete.suggest(".uuid Al",8,players,items);
        check(player.getList().getFirst().apply(".uuid Al").equals(".uuid Alex"),"player completion");
        check(CommandAutocomplete.suggest(".drop ",6,players,items).getList().size()==4,"drop quantities");
        check(CommandAutocomplete.suggest(".drop 10 end",12,players,items).getList().getFirst().apply(".drop 10 end").equals(".drop 10 ender_pearl"),"inventory completion");
        check(CommandAutocomplete.suggest(".potion str",11,players,items).getList().stream().anyMatch(s->s.getText().equals("str2")),"potion aliases");
        var middle=CommandAutocomplete.suggest(".uuid Al trailing",8,players,items);
        check(middle.getList().getFirst().apply(".uuid Al trailing").equals(".uuid Alex trailing"),"preserve suffix when cursor is mid-message");
        check(CommandAutocomplete.suggest("/give",5,players,items).isEmpty(),"vanilla slash unaffected");
        check(CommandAutocomplete.suggest("hello",5,players,items).isEmpty(),"ordinary chat unaffected");
        check(CommandAutocomplete.suggest(".unknown ",9,players,items).isEmpty(),"unknown command");
        check(CommandAutocomplete.suggest(".uuid ",99,players,items).isEmpty(),"invalid cursor");
        System.out.println("PASS: dot command roots, players, items, potion aliases, cursor replacement and vanilla isolation");
    }
}
