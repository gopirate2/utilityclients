package com.baggute.cheatclient.gui;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.baggute.cheatclient.util.ChatCommands;
import net.minecraft.ChatFormatting;

/** A brewing guide, showing normal, amplified and extended recipes together. */
public final class PotionRecipeScreen extends Screen {
    public record Recipe(String name, List<Item> ingredients, String normal, String strong, String extended) {}
    private final Recipe recipe;
    private static PotionRecipeScreen pending;
    private final boolean strong, extended;
    private PotionRecipeScreen(Recipe recipe, boolean strong, boolean extended) {
        super(Component.literal("Potion recipe")); this.recipe=recipe; this.strong=strong; this.extended=extended;
    }
    public static Recipe recipe(String name) {
        return switch (name) {
            case "strength", "str" -> new Recipe("Strength", List.of(Items.NETHER_WART, Items.BLAZE_POWDER), "3:00", "1:30", "8:00");
            case "speed", "spd", "swiftness" -> new Recipe("Speed", List.of(Items.NETHER_WART, Items.SUGAR), "3:00", "1:30", "8:00");
            case "invis", "invisibility" -> new Recipe("Invisibility", List.of(Items.NETHER_WART, Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE), "3:00", null, "8:00");
            case "nightvision", "nv" -> new Recipe("Night Vision", List.of(Items.NETHER_WART, Items.GOLDEN_CARROT), "3:00", null, "8:00");
            case "fireres", "fireresistance", "fr" -> new Recipe("Fire Resistance", List.of(Items.NETHER_WART, Items.MAGMA_CREAM), "3:00", null, "8:00");
            case "waterbreathing", "water", "wb" -> new Recipe("Water Breathing", List.of(Items.NETHER_WART, Items.PUFFERFISH), "3:00", null, "8:00");
            case "jump", "leaping", "jumpboost" -> new Recipe("Leaping", List.of(Items.NETHER_WART, Items.RABBIT_FOOT), "3:00", "1:30", "8:00");
            case "regen", "regeneration" -> new Recipe("Regeneration", List.of(Items.NETHER_WART, Items.GHAST_TEAR), "0:45", "0:22", "1:30");
            case "poison" -> new Recipe("Poison", List.of(Items.NETHER_WART, Items.SPIDER_EYE), "0:45", "0:21", "1:30");
            case "heal", "healing", "health" -> new Recipe("Healing", List.of(Items.NETHER_WART, Items.GLISTERING_MELON_SLICE), "Instant", "Instant", null);
            case "harm", "harming", "damage" -> new Recipe("Harming", List.of(Items.NETHER_WART, Items.GLISTERING_MELON_SLICE, Items.FERMENTED_SPIDER_EYE), "Instant", "Instant", null);
            case "slow", "slowness" -> new Recipe("Slowness", List.of(Items.NETHER_WART, Items.SUGAR, Items.FERMENTED_SPIDER_EYE), "1:30", "0:20 (IV)", "4:00");
            case "weak", "weakness" -> new Recipe("Weakness", List.of(Items.FERMENTED_SPIDER_EYE), "1:30", null, "4:00");
            case "slowfall", "slowfalling" -> new Recipe("Slow Falling", List.of(Items.NETHER_WART, Items.PHANTOM_MEMBRANE), "1:30", null, "4:00");
            case "turtle", "turtlemaster" -> new Recipe("Turtle Master", List.of(Items.NETHER_WART, Items.TURTLE_HELMET), "0:20", "0:20", "0:40");
            case "windcharged" -> new Recipe("Wind Charged", List.of(Items.NETHER_WART, Items.BREEZE_ROD), "3:00", null, null);
            case "weaving" -> new Recipe("Weaving", List.of(Items.NETHER_WART, Items.COBWEB), "3:00", null, null);
            case "oozing" -> new Recipe("Oozing", List.of(Items.NETHER_WART, Items.SLIME_BLOCK), "3:00", null, null);
            case "infested" -> new Recipe("Infested", List.of(Items.NETHER_WART, Items.STONE), "3:00", null, null);
            default -> null;
        };
    }
    public static void open(String[] args) {
        if (args.length < 2) { ChatCommands.say("Usage: .potion str1|str2|speed1|invis|strength8 (add long or 8 for extended)",ChatFormatting.AQUA); return; }
        String value=String.join("",Arrays.copyOfRange(args,1,args.length)).toLowerCase(Locale.ROOT).replace("_","").replace("-","");
        boolean extended=value.endsWith("8") || value.endsWith("8min") || value.endsWith("long") || value.endsWith("extended");
        value=value.replaceFirst("(?:8min|8|long|extended)$","");
        boolean strong=value.endsWith("2");
        value=value.replaceFirst("[12]$","");
        Recipe recipe=recipe(value);
        if(recipe==null) { ChatCommands.say("Unknown brewable potion: "+args[1],ChatFormatting.RED); return; }
        Recipe selected=recipe; boolean amplified=strong, longer=extended;
        var mc=Minecraft.getInstance();
        // Chat's send callback closes its screen; open after that callback returns.
        pending=new PotionRecipeScreen(selected,amplified,longer);
    }
    public static void openPending() {
        if(pending==null) return;
        var mc=Minecraft.getInstance();
        PotionRecipeScreen next=pending;pending=null;
        if(mc.player!=null) mc.gui.setScreen(next);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        g.fill(0,0,width,height,0xCA080B12);
        int x=Math.max(12,(width-440)/2), y=Math.max(12,(height-250)/2);
        g.fill(x-8,y-8,Math.min(width-8,x+440),Math.min(height-8,y+250),0xF018202D);
        g.text(font,recipe.name()+" brewing guide",x,y,0xFF65E0D8,true); y+=22;
        g.text(font,"Fuel: blaze powder. Fill up to 3 glass bottles with water.",x,y,0xFFFFFFFF,true); y+=20;
        for(int i=0;i<recipe.ingredients().size();i++) {
            ItemStack stack=new ItemStack(recipe.ingredients().get(i));
            g.item(stack,x,y);g.text(font,(i+1)+". Add "+stack.getHoverName().getString()+"; wait for brewing.",x+22,y+4,0xFFFFFFFF,true);y+=22;
        }
        g.text(font,"Normal: "+recipe.normal(),x,y,0xFFAAAAAA,true);y+=16;
        g.item(new ItemStack(Items.GLOWSTONE_DUST),x,y);
        g.text(font,recipe.strong()==null?"No stronger variant.":"Add glowstone dust: stronger / "+recipe.strong(),x+22,y+4,strong?0xFFFFDD77:0xFFFFFFFF,true);y+=22;
        g.item(new ItemStack(Items.REDSTONE),x,y);
        g.text(font,recipe.extended()==null?"No extended variant.":"Add redstone: extended / "+recipe.extended(),x+22,y+4,extended?0xFFFFDD77:0xFFFFFFFF,true);y+=22;
        g.text(font,"Choose glowstone OR redstone; stronger potions cannot be extended.",x,y,0xFFAAAAAA,true);y+=16;
        g.text(font,"Optional: gunpowder = splash; then dragon's breath = lingering.",x,y,0xFFAAAAAA,true);y+=16;
        g.text(font,"Esc: close",x,y,0xFF65E0D8,true);
    }
}
