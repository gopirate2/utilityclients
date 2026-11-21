// language: Java, file: Fullbright.java, target: Fabric 26.2
package com.baggute.cheatclient.module.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.DoubleSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.lang.reflect.Field;

/**
 * Brightens the world three ways, in the order the game can refuse them.
 *
 * <ol>
 *   <li><b>Store write.</b> {@code mc.options.gamma().set(10.0)} on enable and
 *       whenever the store drifts. This is the direct override and it is what the
 *       lightmap reads.</li>
 *   <li><b>Read override.</b> {@code GammaMixin} returns {@link #effectiveLevel()}
 *       from {@code OptionInstance.get} for the brightness option only. The slider
 *       is declared {@code 0.0..1.0}, so {@code OptionInstance.set} clamps against
 *       its own value set - the write can land as 1.0 while the read cannot be
 *       clamped, because it never touches the store.</li>
 *   <li><b>Night Vision.</b> The fallback for the case both of the above are
 *       refused: a server or a mod that re-writes the option every tick, a driver
 *       that ignores gamma above 1.0, a build where the mixin never applied. A
 *       client-side {@link MobEffectInstance} is applied to the local player and
 *       refreshed before it lapses. It is also the only mechanism that survives a
 *       locked slider, because it does not read the slider at all.</li>
 * </ol>
 *
 * <p>The store is read back through the raw {@code OptionInstance.value} field,
 * not through {@code get()} - the mixin intercepts {@code get()}, so a read-back
 * through it would return our own override and report success no matter what the
 * option actually stored. That made the previous clamp detection impossible.
 *
 * <p>Night Vision is applied with no particles and no icon, is refreshed at half
 * duration so the screen never flashes, and is removed on disable only when this
 * module added it. A player wearing a real Night Vision effect keeps it.
 */
public class Fullbright extends Module {

    /**
     * Brightness written into the option store.
     *
     * <p>1.0 is the top of vanilla's brightness slider and therefore the largest
     * value {@code OptionInstance.set} will accept. Writing more does not fail
     * quietly - 26.2 logs {@code Illegal option value 10.0 for Brightness} and
     * discards the write - so the old value produced an error line every tick and
     * stored nothing. The value above 1.0 comes from the read override in
     * {@code GammaMixin}, which never touches the store.
     */
    private static final double STORE_GAMMA = 1.0;

    /** Ticks per Night Vision application. Refreshed at half, so no flicker. */
    private static final int NIGHT_VISION_TICKS = 400;
    private static final int NIGHT_VISION_REFRESH = NIGHT_VISION_TICKS / 2;

    private final DoubleSetting level;
    private final BoolSetting nightVision =
            new BoolSetting("nightVision", "Apply Night Vision when the gamma override is refused", true);

    /** Brightness before the module was enabled; null when nothing is captured. */
    private Double previousGamma;
    /** What the option actually stored after our last write, clamp included. */
    private Double lastWritten;
    /** True once the gamma path was measured as refused and Night Vision took over. */
    private boolean usingFallback;
    /** True when this module is the one that put Night Vision on the player. */
    private boolean appliedNightVision;
    private net.minecraft.client.player.LocalPlayer effectPlayer;
    private MobEffectInstance ownedEffect;

    /** Raw store accessor, resolved once. */
    private static Field valueField;
    private static boolean valueFieldResolved;

    public Fullbright() {
        super("Fullbright", "Brightens the world so you can see in the dark.", Category.RENDER);
        this.level = new DoubleSetting("level", "Brightness level", 16.0, 1.0, 20.0);
        addSetting(level);
        addSetting(nightVision);
    }

    /** Value the gamma read returns while this module is on. */
    public double effectiveLevel() {
        return level.get();
    }

    /** True when the module is currently running on the Night Vision fallback. */
    public boolean usingFallback() {
        return usingFallback;
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        usingFallback = false;
        appliedNightVision = false;
        effectPlayer = null;
        ownedEffect = null;
        lastWritten = null;

        if (mc == null || mc.options == null) {
            UtilityClient.LOGGER.warn("[Fullbright] enabled before options existed; no gamma captured");
            return;
        }

        // Capture once. A re-enable without an intervening disable would otherwise
        // overwrite the player's real setting with our own override.
        if (previousGamma == null) {
            previousGamma = storedGamma(mc);
        }
        push(mc);
        UtilityClient.LOGGER.info("[Fullbright] read override at {} (stored {}, raw {})",
                level.get(), previousGamma, lastWritten);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        if (previousGamma == null) previousGamma = storedGamma(mc);

        // Rewrite only on drift: a player touching the slider, a config load, or a
        // server-side options reset all show up as a changed raw value. Writing
        // every tick would mark the options dirty 20 times a second.
        Double raw = storedGamma(mc);
        if (raw == null || lastWritten == null || raw.doubleValue() != lastWritten.doubleValue()) {
            push(mc);
            raw = storedGamma(mc);
        }

        boolean refused = isRefused(raw);
        if (refused && nightVision.get()) {
            applyNightVision(mc);
        } else if (appliedNightVision) {
            // The gamma path recovered; stop refreshing and let the effect lapse so
            // the world does not green out on top of a working brightness override.
            clearNightVision(mc);
        }
        usingFallback = refused && appliedNightVision;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        Double restore = previousGamma;
        previousGamma = null;
        lastWritten = null;

        if (mc != null) clearNightVision(mc);
        usingFallback = false;

        if (mc == null || mc.options == null) return;
        try {
            // No capture means we never wrote anything, so there is nothing to put
            // back - writing 0.0 here would black the world out instead of restoring it.
            if (restore != null) mc.options.gamma().set(restore);
        } catch (Throwable failure) {
            UtilityClient.LOGGER.warn("[Fullbright] could not restore the stored gamma", failure);
        }
        UtilityClient.LOGGER.info("[Fullbright] gamma override removed, restored {}", restore);
    }

    // ---- gamma ----

    /**
     * Writes the brightest value the option will hold and records what it accepted.
     *
     * <p>{@code OptionInstance.set} validates against the option's own value set, so
     * the raw read-back is the clamp-aware answer. A store that comes back well below
     * {@link #STORE_GAMMA} means the option refused even the slider maximum, which is
     * the case Night Vision exists for.
     */
    private void push(Minecraft mc) {
        try {
            mc.options.gamma().set(STORE_GAMMA);
            lastWritten = storedGamma(mc);
        } catch (Throwable failure) {
            lastWritten = null;
            UtilityClient.LOGGER.warn("[Fullbright] gamma write refused", failure);
        }
    }

    /**
     * The value actually held by the brightness option, bypassing this module's
     * read override.
     *
     * <p>{@code OptionInstance.value} is the only honest source: {@code get()} is
     * intercepted by {@code GammaMixin} while the module is on, so it returns our
     * own level no matter what the option stored.
     *
     * @return the stored value, or null when the field cannot be reached
     */
    private Double storedGamma(Minecraft mc) {
        Field field = valueField();
        if (field == null || mc.options == null) return null;
        try {
            Object value = field.get(mc.options.gamma());
            return value instanceof Double d ? d : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Field valueField() {
        if (valueFieldResolved) return valueField;
        valueFieldResolved = true;
        try {
            Field field = OptionInstance.class.getDeclaredField("value");
            field.setAccessible(true);
            valueField = field;
        } catch (Throwable failure) {
            valueField = null;
            UtilityClient.LOGGER.warn("[Fullbright] OptionInstance.value unreachable;"
                    + " fallback runs on the read override alone", failure);
        }
        return valueField;
    }

    /**
     * Whether the store refused the target.
     *
     * <p>Unreadable is not refused: a missing field cannot be told apart from a
     * working write, and the read override is already covering that build. Only a
     * readable value that is materially below the target counts.
     */
    private boolean isRefused(Double raw) {
        if (raw == null) return false;
        return raw.doubleValue() < STORE_GAMMA * 0.75;
    }

    // ---- night vision fallback ----

    private void applyNightVision(Minecraft mc) {
        if (mc.player == null) return;
        try {
            MobEffectInstance current = mc.player.getEffect(MobEffects.NIGHT_VISION);
            boolean ours = mc.player == effectPlayer && current == ownedEffect
                    && current != null && current.getAmplifier() == 0 && !current.isInfiniteDuration()
                    && !current.isVisible() && !current.showIcon() && current.getDuration() <= NIGHT_VISION_TICKS;
            // Duration alone never establishes ownership of a genuine potion.
            if (current != null && !ours) { appliedNightVision = false; ownedEffect = null; return; }
            if (ours && current.getDuration() > NIGHT_VISION_REFRESH) return;
            // ambient=false, visible=false, showIcon=false: no swirl, no timer, no
            // green tint in the inventory - it is a brightness patch, not a potion.
            mc.player.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, false, false, false));
            effectPlayer = mc.player;
            ownedEffect = mc.player.getEffect(MobEffects.NIGHT_VISION);
            appliedNightVision = true;
        } catch (Throwable failure) {
            UtilityClient.LOGGER.warn("[Fullbright] Night Vision fallback refused", failure);
        }
    }

    private void clearNightVision(Minecraft mc) {
        if (!appliedNightVision || mc == null || mc.player == null || mc.player != effectPlayer) {
            appliedNightVision = false;
            effectPlayer = null; ownedEffect = null;
            return;
        }
        appliedNightVision = false;
        try {
            MobEffectInstance current = mc.player.getEffect(MobEffects.NIGHT_VISION);
            // Only strip the effect this module put there. A real potion has a
            // longer duration than one application and is left alone.
            if (current != null && current == ownedEffect && !current.isInfiniteDuration()
                    && current.getAmplifier() == 0 && !current.isVisible() && !current.showIcon()
                    && current.getDuration() <= NIGHT_VISION_TICKS) {
                mc.player.removeEffect(MobEffects.NIGHT_VISION);
            }
        } catch (Throwable failure) {
            UtilityClient.LOGGER.warn("[Fullbright] could not clear the Night Vision fallback", failure);
        } finally {
            effectPlayer = null; ownedEffect = null;
        }
    }
}
