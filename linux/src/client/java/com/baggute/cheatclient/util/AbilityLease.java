package com.baggute.cheatclient.util;

import net.minecraft.world.entity.player.Abilities;

/** Restores only the flight values last written by this lease, on its owner. */
public final class AbilityLease {
    private final Object owner;
    private final Abilities abilities;
    private boolean mayfly, flying, applied;
    private float speed, writtenSpeed;
    public AbilityLease(Object owner, Abilities abilities) {
        this.owner = owner; this.abilities = abilities;
        mayfly = abilities.mayfly; flying = abilities.flying; speed = abilities.getFlyingSpeed();
    }
    public boolean ownedBy(Object current, Abilities currentAbilities) {
        return current == owner && currentAbilities == abilities;
    }
    public void apply(float nextSpeed) {
        if (applied) {
            if (!abilities.mayfly) mayfly = false;
            if (!abilities.flying) flying = false;
            if (Float.compare(abilities.getFlyingSpeed(), writtenSpeed) != 0) speed = abilities.getFlyingSpeed();
        }
        abilities.mayfly = abilities.flying = true;
        writtenSpeed = nextSpeed; abilities.setFlyingSpeed(nextSpeed); applied = true;
    }
    public void restore(Object current) {
        if (current != owner || !applied) return;
        if (abilities.mayfly) abilities.mayfly = mayfly;
        if (abilities.flying) abilities.flying = flying;
        if (Float.compare(abilities.getFlyingSpeed(), writtenSpeed) == 0) abilities.setFlyingSpeed(speed);
        applied = false;
    }
}
