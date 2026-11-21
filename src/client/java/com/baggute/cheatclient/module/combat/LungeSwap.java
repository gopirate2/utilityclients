package com.baggute.cheatclient.module.combat;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/** Fast-hand attributes -> Lunge spear stab -> fast hand, with no camera or sprint changes. */
public final class LungeSwap extends Module {
    private final KeybindSetting activate = new KeybindSetting("Lunge", "Perform one attribute swap", -1, false);
    private final IntSetting swapBackDelay = new IntSetting("swapBackDelay", "Ticks before returning to the fast hand", 1, 1, 10);
    private final IntSetting minLunge = new IntSetting("minLunge", "Minimum Lunge level", 1, 1, 3);
    private final BoolSetting auto = new BoolSetting("auto", "Repeat while attack is held", false);
    private final BoolSetting requireTarget = new BoolSetting("requireTarget", "Require a reachable crosshair entity", false);
    private final DoubleSetting missChance = new DoubleSetting("missChance", "Chance to make an empty swing instead of a Lunge stab (%)", 0, 0, 100);
    private final BoolSetting sendChatFeedback = new BoolSetting("sendChatFeedback", "Send Chat Feedback: show missing-item feedback in local chat", false);
    private int fastSlot = -1, spearSlot = -1, returnTicks, ticks, expectedSlot = -1, readyTick = -1;
    private boolean pending;
    private boolean missCycle;
    private net.minecraft.client.player.LocalPlayer owner;

    public LungeSwap() {
        super("LungeSwap", "Charges with fast-hand attributes, swaps to a Lunge spear, stabs, and returns.", Category.COMBAT);
        addSetting(activate); addSetting(swapBackDelay); addSetting(minLunge); addSetting(auto); addSetting(requireTarget);
        addSetting(missChance); addSetting(sendChatFeedback);
    }

    @Override public void onRawKey(int key, int action) {
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && key == activate.getKey() && key != -1)
            arm(Minecraft.getInstance());
    }

    public boolean arm(Minecraft mc) {
        if (!isEnabled() || pending || returnTicks > 0 || mc == null || mc.player == null || mc.level == null
                || mc.gameMode == null || mc.gui.screen() != null || mc.isPaused()
                || !com.baggute.cheatclient.util.PhysicalInput.focused(mc)) return false;
        int selected = mc.player.getInventory().getSelectedSlot();
        int fast = isFast(mc.player.getInventory().getItem(selected)) ? selected : -1;
        int spear = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (fast < 0 && isFast(stack)) fast = i;
            if (spear < 0 && isLungeSpear(stack, mc)) spear = i;
        }
        if (fast < 0 || spear < 0) {
            String message = "[LungeSwap] Put a Lunge level " + minLunge.get()
                    + "+ spear and a fast item or empty slot in your hotbar.";
            com.baggute.cheatclient.module.misc.Notifications.push(message, false);
            if (sendChatFeedback.get()) com.baggute.cheatclient.util.ChatCommands.say(message, net.minecraft.ChatFormatting.RED);
            return false;
        }
        owner = mc.player; fastSlot = fast; spearSlot = spear; expectedSlot = selected;
        readyTick = -1; ticks = 0; pending = true;
        missCycle = missChance.get() > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble(100) < missChance.get();
        return true;
    }

    @Override public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (owner != null && mc.player != owner)) { clear(); return; }
        if (mc.level == null || mc.gameMode == null || mc.gui.screen() != null || mc.isPaused()
                || !com.baggute.cheatclient.util.PhysicalInput.focused(mc)) { restore(mc); return; }
        if (returnTicks > 0) {
            if (--returnTicks > 0) return;
            if (mc.player.getInventory().getSelectedSlot() == spearSlot) Stealth.restoreSlot(mc, fastSlot);
            clear(); return;
        }
        if (!pending) {
            if (auto.get() && mc.options.keyAttack.isDown()) arm(mc);
            return;
        }
        if (++ticks > 60 || !isLungeSpear(mc.player.getInventory().getItem(spearSlot), mc)
                || !isFast(mc.player.getInventory().getItem(fastSlot))) { restore(mc); return; }
        int selected = mc.player.getInventory().getSelectedSlot();
        if (selected != expectedSlot) { clear(); return; }
        if (selected != fastSlot) {
            // Equipment needs one vanilla tick to acquire the fast item's attributes.
            // A busy arbiter is a deferral; keep retrying without overriding manual swaps.
            if (Stealth.swapSlot(mc, fastSlot)) { expectedSlot = fastSlot; readyTick = ticks; }
            return;
        }
        if (readyTick < 0) { readyTick = ticks; return; }
        if (ticks <= readyTick) return;
        Entity target = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : mc.crosshairPickEntity;
        if (target == mc.player || (target != null && (!target.isAlive() || !Stealth.inEntityReach(mc.player, target)))) target = null;
        if (requireTarget.get() && target == null) return;
        if (Stealth.lungeAttack(mc, spearSlot, target, missCycle)) {
            pending = false;
            returnTicks = swapBackDelay.get();
        }
    }

    /** Attack-speed modifiers, rather than a hardcoded list of tool names. */
    private boolean isFast(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (stack.has(DataComponents.PIERCING_WEAPON)) return false;
        var attributes = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (attributes == null) return true;
        for (var entry : attributes.modifiers()) {
            if (entry.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)
                    && entry.modifier().amount() < 0) return false;
        }
        return true;
    }

    private boolean isLungeSpear(ItemStack stack, Minecraft mc) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.has(DataComponents.PIERCING_WEAPON)) return false;
        return lungeLevel(stack, mc) >= minLunge.get();
    }

    private int lungeLevel(ItemStack stack, Minecraft mc) {
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || mc.level == null) return 0;
        try {
            var registry = mc.level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.Identifier.withDefaultNamespace("lunge"));
            return enchantments.getLevel(registry.getOrThrow(key));
        } catch (Throwable ignored) {
            return 0;
        }
    }


    private void restore(Minecraft mc) {
        if (owner == mc.player && fastSlot >= 0
                && mc.player.getInventory().getSelectedSlot() == spearSlot) Stealth.restoreSlot(mc, fastSlot);
        clear();
    }
    private void clear() {
        pending = false; owner = null; fastSlot = spearSlot = expectedSlot = readyTick = -1;
        returnTicks = ticks = 0; missCycle = false;
    }
    @Override protected void onEnable() { clear(); }
    @Override public void onInputSuspended() { restore(Minecraft.getInstance()); }
    @Override protected void onDisable() { restore(Minecraft.getInstance()); }
}
