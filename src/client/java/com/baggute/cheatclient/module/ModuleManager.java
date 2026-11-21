// language: Java, file: ModuleManager.java, target: Fabric 26.2
package com.baggute.cheatclient.module;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.util.Stealth;
import com.baggute.cheatclient.module.combat.AimAssist;
import com.baggute.cheatclient.module.combat.Aimbot;
import com.baggute.cheatclient.module.combat.AntiMiss;
import com.baggute.cheatclient.module.combat.AutoClicker;
import com.baggute.cheatclient.module.combat.Reach;
import com.baggute.cheatclient.module.combat.AutoJumpReset;
import com.baggute.cheatclient.module.combat.AutoMace;
import com.baggute.cheatclient.module.combat.AutoPearlCatch;
import com.baggute.cheatclient.module.combat.AutoStunSlam;
import com.baggute.cheatclient.module.combat.KillAura;
import com.baggute.cheatclient.module.combat.ShieldBreaker;
import com.baggute.cheatclient.module.combat.Triggerbot;
import com.baggute.cheatclient.module.combat.AutoAnchor;
import com.baggute.cheatclient.module.combat.AutoBreachSwap;
import com.baggute.cheatclient.module.combat.AutoCrystal;
import com.baggute.cheatclient.module.combat.AutoBucket;
import com.baggute.cheatclient.module.combat.AutoCrossbow;
import com.baggute.cheatclient.module.combat.AutoInstacart;
import com.baggute.cheatclient.module.combat.AutoWeb;
import com.baggute.cheatclient.module.combat.AutoXbow;
import com.baggute.cheatclient.module.combat.BowAimbot;
import com.baggute.cheatclient.module.combat.HitSelect;
import com.baggute.cheatclient.module.combat.SmartWTap;
import com.baggute.cheatclient.module.combat.LungeSwap;
import com.baggute.cheatclient.module.combat.WebDrain;
import com.baggute.cheatclient.module.apex.ApexModules;
import com.baggute.cheatclient.module.misc.Debug;
import com.baggute.cheatclient.module.misc.GamemodeDetector;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.module.misc.Panic;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.module.misc.PresetSwitch;
import com.baggute.cheatclient.module.misc.SeedCrackerX;
import com.baggute.cheatclient.module.player.AutoTotem;
import com.baggute.cheatclient.module.player.CartGrabber;
import com.baggute.cheatclient.module.player.MouseTweaks;
import com.baggute.cheatclient.module.player.Zoom;
import com.baggute.cheatclient.module.movement.Flight;
import com.baggute.cheatclient.module.movement.NoFall;
import com.baggute.cheatclient.module.movement.Speed;
import com.baggute.cheatclient.module.movement.StepUp;
import com.baggute.cheatclient.module.movement.WaterWalk;
import com.baggute.cheatclient.module.render.ArrayListModule;
import com.baggute.cheatclient.module.render.AntiVanish;
import com.baggute.cheatclient.module.render.BlockESP;
import com.baggute.cheatclient.module.render.ESP;
import com.baggute.cheatclient.module.render.Freecam;
import com.baggute.cheatclient.module.render.Fullbright;
import com.baggute.cheatclient.module.render.StorageESP;
import com.baggute.cheatclient.module.render.ArmorIndicators;
import com.baggute.cheatclient.module.render.HealthIndicators;
import com.baggute.cheatclient.module.render.Nametags;
import com.baggute.cheatclient.module.render.TargetHud;
import com.baggute.cheatclient.module.render.Trajectory;
import com.baggute.cheatclient.module.automation.AutoEat;
import com.baggute.cheatclient.module.automation.AutoMine;
import com.baggute.cheatclient.module.automation.AutoPot;
import com.baggute.cheatclient.module.automation.AutoWalk;
import com.baggute.cheatclient.module.automation.Baritone;
import com.baggute.cheatclient.settings.KeybindSetting;
import com.baggute.cheatclient.settings.Setting;
import com.baggute.cheatclient.testing.ModuleStats;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ModuleManager {

    private final List<Module> modules = new ArrayList<>();
    private final Map<Category, List<Module>> byCategory = new EnumMap<>(Category.class);
    private final CustomModules customModules = new CustomModules();

    public void init() {
        for (Category c : Category.values()) byCategory.put(c, new ArrayList<>());

        // ---- COMBAT ----
        register(new Triggerbot());
        register(new AutoClicker());
        register(new com.baggute.cheatclient.module.combat.Velocity());
        register(new AimAssist());
        register(new Aimbot());
        register(new AntiMiss());
        register(new Reach());
        register(new ShieldBreaker());
        register(new AutoPearlCatch());
        register(new AutoJumpReset());
        register(new AutoMace());
        register(new AutoStunSlam());
        register(new KillAura());
        register(new AutoCrossbow());
        register(new AutoInstacart());
        register(new LungeSwap());
        register(new AutoBucket());
        register(new WebDrain());
        register(new AutoWeb());
        register(new AutoBreachSwap());
        register(new AutoCrystal());
        register(new AutoAnchor());
        register(new AutoXbow());

        // ---- AUTOMATION ----
        register(new AutoWalk());
        register(new AutoMine());
        register(new AutoPot());
        register(new AutoEat());
        register(new Baritone());

        // ---- MOVEMENT ----
        register(new Flight());
        register(new Speed());
        register(new StepUp());
        register(new WaterWalk());
        register(new NoFall());

        // ---- PLAYER ----
        register(new AutoTotem());
        register(new CartGrabber());
        register(new com.baggute.cheatclient.module.player.InvManager());
        register(new MouseTweaks());
        register(new Zoom());

        // ---- RENDER ----
        register(new ESP());
        register(new ArrayListModule());
        register(new Notifications());
        register(new BlockESP());
        register(new StorageESP());
        register(new Fullbright());
        register(new Freecam());
        register(new HealthIndicators());
        register(new ArmorIndicators());
        register(new AntiVanish());

        // ---- MISC ----
        register(new Hud());
        register(new PresetSwitch());
        register(new SeedCrackerX());
        register(new GamemodeDetector());
        register(new com.baggute.cheatclient.module.misc.ActionTiming());
        register(new com.baggute.cheatclient.module.misc.Appearance());

        register(new Debug());
        register(new Panic());
        register(new HitSelect());
        register(new SmartWTap());
        register(new BowAimbot());
        register(new Nametags());
        register(new TargetHud());
        register(new Trajectory());
        register(new com.baggute.cheatclient.module.misc.StreamProof());
        register(new com.baggute.cheatclient.module.misc.Watermark());

        // ---- APEX IMPORT (labels only, tab: skidded off apex) ----
        for (Module apex : ApexModules.createAll()) register(apex);
        for (Module module : modules) {
            if (module instanceof com.baggute.cheatclient.module.apex.ApexModule apex) {
                String target = com.baggute.cheatclient.module.apex.ApexSettings.delegate(apex.entry());
                if (target != null) apex.bindDelegateSettings(getByName(target));
            }
        }
        customModules.load(this, CustomModules.directory());
    }

    public void closeCustomModules() { customModules.close(); }
    public void registerCustom(java.util.Collection<? extends Module> batch) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Module existing : modules) names.add(existing.getName().toLowerCase(java.util.Locale.ROOT));
        for (Module module : batch) {
            if (module == null || module.getName() == null || !module.getName().matches("[A-Za-z][A-Za-z0-9 _-]{0,47}")
                    || module.getDescription() == null || module.getDescription().length() > 512
                    || module.getCategory() == null || module.getCategory().isVirtual()
                    || !names.add(module.getName().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Invalid or duplicate custom module");
            java.util.Set<String> settings = new java.util.HashSet<>();
            if (module.getSettings().size() > 64) throw new IllegalArgumentException("Too many settings");
            for (Setting setting : module.getSettings()) if (setting == null || setting.getName() == null || !settings.add(setting.getName())
                    || (setting.getName().equals("keybind") && !(setting instanceof KeybindSetting)))
                throw new IllegalArgumentException("Invalid or duplicate custom setting");
        }
        for (Module module : batch) ensureKeybind(module);
        for (Module module : batch) register(module);
    }
    private void register(Module module) {
        // A duplicate name breaks getByName, the config keys and the HUD list, so
        // the first module to claim a name keeps it and the import is dropped.
        for (Module existing : modules) {
            if (existing.getName().equalsIgnoreCase(module.getName())) {
                UtilityClient.LOGGER.warn("[ModuleManager] duplicate module '{}' ignored", module.getName());
                return;
            }
        }
        ensureKeybind(module);
        modules.add(module);
        byCategory.get(module.getCategory()).add(module);
    }

    private void ensureKeybind(Module m) {
        for (Setting s : m.getSettings()) if (s instanceof KeybindSetting ks && "keybind".equals(ks.getName())) return;
        boolean held = m.getName().equals("AutoGapple") || m.getName().equals("AutoShield");
        m.insertSetting(0, new KeybindSetting("keybind", held ? "Hold to act" : "Toggle keybind", -1, held));
    }

    private KeybindSetting getKeybind(Module m) {
        for (Setting s : m.getSettings()) if (s instanceof KeybindSetting ks && "keybind".equals(ks.getName())) return ks;
        return null;
    }

    private boolean hasSecondaryKey(Module m, int keyCode) {
        for (Setting s : m.getPersistentSettings()) {
            if (s instanceof KeybindSetting ks && !"keybind".equals(ks.getName()) && ks.getKey() == keyCode) return true;
        }
        return false;
    }

    /**
     * The client tick: every enabled module, isolated from its neighbours.
     *
     * <p>{@code onTick()} is wrapped because a module that throws must not take the
     * rest of the loop down, and the throw has to be visible or the failure looks
     * like a module that silently does nothing.
     *
     * <p>Two counters move here. The client-wide one advances before the loop, so the
     * audit has a clock that runs whether or not a module does; the per-module one
     * advances only after the module's hook returns, so a module whose tick path dies
     * reads as absent rather than as healthy.
     */
    public void onTick() {
        service("tick counter", ModuleStats::clientTick);
        try { Stealth.beginTick(); }
        catch (Throwable failure) {
            com.baggute.cheatclient.util.ClientErrors.report("interaction arbiter", failure);
            // Block actions for this slice after a failed inventory exchange.
            // beginTick clears the flags again on the next vanilla tick.
            Stealth.markInteraction();
        }
        service("backtrack tick", com.baggute.cheatclient.module.combat.BacktrackBuffer::tick);
        service("kit tick", () -> com.baggute.cheatclient.config.KitManager.tick(Minecraft.getInstance()));
        SeedCrackerX cracker = getSeedCrackerX();
        if (cracker != null) service("seed config", cracker::syncFromConfig);
        Module catchFirst = getByName("AutoPearlCatch");
        if (catchFirst != null && catchFirst.isEnabled()) tickModule(catchFirst);
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            if (m == catchFirst) continue;
            tickModule(m);
        }
    }

    private void tickModule(Module module) {
        try {
            com.baggute.cheatclient.module.misc.ActionTiming.run(module, module::onTick);
            ModuleStats.tick(module.getName());
        } catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(module.getName() + "/tick", failure); }
    }

    public void onRenderFrame(float partialTick) {
        service("frame counter", ModuleStats::clientFrame);
        for (Module m : modules) {
            if (m.isEnabled()) {
                try {
                    m.onRenderFrame(partialTick);
                    ModuleStats.render(m.getName());
                } catch (Throwable t) {
                    com.baggute.cheatclient.util.ClientErrors.report(m.getName() + "/frame", t);
                }
            }
        }
    }

    public void releaseHeld() {
        for (Module m : modules) {
            KeybindSetting key = getKeybind(m);
            if (key != null && key.isHeld()) service(m.getName() + "/release", () -> m.setEnabledHeld(false));
        }
    }

    public void suspendInput() {
        for (Module module : modules) if (module.isEnabled())
            service(module.getName() + "/input suspension", module::onInputSuspended);
    }

    public void onKey(int keyCode, int action) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN || keyCode < 0
                || (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE && action != GLFW.GLFW_REPEAT)) return;
        for (Module m : modules) {
            KeybindSetting ks = getKeybind(m);
            if (ks == null || ks.getKey() == -1 || ks.getKey() != keyCode) continue;
            // A momentary action takes precedence over a duplicate toggle on the
            // same module; pressing Fire must not switch its enabled module off.
            if (!ks.isHeld() && hasSecondaryKey(m, keyCode)) continue;

            service(m.getName() + "/activation", () -> {
              if (ks.isHeld()) {
                // Held binds follow the key: down means on, release means off.
                if (action == GLFW.GLFW_PRESS) m.setEnabledHeld(true);
                else if (action == GLFW.GLFW_RELEASE) m.setEnabledHeld(false);
              } else if (action == GLFW.GLFW_PRESS) {
                m.toggle();
              }
            });
        }
        // Secondary binds reach every module, enabled or not: Panic and PresetSwitch
        // exist precisely to act when nothing else is on, so gating them on enabled
        // state made them dead keys.
        for (Module m : modules) {
            if (hasSecondaryKey(m, keyCode)) service(m.getName() + "/action", () -> com.baggute.cheatclient.module.misc.ActionTiming.run(m, () -> m.onRawKey(keyCode, action)));
        }
        for (Module m : modules) {
            if (m.isEnabled()) {
                try { com.baggute.cheatclient.module.misc.ActionTiming.run(m, () -> m.onKey(keyCode, action)); }
                catch (Throwable t) {
                    com.baggute.cheatclient.util.ClientErrors.report(m.getName() + "/key", t);
                }
            }
        }
    }

    /**
     * Keys that must work regardless of an open screen (the panic switch).
     *
     * @return true when the key was consumed
     */
    public boolean onGlobalKey(int keyCode, int action) {
        for (Module m : modules) {
            try { if (m instanceof Panic panic && panic.tryTrigger(keyCode, action)) return true; }
            catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(m.getName() + "/global key", failure); }
        }
        return false;
    }

    private static void service(String name, Runnable callback) {
        try { callback.run(); }
        catch (Throwable failure) { com.baggute.cheatclient.util.ClientErrors.report(name, failure); }
    }

    public List<Module> getModules() { return modules; }

    /**
     * Modules already bound to this key, primary or secondary. Empty means the bind
     * is clean.
     */
    public List<Module> getKeyConflicts(int keyCode, Module self) {
        List<Module> out = new ArrayList<>();
        if (keyCode == -1 || keyCode == GLFW.GLFW_KEY_UNKNOWN) return out;
        for (Module m : modules) {
            if (m == self) continue;
            KeybindSetting primary = getKeybind(m);
            if (primary != null && primary.getKey() == keyCode) { out.add(m); continue; }
            for (Setting s : m.getSettings()) {
                if (s instanceof KeybindSetting k && !"keybind".equals(k.getName()) && k.getKey() == keyCode) {
                    if (self != null && self.getSettings().contains(s)) continue;
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    public boolean hasInternalKeyConflict(Module module, KeybindSetting binding) {
        if (module == null || binding.getKey() < 0) return false;
        for (Setting setting : module.getSettings())
            if (setting instanceof KeybindSetting key && key != binding && key.getKey() == binding.getKey()) return true;
        return false;
    }

    public List<Module> getByCategory(Category category) {
        return byCategory.computeIfAbsent(category, k -> new ArrayList<>());
    }

    public Module getByName(String name) {
        for (Module m : modules) if (m.getName().equalsIgnoreCase(name)) return m;
        return null;
    }

    /**
     * Every module that currently has at least one key bound, primary or
     * secondary. The BINDS tab used to look only at the toggle key, which hid
     * action binds like Panic, preset cycling or the spear macro.
     */
    public List<Module> getBoundModules() {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (!getBoundKeys(m).isEmpty()) out.add(m);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** Non-default keybind settings on a module, primary toggle first. */
    public List<KeybindSetting> getBoundKeys(Module module) {
        List<KeybindSetting> out = new ArrayList<>();
        for (Setting s : module.getSettings()) {
            if (s instanceof KeybindSetting k
                    && k.getKey() != -1 && k.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
                out.add(k);
            }
        }
        return out;
    }

    public Module getByKey(int keyCode) {
        // -1 (and GLFW's unknown key) means "unbound"; every module defaults to it,
        // so matching on it would return the first module in the list.
        if (keyCode == -1 || keyCode == GLFW.GLFW_KEY_UNKNOWN) return null;
        for (Module m : modules) {
            KeybindSetting ks = getKeybind(m);
            if (ks != null && ks.getKey() == keyCode) return m;
        }
        return null;
    }

    // ---- typed accessors ----

    /**
     * Disables every module in the given categories.
     *
     * <p>Deferred even when already on the client thread: {@code
     * Minecraft#execute} runs inline there and queues otherwise, and the caller
     * is a packet hook that can arrive on either. {@code setEnabled} fires
     * {@code onDisable}, and a module that touches the level off-thread throws.
     */
    public void disableCategories(Category... categories) {
        Minecraft.getInstance().execute(() -> {
            for (Category category : categories) {
                for (Module module : getByCategory(category)) {
                    if (module.isEnabled()) service(module.getName() + "/disable", () -> module.setEnabled(false));
                }
            }
        });
    }

    public AntiVanish getAntiVanish() { return (AntiVanish) getByName("AntiVanish"); }
    public ESP getESP() { return (ESP) getByName("ESP"); }
    public BlockESP getBlockESP() { return (BlockESP) getByName("BlockESP"); }
    public StorageESP getStorageESP() { return (StorageESP) getByName("StorageESP"); }
    public Triggerbot getTriggerbot() { return (Triggerbot) getByName("Triggerbot"); }
    public AimAssist getAimAssist() { return (AimAssist) getByName("AimAssist"); }
    public ShieldBreaker getShieldBreaker() { return (ShieldBreaker) getByName("ShieldBreaker"); }
    public AutoPearlCatch getAutoPearlCatch() { return (AutoPearlCatch) getByName("AutoPearlCatch"); }
    public AutoJumpReset getAutoJumpReset() { return (AutoJumpReset) getByName("AutoJumpReset"); }
    public AutoMace getAutoMace() { return (AutoMace) getByName("AutoMace"); }
    public AutoStunSlam getAutoStunSlam() { return (AutoStunSlam) getByName("AutoStunSlam"); }
    public KillAura getKillAura() { return (KillAura) getByName("KillAura"); }
    public Fullbright getFullbright() { return (Fullbright) getByName("Fullbright"); }
    public Freecam getFreecam() { return (Freecam) getByName("Freecam"); }
    public Flight getFlight() { return (Flight) getByName("Flight"); }
    public Speed getSpeed() { return (Speed) getByName("Speed"); }
    public StepUp getStepUp() { return (StepUp) getByName("StepUp"); }
    public WaterWalk getWaterWalk() { return (WaterWalk) getByName("WaterWalk"); }
    public NoFall getNoFall() { return (NoFall) getByName("NoFall"); }
    public Hud getHud() { return (Hud) getByName("Hud"); }
    public SeedCrackerX getSeedCrackerX() { return (SeedCrackerX) getByName("SeedCrackerX"); }
}
