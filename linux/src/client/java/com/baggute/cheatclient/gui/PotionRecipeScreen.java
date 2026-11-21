package com.baggute.cheatclient.gui;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.baggute.cheatclient.util.ChatCommands;
import net.minecraft.ChatFormatting;

/** A brewing guide, showing normal, amplified and extended recipes together. */
public final class PotionRecipeScreen extends Screen {
    public record Recipe(String name, List<Item> ingredients, String normal, String strong, String extended, String note) {
        public Recipe(String name,List<Item> ingredients,String normal,String strong,String extended) { this(name,ingredients,normal,strong,extended,""); }
    }
    public record Request(Recipe recipe, boolean strong, boolean extended) {}
    public static final List<String> POTIONS=List.of("water","awkward","mundane","thick","strength","speed","invisibility","nightvision","fireresistance","waterbreathing","leaping","regeneration","poison","healing","harming","slowness","weakness","slowfalling","turtlemaster","windcharged","weaving","oozing","infested","luck");
    private final Recipe recipe;
    private static PotionRecipeScreen pending;
    private final boolean strong, extended;
    private PotionRecipeScreen(Recipe recipe, boolean strong, boolean extended) {
        super(Component.literal("Potion recipe")); this.recipe=recipe; this.strong=strong; this.extended=extended;
    }
    public static Recipe recipe(String name) {
        name=name.toLowerCase(Locale.ROOT).replace("_","").replace(" ","");
        return switch (name) {
            case "water" -> new Recipe("Water",List.of(),"No effect",null,null,"Fill a glass bottle from water. This is the starting bottle for brewing.");
            case "awkward" -> new Recipe("Awkward",List.of(Items.NETHER_WART),"No effect",null,null,"The base for most effect potions. Continue with the ingredient for the effect you want.");
            case "mundane" -> new Recipe("Mundane",List.of(Items.SUGAR),"No effect",null,null,"Brew sugar into water without nether wart. Mundane potions cannot become effect potions.");
            case "thick" -> new Recipe("Thick",List.of(Items.GLOWSTONE_DUST),"No effect",null,null,"Brew glowstone dust into water without nether wart. Thick potions cannot become effect potions.");
            case "luck" -> new Recipe("Luck",List.of(),"5:00",null,null,"Luck has no survival brewing recipe. It is available through creative inventory or server commands.");
            case "strength", "str" -> new Recipe("Strength", List.of(Items.NETHER_WART, Items.BLAZE_POWDER), "3:00", "1:30", "8:00");
            case "speed", "spd", "swiftness" -> new Recipe("Speed", List.of(Items.NETHER_WART, Items.SUGAR), "3:00", "1:30", "8:00");
            case "invis", "invisibility" -> new Recipe("Invisibility", List.of(Items.NETHER_WART, Items.GOLDEN_CARROT, Items.FERMENTED_SPIDER_EYE), "3:00", null, "8:00");
            case "nightvision", "nv" -> new Recipe("Night Vision", List.of(Items.NETHER_WART, Items.GOLDEN_CARROT), "3:00", null, "8:00");
            case "fireres", "fireresistance", "fr" -> new Recipe("Fire Resistance", List.of(Items.NETHER_WART, Items.MAGMA_CREAM), "3:00", null, "8:00");
            case "waterbreathing", "wb" -> new Recipe("Water Breathing", List.of(Items.NETHER_WART, Items.PUFFERFISH), "3:00", null, "8:00");
            case "jump", "leaping", "jumpboost" -> new Recipe("Leaping", List.of(Items.NETHER_WART, Items.RABBIT_FOOT), "3:00", "1:30", "8:00");
            case "regen", "regeneration" -> new Recipe("Regeneration", List.of(Items.NETHER_WART, Items.GHAST_TEAR), "0:45", "0:22", "1:30");
            case "poison" -> new Recipe("Poison", List.of(Items.NETHER_WART, Items.SPIDER_EYE), "0:45", "0:21", "1:30");
            case "heal", "healing", "health", "instanthealth" -> new Recipe("Healing", List.of(Items.NETHER_WART, Items.GLISTERING_MELON_SLICE), "Instant", "Instant", null);
            case "harm", "harming", "damage", "instantdamage" -> new Recipe("Harming", List.of(Items.NETHER_WART, Items.GLISTERING_MELON_SLICE, Items.FERMENTED_SPIDER_EYE), "Instant", "Instant", null);
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
    public static Request parse(String input) {
        String value=input.toLowerCase(Locale.ROOT).trim().replace("minecraft:","").replace("potion_of_","").replace("_","").replace("-","").replace(" ","");
        value=value.replaceFirst("^(?:splash|lingering)","");
        boolean extended=value.startsWith("long")||value.endsWith("8")||value.endsWith("8min")||value.endsWith("long")||value.endsWith("extended");
        boolean strong=value.startsWith("strong")||value.endsWith("2")||value.endsWith("4")||value.endsWith("ii")||value.endsWith("iv");
        value=value.replaceFirst("^(?:long|strong)","").replaceFirst("(?:8min|8|long|extended)$","").replaceFirst("(?:[124]|ii|iv)$","");
        Recipe recipe=recipe(value);
        return recipe==null?null:new Request(recipe,strong,extended);
    }
    public static void open(String[] args) {
        if(args.length<2||Set.of("all","list","help").contains(args[1].toLowerCase(Locale.ROOT))) { pending=new PotionRecipeScreen(null,false,false);return; }
        Request request=parse(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
        if(request==null) { ChatCommands.say("Unknown potion: "+args[1]+". Use .potion to browse every supported potion.",ChatFormatting.RED); return; }
        // Chat's send callback closes its screen; open after that callback returns.
        pending=new PotionRecipeScreen(request.recipe(),request.strong(),request.extended());
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
        if(recipe==null) { drawCatalogue(g);return; }
        int x=Math.max(12,(width-440)/2), y=Math.max(12,(height-250)/2);
        g.fill(x-8,y-8,Math.min(width-8,x+440),Math.min(height-8,y+250),0xF018202D);
        g.text(font,recipe.name()+" brewing guide",x,y,0xFF65E0D8,true); y+=22;
        g.text(font,"Fuel: blaze powder. Fill up to 3 glass bottles with water.",x,y,0xFFFFFFFF,true); y+=20;
        for(int i=0;i<recipe.ingredients().size();i++) {
            ItemStack stack=new ItemStack(recipe.ingredients().get(i));
            g.item(stack,x,y);g.text(font,(i+1)+". Add "+stack.getHoverName().getString()+"; wait for brewing.",x+22,y+4,0xFFFFFFFF,true);y+=22;
        }
        if(!recipe.note().isEmpty()) for(var line:font.split(Component.literal(recipe.note()),Math.min(440,width-x-12))) {g.text(font,line,x,y,0xFFFFDD77,true);y+=11;}
        g.text(font,"Normal: "+recipe.normal(),x,y,0xFFAAAAAA,true);y+=16;
        g.item(new ItemStack(Items.GLOWSTONE_DUST),x,y);
        g.text(font,recipe.strong()==null?"No stronger variant.":"Add glowstone dust: stronger / "+recipe.strong(),x+22,y+4,strong?0xFFFFDD77:0xFFFFFFFF,true);y+=22;
        g.item(new ItemStack(Items.REDSTONE),x,y);
        g.text(font,recipe.extended()==null?"No extended variant.":"Add redstone: extended / "+recipe.extended(),x+22,y+4,extended?0xFFFFDD77:0xFFFFFFFF,true);y+=22;
        g.text(font,"Choose glowstone OR redstone; stronger potions cannot be extended.",x,y,0xFFAAAAAA,true);y+=16;
        g.text(font,"Optional: gunpowder = splash; then dragon's breath = lingering.",x,y,0xFFAAAAAA,true);y+=16;
        g.text(font,"Listed durations are drink/splash. Lingering effects last one quarter.",x,y,0xFFAAAAAA,true);y+=16;
        g.text(font,"Esc: close. Use .potion to browse all potions.",x,y,0xFF65E0D8,true);
    }
    private void drawCatalogue(GuiGraphicsExtractor g) {
        int x=Math.max(12,(width-440)/2),w=Math.min(440,width-x-12);
        g.text(font,"POTIONS - choose a recipe",x,16,0xFF65E0D8,true);
        g.text(font,"All vanilla types, stronger/extended variants, and aliases.",x,32,0xFFAAAAAA,true);
        for(int i=0;i<POTIONS.size();i++) {
            int column=i/12,row=i%12,left=x+column*w/2,top=52+row*catalogueRowHeight();
            if(top+catalogueRowHeight()-2>=height-16)continue;
            g.fill(left,top,left+w/2-4,top+catalogueRowHeight()-2,0xD0202A38);
            g.text(font,recipe(POTIONS.get(i)).name(),left+5,top+3,0xFFFFFFFF,true);
        }
        g.text(font,"Examples: .potion long_fire_resistance / strong_healing / invis8",x,height-14,0xFFAAAAAA,true);
    }
    private int catalogueRowHeight() { return Math.min(16,Math.max(10,(height-70)/12)); }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubled) {
        if(recipe==null&&event.button()==0) {
            int x=Math.max(12,(width-440)/2),w=Math.min(440,width-x-12);
            int col=(int)((event.x()-x)/(w/2)),row=(int)((event.y()-52)/catalogueRowHeight());
            if(event.x()>=x&&event.x()<x+w&&event.y()>=52&&row>=0&&row<12&&col>=0&&col<2) {
                int index=col*12+row;
                if(index<POTIONS.size()){Minecraft.getInstance().gui.setScreen(new PotionRecipeScreen(recipe(POTIONS.get(index)),false,false));return true;}
            }
        }
        return super.mouseClicked(event,doubled);
    }
}
