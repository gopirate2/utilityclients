package com.baggute.cheatclient;

import com.baggute.cheatclient.gui.ThemePalette;
import com.baggute.cheatclient.gui.ThemeStore;
import com.baggute.cheatclient.module.misc.Appearance;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;

public final class ThemeShareChecks {
    public static void run() throws Exception {
        Appearance original=new Appearance();original.palette.set("Cyberpunk");original.style.set("Glass");original.themeName.set("Community Rose");original.author.set("Theme creator");
        original.opacity.set(.72);original.radius.set(11);original.borderWidth.set(3);original.hover.set(.32);
        var theme=ThemeStore.capture(original);String code=ThemeStore.encode(theme);
        require(code.length()<2000,"share code fits a normal Discord message");
        require(ThemeStore.decode(code).equals(theme),"theme code retains colors and advanced controls");
        require(ThemeStore.decode(ThemeStore.json(theme).toString()).equals(theme),"editable JSON roundtrip");
        Appearance target=new Appearance();ThemeStore.apply(theme,target);
        require(ThemeStore.capture(target).equals(theme),"applied shared theme has exact appearance and metadata");
        Appearance legacy=new Appearance();legacy.palette.set("Custom");legacy.background.set(0xFFF0F0F0);legacy.text.set(0xFF101010);
        require(ThemePalette.resolve(legacy).panel()==ThemePalette.blend(legacy.background.get(),legacy.text.get(),.06f),"older custom profiles keep their derived panel colors");
        for(String field:List.of("format","opacity","radius","borderWidth","hover")) {
            var bad=ThemeStore.json(theme);bad.addProperty(field,field.equals("radius")?2.5:100);reject(bad.toString());
        }
        var bad=ThemeStore.json(theme);bad.addProperty("style","RemoteScript");reject(bad.toString());
        bad=ThemeStore.json(theme);bad.addProperty("modules","not theme data");reject(bad.toString());
        bad=ThemeStore.json(theme);bad.getAsJsonObject("colors").addProperty("accent","javascript:alert(1)");reject(bad.toString());
        bad=ThemeStore.json(theme);bad.getAsJsonObject("colors").remove("panel");reject(bad.toString());
        bad=ThemeStore.json(theme);bad.addProperty("name","x".repeat(49));reject(bad.toString());
        reject("UCTHEME1:@@@@");reject("x".repeat(ThemeStore.MAX_BYTES*2+1));
        Path folder=Files.createTempDirectory("uc-theme-library-");
        try {
            var escaped=new ThemeStore.Theme("../outside",theme.author(),theme.palette());
            Path saved=ThemeStore.save(escaped,folder);
            require(saved.toAbsolutePath().normalize().startsWith(folder.toAbsolutePath().normalize()),"theme names cannot escape the library");
            require(ThemeStore.load(saved).equals(escaped),"saved theme reloads exactly");
            require(ThemeStore.list(folder).equals(List.of(saved)),"local library shows JSON themes");
            ThemeStore.save(escaped,folder);
            try(var files=Files.list(folder)){require(files.count()==1,"repeat save replaces without staging leftovers");}
        } finally { try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);} }
        Set<Integer> backgrounds=new HashSet<>();
        for(String name:original.palette.choices())if(!name.equals("Custom")){original.palette.set(name);backgrounds.add(ThemePalette.resolve(original).background());}
        require(backgrounds.size()==16,"sixteen distinct preset palettes");
        System.out.println("PASS: sixteen palettes, theme code/JSON roundtrip, exact advanced settings, rejected malformed imports and bounded atomic theme library");
    }
    private static void reject(String text)throws Exception {try{ThemeStore.decode(text);throw new AssertionError("invalid theme accepted");}catch(IOException expected){}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
