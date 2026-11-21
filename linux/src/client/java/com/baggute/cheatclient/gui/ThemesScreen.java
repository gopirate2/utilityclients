package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.misc.Appearance;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;

/** Draft-first theme editor, preview, local library and clipboard sharing. */
public final class ThemesScreen extends Screen {
    private static final String[] LABELS={"Name","Author","Style","Background","Panel","Accent","Text","Muted Text","Border","Opacity","Corner Radius","Border Width","Hover Strength"};
    private static final String[] KEYS={"name","author","style","background","panel","accent","text","dim","border","opacity","radius","borderWidth","hover"};
    private static final List<String> STYLES=List.of("Flat","Rounded","Outlined","Glass");
    private final Screen parent;
    private final Appearance appearance;
    private ThemeStore.Theme draft;
    private final List<String> presets;
    private List<Path> library=List.of();
    private int preset=-1,editorScroll,libraryScroll,editing=-1;
    private String input="",message="Edit a draft or import a shared theme. Apply changes the menu; Save keeps a local copy.";
    private boolean replaceInput;
    public ThemesScreen(Screen parent) {
        super(Component.literal("Themes"));this.parent=parent;
        appearance=(Appearance)UtilityClient.moduleManager.getByName("Appearance");
        draft=ThemeStore.capture(appearance);presets=appearance.palette.choices().stream().filter(s->!s.equals("Custom")).toList();
        preset=presets.indexOf(appearance.palette.get());refreshLibrary();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){Minecraft.getInstance().gui.setScreen(parent);}
    private int left(){return Math.max(12,(width-760)/2);}
    private int paneWidth(){return Math.min(760,width-left()-12);}
    private int editorWidth(){return Math.max(140,paneWidth()*45/100);}
    private int fieldsTop(){return 66;}
    private int bottom(){return height-65;}
    private int rowHeight(){return 17;}
    private int previewHeight(){return Math.min(128,Math.max(64,(height-125)/2));}
    private int libraryTop(){return 62+previewHeight()+28;}
    private void refreshLibrary(){try{library=ThemeStore.list(ThemeStore.directory());}catch(Exception e){message="Theme folder: "+e.getMessage();}}
    private String value(int index) {
        JsonObject root=ThemeStore.json(draft);
        return (index>=3&&index<=8?root.getAsJsonObject("colors").get(KEYS[index]):root.get(KEYS[index])).getAsString();
    }
    private void commit() {
        if(editing<0)return;
        try {
            JsonObject root=ThemeStore.json(draft);String key=KEYS[editing];
            if(editing>=3&&editing<=8)root.getAsJsonObject("colors").addProperty(key,input.startsWith("#")?input:"#"+input);
            else if(editing>=9)root.addProperty(key,new java.math.BigDecimal(input.trim()));
            else root.addProperty(key,input);
            draft=ThemeStore.parse(root.toString());editing=-1;message="Draft updated. Preview is on the right.";
        }catch(Exception e){message=e.getMessage();}
    }
    private void preset(int direction) {
        preset=Math.floorMod(preset+direction,presets.size());Appearance sample=new Appearance();sample.palette.set(presets.get(preset));
        sample.style.set(draft.palette().style());sample.opacity.set(draft.palette().opacity());sample.radius.set(draft.palette().radius());sample.borderWidth.set(draft.palette().borderWidth());sample.hover.set(draft.palette().hover());
        draft=new ThemeStore.Theme(presets.get(preset),draft.author(),ThemePalette.resolve(sample));editing=-1;message="Preset preview: "+presets.get(preset)+". Edit colors to create a variation.";
    }
    private void importClipboard() {
        try{String text=Minecraft.getInstance().keyboardHandler.getClipboard().trim();
            if(text.length()>ThemeStore.MAX_BYTES*2)throw new java.io.IOException("Theme clipboard input is too large");
            draft=text.startsWith(ThemeStore.PREFIX)||text.startsWith("{")?ThemeStore.decode(text):ThemeStore.load(Path.of(text.replaceAll("^\"|\"$","")));
            editing=-1;preset=-1;message="Imported "+draft.name()+" as a preview. Apply or Save when ready.";}
        catch(Exception e){message=e.getMessage();}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        UiRenderer.refreshTheme();int x=left(),w=paneWidth(),ew=editorWidth(),right=x+ew+14,rw=w-ew-14;
        g.fill(0,0,width,height,0xF0000000|(ThemePalette.current().background()&0xFFFFFF));g.text(font,"THEMES / EDIT, PREVIEW & SHARE",x,16,Ui.TEXT,true);
        g.text(font,font.plainSubstrByWidth("Click fields to edit. Enter commits; scroll the editor or library.",w),x,33,Ui.MUTED,true);
        g.fill(x,48,x+ew,64,Ui.SOFT);g.text(font,"<",x+5,52,Ui.TEXT,true);g.text(font,">",x+ew-10,52,Ui.TEXT,true);
        g.text(font,font.plainSubstrByWidth("Preset: "+(preset<0?"Custom":presets.get(preset)),ew-44),x+22,52,Ui.TEXT,true);
        int visible=Math.max(1,(bottom()-fieldsTop())/rowHeight());editorScroll=Math.min(editorScroll,Math.max(0,LABELS.length-visible));
        for(int i=editorScroll;i<LABELS.length&&i<editorScroll+visible;i++) {
            int y=fieldsTop()+(i-editorScroll)*rowHeight();g.fill(x,y,x+ew,y+rowHeight()-2,i==editing?Ui.ROW_SEL:Ui.INPUT);
            int split=x+ew/2;g.text(font,font.plainSubstrByWidth(LABELS[i],ew/2-8),x+4,y+4,Ui.DIM,true);
            String text=i==editing?input:value(i);g.text(font,font.plainSubstrByWidth(text,ew/2-10),split,y+4,Ui.TEXT,true);
            if(i>=3&&i<=8)g.fill(x+ew-10,y+3,x+ew-3,y+11,Integer.parseUnsignedInt(value(i).substring(1),16)|0xFF000000);
        }
        drawPreview(g,right,62,rw,previewHeight());
        g.text(font,"LOCAL THEMES (click to preview)",right,libraryTop()-16,Ui.MUTED,true);
        int rows=Math.max(0,(bottom()-libraryTop())/16);libraryScroll=Math.min(libraryScroll,Math.max(0,library.size()-Math.max(1,rows)));
        for(int i=libraryScroll;i<library.size()&&i<libraryScroll+rows;i++) {
            int y=libraryTop()+(i-libraryScroll)*16;g.fill(right,y,right+rw,y+14,Ui.INPUT);
            g.text(font,font.plainSubstrByWidth(library.get(i).getFileName().toString(),rw-8),right+4,y+3,Ui.TEXT,true);
        }
        if(library.isEmpty()&&rows>0)g.text(font,font.plainSubstrByWidth("Save your first theme, or import a code.",rw-8),right,libraryTop(),Ui.MUTED,true);
        String[] buttons={"APPLY","SAVE","COPY CODE","IMPORT","FOLDER"};int bw=w/5;
        for(int i=0;i<buttons.length;i++){g.fill(x+i*bw,height-52,x+(i+1)*bw-4,height-29,Ui.SOFT);g.text(font,font.plainSubstrByWidth(buttons[i],bw-8),x+i*bw+4,height-45,Ui.TEXT,true);}
        g.text(font,font.plainSubstrByWidth(message==null?"Invalid theme":message,w),x,height-18,Ui.MUTED,true);
    }
    private void drawPreview(GuiGraphicsExtractor g,int x,int y,int w,int h) {
        var p=draft.palette();int alpha=(int)Math.round(p.opacity()*255*(p.style().equals("Glass")?.75:1));
        g.fill(x,y,x+w,y+h,0xFF303947);int radius=p.style().equals("Rounded")||p.style().equals("Glass")?p.radius():0;
        int border=p.borderWidth();Ui.roundedFill(g,x,y,w,h,radius,(alpha<<24)|(p.background()&0xFFFFFF));
        for(int layer=0;layer<border;layer++) {
            int xx=x+layer,yy=y+layer,ww=w-2*layer,hh=h-2*layer,r=Math.max(0,Math.min(radius-layer,Math.min(ww,hh)/2));
            g.fill(xx+r,yy,xx+ww-r,yy+1,p.border());g.fill(xx+r,yy+hh-1,xx+ww-r,yy+hh,p.border());
            g.fill(xx,yy+r,xx+1,yy+hh-r,p.border());g.fill(xx+ww-1,yy+r,xx+ww,yy+hh-r,p.border());
            for(int row=0;row<r;row++){double dy=r-row-.5;int inset=Math.max(0,r-(int)Math.sqrt(r*r-dy*dy));
                for(int[] point:new int[][]{{xx+inset,yy+row},{xx+ww-1-inset,yy+row},{xx+inset,yy+hh-1-row},{xx+ww-1-inset,yy+hh-1-row}})g.fill(point[0],point[1],point[0]+1,point[1]+1,p.border());}
        }
        g.text(font,font.plainSubstrByWidth(draft.name(),w-12),x+6,y+7,p.text(),true);
        int panel=p.style().equals("Outlined")?p.background():p.panel();Ui.roundedFill(g,x+6,y+24,w-12,Math.max(16,h-46),radius,(alpha<<24)|(panel&0xFFFFFF));
        g.text(font,"Enabled module",x+12,y+31,p.accent(),true);
        if(h>80){g.fill(x+10,y+47,x+w-10,y+62,ThemePalette.blend(panel,p.accent(),(float)p.hover()));g.text(font,"Hovered row / setting",x+12,y+50,p.text(),true);}
        g.text(font,font.plainSubstrByWidth(draft.author().isEmpty()?"Secondary text":draft.author(),w-16),x+8,y+h-14,p.dim(),true);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubled) {
        int x=left(),w=paneWidth(),ew=editorWidth(),right=x+ew+14;double mx=event.x(),my=event.y();
        if(my>=height-52&&my<height-29&&mx>=x&&mx<x+w) {
            if(editing>=0){commit();if(editing>=0)return true;}
            try{switch((int)((mx-x)/(w/5))) {
                case 0 -> {ThemeStore.apply(draft,appearance);UtilityClient.saveConfig();message="Applied "+draft.name()+".";}
                case 1 -> {Path file=ThemeStore.save(draft,ThemeStore.directory());refreshLibrary();message="Saved "+file.getFileName()+". Share the JSON file or Copy Code.";}
                case 2 -> {Minecraft.getInstance().keyboardHandler.setClipboard(ThemeStore.encode(draft));message="Theme code copied. Paste it in Discord; others can Import it.";}
                case 3 -> importClipboard();
                case 4 -> {java.nio.file.Files.createDirectories(ThemeStore.directory());net.minecraft.util.Util.getPlatform().openPath(ThemeStore.directory());message="Theme folder: "+ThemeStore.directory();}
                default -> { }
            }}catch(Exception e){message=e.getMessage();}return true;
        }
        if(mx>=x&&mx<x+ew&&my>=48&&my<64){preset(event.button()==1||mx<x+20?-1:1);return true;}
        if(mx>=x&&mx<x+ew&&my>=fieldsTop()&&my<bottom()) {
            int row=(int)((my-fieldsTop())/rowHeight())+editorScroll;
            if(row<LABELS.length) {
                if(row==2) {JsonObject json=ThemeStore.json(draft);json.addProperty("style",STYLES.get(Math.floorMod(STYLES.indexOf(draft.palette().style())+(event.button()==1?-1:1),STYLES.size())));try{draft=ThemeStore.parse(json.toString());}catch(Exception e){message=e.getMessage();}editing=-1;}
                else{editing=row;input=value(row);replaceInput=true;}return true;
            }
        }
        if(mx>=right&&mx<x+w&&my>=libraryTop()&&my<bottom()) {
            int row=(int)((my-libraryTop())/16)+libraryScroll;
            if(row<library.size())try{draft=ThemeStore.load(library.get(row));editing=-1;preset=-1;message="Previewing "+draft.name()+". Apply to use it.";}catch(Exception e){message=e.getMessage();}return true;
        }
        return super.mouseClicked(event,doubled);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        int key=event.key();boolean control=(event.modifiers()&org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL)!=0;
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE){if(editing>=0)editing=-1;else onClose();return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_V&&control){if(editing<0)importClipboard();else{String pasted=Minecraft.getInstance().keyboardHandler.getClipboard().trim();if(pasted.length()>128)message="Field input is too long.";else{input=pasted;replaceInput=false;}}return true;}
        if(editing>=0) {
            if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_A&&control){replaceInput=true;return true;}
            if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER){commit();return true;}
            if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE){if(replaceInput)input="";else if(!input.isEmpty())input=input.substring(0,input.offsetByCodePoints(input.length(),-1));replaceInput=false;return true;}
        }
        return super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event){if(editing>=0&&event.codepoint()>=32){if(replaceInput){input="";replaceInput=false;}if(input.length()<128)input+=event.codepointAsString();return true;}return false;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){int step=(int)Math.signum(vertical)*2;if(x<left()+editorWidth())editorScroll=Math.max(0,editorScroll-step);else libraryScroll=Math.max(0,libraryScroll-step);return true;}
}
