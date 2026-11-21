package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.ColorSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
public final class HealthIndicators extends Module {
    public final com.baggute.cheatclient.settings.BoolSetting playersOnly = new com.baggute.cheatclient.settings.BoolSetting("playersOnly", "Show players only; disable to include mobs", true);
    public final DoubleSetting range=new DoubleSetting("range","Indicator range",64.0,8.0,256.0); public final DoubleSetting scale=new DoubleSetting("scale","Heart size",1.5,0.75,4.0); public final ColorSetting color=new ColorSetting("color","Health color",0xFFFF4040);
    public HealthIndicators(){super("HealthIndicators","Shows health indicators on entities.",Category.RENDER);addSetting(range);addSetting(scale);addSetting(color);addSetting(playersOnly);}
    public double getRange(){return range.get();} public double getScale(){return scale.get();} public int getColor(){return color.get();}

}

