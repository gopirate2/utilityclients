package com.baggute.cheatclient.module.player;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.settings.IntSetting;
import net.minecraft.client.Minecraft;

/** Smooth scroll zoom applied to the camera, leaving persistent game options alone. */
public final class Zoom extends Module {
    private final DoubleSetting minFov=new DoubleSetting("minFov","Minimum zoom field of view",10,2,90);
    private final DoubleSetting step=new DoubleSetting("step","Scroll zoom strength",5,1,20);
    private final BoolSetting hideHand=new BoolSetting("hideHand","Hide first-person hand while zoomed",true);
    private final IntSetting scrollNotches=new IntSetting("scrollNotches","Starting zoom notches",3,0,20);
    private final DoubleSetting transition=new DoubleSetting("transition","Zoom transition time (seconds)",.1,.02,.5);
    private double notches, multiplier=1;
    private long lastFrame;
    public Zoom(){super("Zoom","Smooth zoom with scroll adjustment and proportional mouse sensitivity.",Category.PLAYER);addSetting(minFov);addSetting(step);addSetting(hideHand);addSetting(scrollNotches);addSetting(transition);}
    @Override protected void onEnable(){notches=scrollNotches.get();lastFrame=0;}
    @Override protected void onDisable(){notches=0;}
    public boolean onScroll(double amount){
        if(!isEnabled()||!Double.isFinite(amount)||amount==0)return false;
        notches=Math.max(0,Math.min(20,notches+amount)); return true;
    }
    public float applyFov(float vanilla){
        Minecraft mc=Minecraft.getInstance();
        boolean active=isEnabled()&&mc!=null&&mc.player!=null&&mc.gui.screen()==null&&!mc.isPaused();
        double target=active?Math.max(minFov.get()/Math.max(1,vanilla),Math.pow(1+step.get()*.04,-notches)):1;
        target=Math.min(1,target);
        long now=System.nanoTime();double seconds=lastFrame==0?1.0/60:Math.min(.1,(now-lastFrame)/1e9);lastFrame=now;
        multiplier=smooth(multiplier,target,seconds,transition.get());
        return (float)(vanilla*multiplier);
    }
    public static double smooth(double current,double target,double seconds,double duration){
        double next=target+(current-target)*Math.exp(-Math.max(0,seconds)/Math.max(.001,duration));
        return Math.abs(next-target)<.0001?target:next;
    }
    public double sensitivity(double vanilla){
        double adjusted=(Math.cbrt(Math.pow(vanilla*.6+.2,3)*multiplier)-.2)/.6;
        return Math.max(0,Math.min(1,adjusted));
    }
    public boolean shouldHideHand(){return isEnabled()&&hideHand.get()&&multiplier<.98;}
}
