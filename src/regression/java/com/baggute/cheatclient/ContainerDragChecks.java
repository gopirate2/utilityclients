package com.baggute.cheatclient;

import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.player.MouseTweaks;
import com.baggute.cheatclient.settings.BoolSetting;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/** Uses the actual 26.2 container hit test with an intentionally stale rendered hover. */
public final class ContainerDragChecks {
    public static void main(String[] args) { run(); }
    public static void run() {
        MinecraftBootstrap.initialize();
        Object oldGame;
        UtilityClient oldClient = UtilityClient.INSTANCE;
        ModuleManager previous = UtilityClient.moduleManager;
        try {
            var instance = net.minecraft.client.Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true);
            oldGame = instance.get(null); ActivationChecks.setup();
        } catch (Exception failure) { throw new AssertionError(failure); }
        ModuleManager manager = new ModuleManager(); manager.init(); UtilityClient.moduleManager = manager;
        MouseTweaks tweaks = (MouseTweaks) manager.getByName("MouseTweaks"); tweaks.setEnabledHeld(true);
        try {
            Inventory inventory = new Inventory(null, new EntityEquipment());
            SimpleContainer items = new SimpleContainer(27);
            items.setItem(0, new ItemStack(Items.STONE, 16)); items.setItem(1, new ItemStack(Items.DIRT, 8));
            ChestMenu menu = ChestMenu.threeRows(1, inventory, items);
            Screen screen = new Screen(menu, inventory);
            require(ContainerMouseHooks.handle(screen, screen.event(0,0,true),0), "press uses current slot without a rendered hover");
            require(ContainerMouseHooks.handle(screen, screen.event(1,0,true),1), "drag reaches the next slot");
            ContainerMouseHooks.handle(screen, screen.event(0,0,true),1);
            require(screen.actions.size()==2 && screen.actions.get(0).equals("0:0:QUICK_MOVE") && screen.actions.get(1).equals("1:0:QUICK_MOVE"), "once per visited slot, current coordinates and correct quick-move action");
            require(!ContainerMouseHooks.handle(screen, screen.event(0,1,true),2), "other button release preserves active drag");
            require(ContainerMouseHooks.handle(screen, screen.event(0,0,true),2), "owned release consumed");
            require(!ContainerMouseHooks.handle(screen, screen.event(1,0,true),1), "release clears drag state");
            screen.actions.clear();
            require(!ContainerMouseHooks.handle(screen, new MouseButtonEvent(0,0,new MouseButtonInfo(0,GLFW.GLFW_MOD_SHIFT)),0), "background press keeps vanilla behavior");
            require(ContainerMouseHooks.handle(screen, screen.event(0,0,true),1), "drag can begin outside a slot and enter it");
            ContainerMouseHooks.handle(screen, screen.event(0,0,true),2);
            screen.actions.clear();
            require(!ContainerMouseHooks.handle(screen, screen.event(0,0,false),0), "Shift requirement enforced");
            require(!ContainerMouseHooks.handle(screen, screen.event(1,0,true),1), "late Shift cannot arm a cancelled press");
            ContainerMouseHooks.handle(screen, screen.event(0,0,true),2);
            require(ContainerMouseHooks.handle(screen, screen.event(0,1,true),0), "right-button pickup starts");
            require(ContainerMouseHooks.handle(screen, screen.event(0,1,true),2), "right release cannot replay vanilla pickup");
            require(screen.actions.equals(List.of("0:1:PICKUP")), "one right-button pickup request");
            ((BoolSetting)tweaks.getSettings().stream().filter(s->s.getName().equals("leftDrag")).findFirst().orElseThrow()).set(false);
            require(!ContainerMouseHooks.handle(screen, screen.event(0,0,true),0), "disabled button stays vanilla");
            require(!ContainerMouseHooks.handle(screen, screen.event(0,0,true),2), "unowned release stays vanilla");
        } finally {
            ContainerMouseHooks.clear(); tweaks.setEnabledHeld(false); manager.closeCustomModules(); UtilityClient.moduleManager = previous; UtilityClient.INSTANCE = oldClient; com.baggute.cheatclient.util.Stealth.reset();
            try { var instance = net.minecraft.client.Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, oldGame); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        System.out.println("PASS: MouseTweaks current-cursor hit testing, stale/no hover, cross-slot and background-start drags, repeat guards, modifier/button controls and consumed releases");
    }
    private static final class Screen extends ContainerScreen {
        final List<String> actions = new ArrayList<>();
        Screen(ChestMenu menu, Inventory inventory) { super(menu, inventory, Component.literal("fixture")); leftPos=100; topPos=80; hoveredSlot=menu.slots.get(10); }
        MouseButtonEvent event(int slot, int button, boolean shift) {
            Slot target=getMenu().slots.get(slot);
            return new MouseButtonEvent(leftPos+target.x+8,topPos+target.y+8,new MouseButtonInfo(button,shift?GLFW.GLFW_MOD_SHIFT:0));
        }
        @Override protected void slotClicked(Slot slot,int index,int button,ContainerInput input) { actions.add(index+":"+button+":"+input); }
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
