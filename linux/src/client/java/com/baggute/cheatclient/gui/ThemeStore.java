package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.config.Storage;
import com.baggute.cheatclient.module.misc.Appearance;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Theme-only sharing: fixed fields, no module settings, files, URLs or executable content. */
public final class ThemeStore {
    public static final String PREFIX="UCTHEME1:";
    public static final int MAX_BYTES=8192;
    public record Theme(String name,String author,ThemePalette.Palette palette) {}
    private ThemeStore() {}
    public static Path directory() { return Storage.root().resolve("utilityclient/themes"); }
    public static Theme capture(Appearance appearance) {
        return new Theme(appearance.themeName.get(),appearance.author.get(),ThemePalette.resolve(appearance));
    }
    public static JsonObject json(Theme theme) {
        var p=theme.palette();var root=new JsonObject();
        root.addProperty("format",1);root.addProperty("client","utilityclient");root.addProperty("name",theme.name());root.addProperty("author",theme.author());root.addProperty("style",p.style());
        var colors=new JsonObject();
        int[] values={p.background(),p.panel(),p.accent(),p.text(),p.dim(),p.border()};
        String[] names={"background","panel","accent","text","dim","border"};
        for(int i=0;i<names.length;i++)colors.addProperty(names[i],hex(values[i]));
        root.add("colors",colors);root.addProperty("opacity",p.opacity());root.addProperty("radius",p.radius());root.addProperty("borderWidth",p.borderWidth());root.addProperty("hover",p.hover());return root;
    }
    public static String hex(int value) { return String.format(Locale.ROOT,"#%06X",value&0xFFFFFF); }
    public static String encode(Theme theme) throws IOException {
        String text=json(theme).toString();parse(text);
        return PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    public static Theme decode(String input) throws IOException {
        if(input==null||input.length()>MAX_BYTES*2)throw new IOException("Theme code is empty or too large");
        String text=input.trim();
        try {
            if(text.startsWith(PREFIX))text=new String(Base64.getUrlDecoder().decode(text.substring(PREFIX.length())),StandardCharsets.UTF_8);
            return parse(text);
        }catch(IllegalArgumentException error){throw new IOException("Invalid theme share code",error);}
    }
    public static Theme parse(String text) throws IOException {
        if(text==null||text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Theme exceeds 8 KiB");
        try {
            JsonObject root=JsonParser.parseString(text).getAsJsonObject();
            requireKeys(root,Set.of("format","client","name","author","style","colors","opacity","radius","borderWidth","hover"));
            if(integer(root,"format",1,1)!=1||!string(root,"client").equals("utilityclient"))throw new IllegalArgumentException("Unsupported theme format/client");
            String name=label(root,"name",false),author=label(root,"author",true),style=string(root,"style");
            if(!Set.of("Flat","Rounded","Outlined","Glass").contains(style))throw new IllegalArgumentException("Unknown theme style");
            var colors=root.getAsJsonObject("colors");requireKeys(colors,Set.of("background","panel","accent","text","dim","border"));
            return new Theme(name,author,new ThemePalette.Palette(color(colors,"background"),color(colors,"panel"),color(colors,"accent"),color(colors,"text"),color(colors,"dim"),color(colors,"border"),style,
                number(root,"opacity",.55,1),integer(root,"radius",0,16),integer(root,"borderWidth",0,3),number(root,"hover",0,.5)));
        }catch(RuntimeException error){throw new IOException("Invalid theme: "+error.getMessage(),error);}
    }
    private static void requireKeys(JsonObject value,Set<String> keys) {
        if(value==null||!value.keySet().equals(keys))throw new IllegalArgumentException("Missing or unexpected theme fields");
    }
    private static String string(JsonObject object,String key) {
        var value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+" must be text");return value.getAsString();
    }
    private static String label(JsonObject root,String key,boolean empty) {
        String text=string(root,key).trim();if(text.length()>48||(!empty&&text.isEmpty())||text.codePoints().anyMatch(c->c<32||c==127))throw new IllegalArgumentException(key+" must contain "+(empty?"0":"1")+"–48 printable characters");return text;
    }
    private static int color(JsonObject object,String name) {
        String text=string(object,name);if(!text.matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException(name+" must use #RRGGBB");return 0xFF000000|Integer.parseInt(text.substring(1),16);
    }
    private static double number(JsonObject root,String key,double minimum,double maximum) {
        var value=root.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+" must be a number");
        double number=value.getAsDouble();if(!Double.isFinite(number)||number<minimum||number>maximum)throw new IllegalArgumentException(key+" outside "+minimum+"–"+maximum);return number;
    }
    private static int integer(JsonObject root,String key,int minimum,int maximum) {
        number(root,key,minimum,maximum);return root.get(key).getAsBigDecimal().intValueExact();
    }
    public static void apply(Theme input,Appearance target) throws IOException {
        Theme theme=parse(json(input).toString());var p=theme.palette();
        target.palette.set("Custom");target.customSurfaceColors.set(true);target.style.set(p.style());target.themeName.set(theme.name());target.author.set(theme.author());
        target.background.set(p.background());target.panel.set(p.panel());target.accent.set(p.accent());target.text.set(p.text());target.dim.set(p.dim());target.border.set(p.border());
        target.opacity.set(p.opacity());target.radius.set(p.radius());target.borderWidth.set(p.borderWidth());target.hover.set(p.hover());
    }
    public static Path save(Theme input,Path folder) throws IOException {
        Theme theme=parse(json(input).toString());folder=folder.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(folder))throw new IOException("Theme folder must not be a link");Files.createDirectories(folder);
        String name=theme.name().replaceAll("[^A-Za-z0-9 _-]","_").trim();if(name.isEmpty())name="theme";
        Path target=folder.resolve("theme-"+name+".json");
        if(Files.isSymbolicLink(target))throw new IOException("Theme file must not be a link");
        Path pending=Files.createTempFile(folder,".theme-",".tmp");
        try {
            Files.writeString(pending,new GsonBuilder().setPrettyPrinting().create().toJson(json(theme))+"\n",StandardCharsets.UTF_8);
            try{Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(pending,target,StandardCopyOption.REPLACE_EXISTING);}return target;
        }finally{Files.deleteIfExists(pending);}
    }
    public static Theme load(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw new IOException("Theme file is missing, linked or too large");return parse(Files.readString(file,StandardCharsets.UTF_8));
    }
    public static List<Path> list(Path folder) throws IOException {
        Files.createDirectories(folder);try(var files=Files.list(folder)){return files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().endsWith(".json")).sorted().limit(128).toList();}
    }
}
