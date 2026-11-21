package com.baggute.cheatclient;

import com.baggute.cheatclient.module.player.MouseTweaks;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/** Container drag state outside game classes, usable at startup and after attach. */
public final class ContainerMouseHooks {
    private ContainerMouseHooks() {}
    private static final Map<Object, Drag> DRAGS = new WeakHashMap<>();
    private static Method slotAt;
    private static Method click;
    private static final class Drag {
        final int button;
        final Set<Integer> visited = new HashSet<>();
        boolean owned, cancelled;
        Drag(int button) { this.button = button; }
    }
    public static void clear() { DRAGS.clear(); }

    /** 0 = press, 1 = drag, 2 = release. */
    public static boolean handle(Object screen, Object input, int phase) {
        if (phase == 2) {
            Drag drag = DRAGS.get(screen);
            if (input instanceof MouseButtonEvent event && drag != null && drag.button == event.button()) {
                DRAGS.remove(screen); return drag.owned;
            }
            return false;
        }
        if (!(screen instanceof AbstractContainerScreen<?>) || !(input instanceof MouseButtonEvent event)
                || UtilityClient.isUnloaded() || UtilityClient.moduleManager == null) return false;
        var module = UtilityClient.moduleManager.getByName("MouseTweaks");
        Drag previous = DRAGS.get(screen);
        if (!(module instanceof MouseTweaks tweaks) || !tweaks.isEnabled()
                || !tweaks.allowsButton(event.button())
                || (tweaks.requiresShift() && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0)) {
            if (previous != null) previous.cancelled = true;
            return phase == 1 && previous != null && previous.owned;
        }
        if (phase == 1 && (previous == null || previous.button != event.button())) return false;
        if (phase != 0 && phase != 1) return false;
        if (phase == 0) { previous = new Drag(event.button()); DRAGS.put(screen, previous); }
        if (previous.cancelled) return previous.owned;
        try {
            if (slotAt == null) {
                slotAt = AbstractContainerScreen.class.getDeclaredMethod("getHoveredSlot", double.class, double.class);
                click = AbstractContainerScreen.class.getDeclaredMethod("slotClicked", Slot.class, int.class, int.class, ContainerInput.class);
                slotAt.setAccessible(true); click.setAccessible(true);
            }
            Slot slot = (Slot) slotAt.invoke(screen, event.x(), event.y());
            if (slot == null || !slot.isActive() || slot.isFake()) return previous.owned;
            if (event.button() == 0 && !slot.hasItem()) return previous.owned;
            if (previous.visited.add(slot.index)) {
                click.invoke(screen, slot, slot.index, event.button(),
                        event.button() == 0 ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
                previous.owned = true;
            }
            return previous.owned;
        } catch (ReflectiveOperationException failure) {
            DRAGS.remove(screen);
            UtilityClient.LOGGER.error("MouseTweaks container hook failed", failure);
            return false;
        }
    }
}
