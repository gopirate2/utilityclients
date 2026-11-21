package com.baggute.cheatclient.gui;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.CustomModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;

/** In-game installation controls work even when Java Desktop is unavailable. */
public final class CustomModulesScreen extends Screen {
    private final Screen parent;
    private String path="", message="Use INSTALL EXAMPLE to try a working custom module without compiling anything.";
    private int scroll;
    public CustomModulesScreen(Screen parent) { super(Component.literal("Custom modules"));this.parent=parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
    private int left() { return Math.max(12,(width-650)/2); }
    private int top() { return 18; }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        UiRenderer.refreshTheme();g.fill(0,0,width,height,0xF0000000|(ThemePalette.current().background()&0xFFFFFF));
        int x=left(),y=top(),w=Math.min(650,width-x-12);
        g.text(font,"CUSTOM MODULES",x,y,Ui.TEXT,true);y+=20;
        g.text(font,"1. Install the example, or import a JAR made with the UtilityClient SDK.",x,y,Ui.MUTED,true);y+=14;
        g.text(font,"2. Close this panel, search for its module name, then enable or bind it.",x,y,Ui.MUTED,true);y+=20;
        String folder=CustomModules.directory().toString();
        g.text(font,font.plainSubstrByWidth(folder,w),x,y,Ui.TEXT,true);y+=20;
        String[] labels={"OPEN FOLDER","COPY PATH","OPEN SDK","INSTALL EXAMPLE","RELOAD"};
        int buttonWidth=Math.max(1,w/5);
        for(int i=0;i<labels.length;i++) { g.fill(x+i*buttonWidth,y,x+(i+1)*buttonWidth-4,y+22,Ui.SOFT);g.text(font,font.plainSubstrByWidth(labels[i],buttonWidth-8),x+i*buttonWidth+4,y+7,Ui.TEXT,true); }
        y+=32;g.text(font,"Paste the full path to your module JAR (Ctrl+V), then press Enter:",x,y,Ui.MUTED,true);y+=14;
        g.fill(x,y,x+w,y+22,Ui.SOFT);g.text(font,font.plainSubstrByWidth(path.isEmpty()?"C:\\path\\MyModules.jar":path,w-12),x+6,y+7,Ui.TEXT,true);y+=30;
        for(var line:font.split(Component.literal(message),w)) { g.text(font,line,x,y,Ui.accentFaded(1),true);y+=11; }
        y+=14;g.text(font,"LOAD REPORT (scroll for more)",x,y,Ui.MUTED,true);y+=16;
        var report=UtilityClient.moduleManager.customDiagnostics();
        if(report.isEmpty()) g.text(font,"No custom JARs installed. The SDK is in the folder shown above.",x,y,Ui.MUTED,true);
        int rows=Math.max(1,(height-y-24)/12);scroll=Math.min(scroll,Math.max(0,report.size()-rows));
        for(int i=scroll;i<report.size()&&i<scroll+rows;i++) { g.text(font,font.plainSubstrByWidth(report.get(i),w),x,y,Ui.TEXT,true);y+=12; }
        g.text(font,"New JARs load immediately. Replacing/removing loaded JARs requires a restart. Esc: back",x,height-16,Ui.MUTED,true);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubled) {
        int x=left(),y=top()+74,w=Math.min(650,width-x-12);
        if(event.button()==0&&event.x()>=x&&event.x()<x+w&&event.y()>=y&&event.y()<y+22) {
            int button=(int)((event.x()-x)/Math.max(1,w/5));
            try {
                switch(button) {
                    case 0,2 -> { Path folder=button==2?CustomModules.directory().resolve("SDK"):CustomModules.directory();java.nio.file.Files.createDirectories(folder);net.minecraft.util.Util.getPlatform().openPath(folder);message="Folder: "+folder; }
                    case 1 -> { Minecraft.getInstance().keyboardHandler.setClipboard(CustomModules.directory().toString());message="Module folder copied to your clipboard."; }
                    case 3 -> { CustomModules.installExample();UtilityClient.moduleManager.reloadCustomModules();UtilityClient.saveConfig();message="Example installed. Close this panel and search for HelloCustom."; }
                    case 4 -> { UtilityClient.moduleManager.reloadCustomModules();message="Scanned module JARs. Results are shown below."; }
                    default -> { }
                }
            } catch(Exception error) { message=error.getMessage(); }
            return true;
        }
        return super.mouseClicked(event,doubled);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        int key=event.key();
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_V&&(event.modifiers()&org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL)!=0) {path=Minecraft.getInstance().keyboardHandler.getClipboard().trim();return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE&&!path.isEmpty()) {path=path.substring(0,path.offsetByCodePoints(path.length(),-1));return true;}
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            try { CustomModules.importJar(Path.of(path.trim().replaceAll("^\"|\"$","")));UtilityClient.moduleManager.reloadCustomModules();UtilityClient.saveConfig();message="JAR imported. Check the load report below for its module name or errors."; }
            catch(Exception error){message=error.getMessage();} return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) { if(path.length()<4096)path+=new String(Character.toChars(event.codepoint()));return true; }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { scroll=Math.max(0,scroll-(int)Math.signum(vertical)*3);return true; }
}
