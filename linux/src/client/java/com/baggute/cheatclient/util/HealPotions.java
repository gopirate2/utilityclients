package com.baggute.cheatclient.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Healing automation accepts only safe effects and supported delivery types. */
public final class HealPotions {
    public enum Delivery { NONE, DRINK, SPLASH }
    private HealPotions() {}

    public static Delivery delivery(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Delivery.NONE;
        Delivery form = stack.is(Items.POTION) ? Delivery.DRINK
                : stack.is(Items.SPLASH_POTION) ? Delivery.SPLASH : Delivery.NONE;
        if (form == Delivery.NONE) return form;
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return Delivery.NONE;
        boolean heals = false;
        for (var effect : contents.getAllEffects()) {
            if (!effect.getEffect().value().isBeneficial()) return Delivery.NONE;
            if (effect.getEffect().equals(MobEffects.INSTANT_HEALTH)
                    || effect.getEffect().equals(MobEffects.REGENERATION)) heals = true;
        }
        return heals ? form : Delivery.NONE;
    }
}
