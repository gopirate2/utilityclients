package com.baggute.cheatclient;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.ModuleManager;
import com.baggute.cheatclient.module.apex.ApexModule;
import com.baggute.cheatclient.module.apex.ApexImpl;
import com.baggute.cheatclient.module.combat.*;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.module.misc.Panic;
import com.baggute.cheatclient.render.HudRenderer;
import com.baggute.cheatclient.settings.*;
import com.baggute.cheatclient.stealth.StealthConfig;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Predicate;

/** Real module/action code and vanilla item components, with no live game or network. */
public final class ActivationChecks {
    private static final Unsafe MEMORY;
    static {
        try { Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); MEMORY = (Unsafe) field.get(null); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Game game;
    private static Hand player;
    private static World world;
    private static Mode mode;
    private static GuiShim gui;
    private static final List<Use> USES = new ArrayList<>();
    private record Use(int tick, String item, InteractionHand hand, float yaw, float pitch) {}

    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        MinecraftBootstrap.initialize();
        Object oldGame = field(Minecraft.class, "instance").get(null);
        ModuleManager oldManager = UtilityClient.moduleManager;
        UtilityClient oldInstance = UtilityClient.INSTANCE;
        Field config = Arrays.stream(StealthConfig.class.getDeclaredFields()).filter(f -> f.getType() == StealthConfig.class).findFirst().orElseThrow();
        config.setAccessible(true); Object oldConfig = config.get(null);
        var constructor = StealthConfig.class.getDeclaredConstructor(); constructor.setAccessible(true); config.set(null, constructor.newInstance());
        try {
            setup(); catalog(); bindingFailures(); sharedTick(); crossbow(); bucketRepairs(); lunge(); pearlSpeed(false, 0); pearlSpeed(true, 0); pearlSpeed(false, 4);
            manualPearlSpeed(false, true); manualPearlSpeed(true, false); rejectedPearls(); atomicItemUse();
            timingAndPriority(); quietFeedback(); missControls(); livePearls(); apexSequences(); delegates(); combatRepairs(); requestedRepairs(); hud(); panic();
            System.out.println("PASS: action press/repeat/release, failure isolation, duplicate bindings, shared tick, AutoXbow arming/charging, Lunge III stab/return, fast pearl packet aim, own/foreign pearl tracking, offhand timing, Apex sequence/delegate cleanup and HUD preview");
        } finally {
            field(Minecraft.class, "instance").set(null, oldGame);
            UtilityClient.moduleManager = oldManager; UtilityClient.INSTANCE = oldInstance; config.set(null, oldConfig);
            Stealth.reset(); field(UtilityClient.class, "initialized").setBoolean(null, false);
        }
    }

    static void setup() throws Exception {
        game = allocate(Game.class); game.focused = true;
        gui = allocate(GuiShim.class); field(Minecraft.class, "gui").set(game, gui);
        gui.messages = new ArrayList<>(); gui.listener = allocate(ChatShim.class);
        world = allocate(World.class); world.blocks = new HashMap<>(); world.entities = new ArrayList<>();
        player = allocate(Hand.class); player.inventory = new Inventory(player, new EntityEquipment());
        field(Entity.class, "position").set(player, new Vec3(0, 64, 0));
        field(Entity.class,"id").setInt(player,1);
        field(Entity.class, "eyeHeight").setFloat(player, 1.62f);
        field(Entity.class, "dimensions").set(player, EntityDimensions.scalable(.6f, 1.8f));
        player.setUUID(UUID.randomUUID());
        player.setBoundingBox(new AABB(-.3, 64, -.3, .3, 65.8, .3));
        player.offhand = ItemStack.EMPTY; player.motion = Vec3.ZERO; player.cooldowns = new ItemCooldowns();
        player.health=20; player.charge=1; player.useTicks=20; player.ammo=true;
        var connection = allocate(Link.class); connection.packets = new ArrayList<>(); field(LocalPlayer.class, "connection").set(player, connection);
        mode = allocate(Mode.class); mode.game = game; mode.itemResult = InteractionResult.SUCCESS;
        game.level = world; game.player = player; game.gameMode = mode;
        field(Minecraft.class, "font").set(game, allocate(FontShim.class));
        field(Minecraft.class, "instance").set(null, game);
        UtilityClient.INSTANCE = new UtilityClient();
        Stealth.reset(); USES.clear();
    }

    private static void catalog() {
        ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager; manager.init();
        Set<String> names = new HashSet<>(); int actions = 0, unavailable = 0;
        for (Module module : manager.getModules()) {
            require(names.add(module.getName().toLowerCase(Locale.ROOT)), "unique registered module");
            require(module.getPersistentSettings().stream().filter(s -> s instanceof KeybindSetting && s.getName().equals("keybind")).count() == 1,
                    "one primary key for " + module.getName());
            for (Setting setting : module.getPersistentSettings()) if (setting instanceof KeybindSetting key && !key.getName().equals("keybind")) {
                try { require(module.getClass().getMethod("onRawKey", int.class, int.class).getDeclaringClass() != Module.class,
                        "action has a handler: " + module.getName() + "/" + key.getName()); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                require(key.activationLabel().equals("press"), "momentary action labelled correctly"); actions++;
            }
            if (module instanceof ApexModule apex && !apex.isImplemented()) {
                module.setEnabledHeld(true); require(!module.isEnabled(), "unavailable held entry stays off"); unavailable++;
            }
        }
        require(manager.getModules().size() >= 100 && actions >= 8, "complete native/imported catalog");
        ApexModule unsupported = new ApexModule("UnavailableFixture", "Fixture");
        unsupported.setEnabled(true); unsupported.setEnabledHeld(true);
        require(!unsupported.isEnabled(), "unavailable entries reject both toggle and held activation");
        System.out.println("PASS: all " + names.size() + " registered modules / " + actions + " action keys checked; " + unavailable + " unavailable entries reject activation");
    }

    private static void bindingFailures() {
        ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        Probe broken = new Probe("Broken", 65, 66), healthy = new Probe("Healthy", 65, 66);
        broken.failEnable = broken.failAction = true;
        manager.getModules().add(broken); manager.getModules().add(healthy);
        manager.onKey(65, GLFW.GLFW_PRESS);
        require(!broken.isEnabled() && healthy.isEnabled(), "failed enable rolls back and next module enables");
        manager.onKey(65, GLFW.GLFW_REPEAT); manager.onKey(65, GLFW.GLFW_RELEASE);
        require(healthy.isEnabled() && healthy.enables == 1, "repeat/release never toggle twice");
        manager.onKey(66, GLFW.GLFW_PRESS);
        require(healthy.actions == 1, "failed action does not stop next action");
        primary(healthy).setKey(66); manager.onKey(66, GLFW.GLFW_PRESS);
        require(healthy.isEnabled() && healthy.actions == 2, "duplicate action key does not disable its module");
        require(manager.hasInternalKeyConflict(healthy, primary(healthy)), "internal duplicate is visible");
        Probe held = new Probe("Held", 67, -1); primary(held).setHeld(true); manager.getModules().add(held);
        manager.onKey(67, GLFW.GLFW_PRESS); require(held.isEnabled(), "held press enables");
        manager.onKey(67, GLFW.GLFW_RELEASE); require(!held.isEnabled(), "held release disables");
        held.setEnabledHeld(true); broken.failEnable = false; broken.failDisable = true; primary(broken).setHeld(true); broken.setEnabledHeld(true);
        manager.releaseHeld(); require(!held.isEnabled() && !broken.isEnabled(), "held cleanup isolated");
        healthy.failTick = true; held.setEnabled(true); tick(manager);
        require(held.ticks == 1, "failed module tick does not stop next module");
        manager.onKey(-1, GLFW.GLFW_PRESS); require(healthy.actions == 2, "unbound keys ignored");
    }

    private static void sharedTick() throws Exception {
        ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        Probe probe = new Probe("TickProbe", -1, -1); manager.getModules().add(probe); probe.setEnabled(true);
        field(UtilityClient.class, "initialized").setBoolean(null, false);
        UtilityClient.onClientTick(game); require(probe.ticks == 0, "partial init cannot tick");
        field(UtilityClient.class, "initialized").setBoolean(null, true);
        for (int i = 0; i < 40; i++) UtilityClient.onClientTick(game);
        require(probe.ticks == 40, "one shared callback runs every game tick without Fabric lifecycle events");
        gui.current = new Screen(Component.literal("Input fixture")) {};
        LateBridge.onKeyPress(new KeyEvent(65, 0, 0), GLFW.GLFW_PRESS);
        gui.current = null; game.focused = false;
        LateBridge.onKeyPress(new KeyEvent(65, 0, 0), GLFW.GLFW_PRESS); game.focused = true;
    }

    private static void crossbow() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        AutoXbow module = new AutoXbow(); module.setEnabled(true); bind(module, "Fire", 82); manager.getModules().add(module);
        player.inventory.setItem(0, new ItemStack(Items.RAIL)); player.inventory.setItem(1, new ItemStack(Items.TNT_MINECART));
        player.inventory.setItem(2, new ItemStack(Items.FLINT_AND_STEEL)); player.inventory.setItem(3, new ItemStack(Items.CROSSBOW));
        BlockPos support = new BlockPos(0, 64, 2); world.blocks.put(support, Blocks.STONE.defaultBlockState());
        game.hitResult = new BlockHitResult(new Vec3(.5, 65, 2.5), Direction.UP, support, false);
        LateBridge.onKeyPress(new KeyEvent(82, 0, 0), GLFW.GLFW_PRESS);
        require(field(AutoXbow.class, "state").get(module).toString().equals("CHARGE"), "Fire arms enabled AutoXbow");
        tick(manager); tick(manager);
        require(USES.stream().anyMatch(u -> u.item.equals("minecraft:crossbow")), "armed crossbow starts charging from tick delivery");
        player.useTicks=100;
        tick(manager);
        require(field(AutoXbow.class,"loadingReleased").getBoolean(module), "unloaded crossbow releases before acknowledgment");
        for(int i=0;i<12;i++)tick(manager);
        require(field(AutoXbow.class,"state").get(module).toString().equals("IDLE") && world.getBlockState(support.above()).isAir()
                && USES.stream().allMatch(u->u.item.equals("minecraft:crossbow")), "failed charge acknowledgment aborts without rail/cart/fire");
        module.setEnabled(false); module.setEnabled(true); player.ammo=false;
        require(module.trigger(),"no-ammo cycle can validate the kit");tick(manager);tick(manager);
        require(field(AutoXbow.class,"state").get(module).toString().equals("IDLE"),"no ammo stops before placement");
        player.ammo=true; module.setEnabled(false);module.setEnabled(true);require(module.trigger(),"manual-scroll fixture arms");
        player.inventory.setSelectedSlot(7);tick(manager);
        require(field(AutoXbow.class,"state").get(module).toString().equals("IDLE")&&player.inventory.getSelectedSlot()==7,"manual scroll cancels without overriding selection");
        module.onInputSuspended(); require(field(AutoXbow.class, "state").get(module).toString().equals("IDLE"), "screen/focus suspension cancels cycle");
    }

    private static void bucketRepairs() throws Exception {
        setup();world.realClip=true;player.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET));
        BlockPos cell=new BlockPos(0,64,1);
        world.blocks.put(cell.relative(Direction.SOUTH),Blocks.STONE.defaultBlockState());
        require(com.baggute.cheatclient.util.BucketActions.use(game,cell,false),"bucket finds adjacent side face without floor support");
        int used=USES.size();Stealth.reset();world.blocks.clear();
        require(!com.baggute.cheatclient.util.BucketActions.use(game,cell,false)&&USES.size()==used,"unsupported bucket target sends no item use");
        player.inventory.setItem(0,new ItemStack(Items.BUCKET));world.blocks.put(cell,Blocks.WATER.defaultBlockState());Stealth.reset();
        require(com.baggute.cheatclient.util.BucketActions.use(game,cell,true),"empty bucket picks up visible source fluid");
        world.blocks.put(cell,Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,1));Stealth.reset();
        require(!com.baggute.cheatclient.util.BucketActions.use(game,cell,true),"flowing water cannot arm source pickup");
        setup();world.realClip=true;ModuleManager manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var web=new com.baggute.cheatclient.module.combat.WebDrain();manager.getModules().add(web);web.setEnabled(true);
        player.inventory.setItem(0,new ItemStack(Items.DIAMOND_SWORD));player.inventory.setItem(1,new ItemStack(Items.WATER_BUCKET));
        BlockPos webPos=player.blockPosition();world.blocks.put(webPos,Blocks.COBWEB.defaultBlockState());world.blocks.put(webPos.below(),Blocks.STONE.defaultBlockState());
        for(int i=0;i<5&&USES.isEmpty();i++)tick(manager);
        require(!USES.isEmpty()&&player.inventory.getSelectedSlot()==1,"WebDrain detects intersecting cobweb and pours after equip");
        BlockPos poured=(BlockPos)field(web.getClass(),"pouredCell").get(web);require(poured!=null,"WebDrain retains source for pickup");
        world.blocks.remove(webPos);world.blocks.put(poured,Blocks.WATER.defaultBlockState());player.inventory.setItem(1,new ItemStack(Items.BUCKET));
        for(int i=0;i<4;i++)tick(manager);
        require(USES.size()==2&&player.inventory.getSelectedSlot()==0,"WebDrain retrieves source and restores sword");web.setEnabled(false);
        setup();world.realClip=true;manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var drain=new com.baggute.cheatclient.module.apex.ApexModule("AutoDrain","fixture");manager.getModules().add(drain);drain.setEnabled(true);player.inventory.setItem(0,new ItemStack(Items.DIAMOND_SWORD));player.inventory.setItem(1,new ItemStack(Items.BUCKET));
        world.blocks.put(cell,Blocks.WATER.defaultBlockState());
        for(int i=0;i<12;i++){tick(manager);if(!USES.isEmpty())world.blocks.clear();}
        require(USES.size()==1&&USES.getFirst().item.equals("minecraft:bucket")&&player.inventory.getSelectedSlot()==0,"AutoDrain finds nearby source, scoops once and restores slot");
        drain.setEnabled(false);manager.closeCustomModules();
        System.out.println("PASS: bucket side-face placement, unsupported ray rejection, source pickup, WebDrain pour/retrieve/restore and AutoDrain ownership");
    }

    private static void lunge() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        LungeSwap module = new LungeSwap(); bind(module, "Lunge", 89); number(module, "minLunge", 3); module.setEnabled(true); manager.getModules().add(module);
        var registry = MinecraftBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
        var enchantment = registry.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("lunge")));
        ItemStack spear = new ItemStack(Items.DIAMOND_SPEAR); spear.enchant(enchantment, 3); player.inventory.setItem(1, spear);
        LateBridge.onKeyPress(new KeyEvent(89, 0, 0), GLFW.GLFW_PRESS);
        require(field(LungeSwap.class, "pending").getBoolean(module), "Lunge key arms with genuine Lunge III item");
        tick(manager); require(mode.stabs == 0 && player.inventory.getSelectedSlot() == 0, "charge is acquired with fast-hand attributes");
        tick(manager); require(mode.stabs == 1 && player.inventory.getSelectedSlot() == 1, "ready fast hand swaps and stabs before the spear cooldown takes over");
        tick(manager); require(player.inventory.getSelectedSlot() == 0, "fast hand restored");
        ItemStack weaker = new ItemStack(Items.DIAMOND_SPEAR); weaker.enchant(enchantment, 2); player.inventory.setItem(1, weaker);
        require(!module.arm(game), "saved Lunge III minimum rejects Lunge II");
        player.inventory.setItem(1, spear); require(module.arm(game), "new cycle can arm");
        player.inventory.setSelectedSlot(7); tick(manager); require(player.inventory.getSelectedSlot() == 7, "manual scrolling is retained");
    }

    private static void pearlSpeed(boolean offhand, int pearlSlot) throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        AutoPearlCatch module = new AutoPearlCatch(); bind(module, "PearlCatch", 83); module.setEnabled(true); manager.getModules().add(module);
        player.inventory.setItem(pearlSlot, new ItemStack(Items.ENDER_PEARL));
        if (offhand) player.offhand = new ItemStack(Items.WIND_CHARGE); else player.inventory.setItem(1, new ItemStack(Items.WIND_CHARGE));
        player.setYRot(0); player.setXRot(-45);
        LateBridge.onKeyPress(new KeyEvent(83, 0, 0), GLFW.GLFW_PRESS);
        require(USES.size() == 1 && USES.getFirst().tick == 0, "hotkey throws immediately, including a different pearl slot");
        player.setYRot(90); player.setXRot(0);
        for (int i = 0; i < 5; i++) tick(manager);
        Use pearl = USES.stream().filter(u -> u.item.equals("minecraft:ender_pearl")).findFirst().orElseThrow();
        Use wind = USES.stream().filter(u -> u.item.equals("minecraft:wind_charge")).findFirst().orElseThrow();
        require(wind.tick - pearl.tick == 2, "two ticks pearl-to-charge, including a hotbar swap");
        require(Math.abs(wind.yaw) < 1 && wind.pitch < 0 && hitsProjectedPearl(pearl, wind),
                "charge intercepts original pearl after player turns: " + wind);
        require(player.getYRot() == 90 && player.getXRot() == 0, "directed use retains visible view");
        require(player.inventory.getSelectedSlot() == 0, "slot returns without extra aim phases");
        require(USES.size() == 2, "one pearl and one wind charge per key press");
    }

    private static void manualPearlSpeed(boolean offhand, boolean consumeLast) throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        AutoPearlCatch module = new AutoPearlCatch(); module.setEnabled(true); manager.getModules().add(module);
        player.inventory.setItem(0, new ItemStack(Items.ENDER_PEARL));
        if (offhand) player.offhand = new ItemStack(Items.WIND_CHARGE); else player.inventory.setItem(2, new ItemStack(Items.WIND_CHARGE));
        player.setYRot(0); player.setXRot(-45); mode.consumePearl = consumeLast;
        require(mode.useItem(player, InteractionHand.MAIN_HAND).consumesAction(), "manual pearl accepted by vanilla result");
        if (consumeLast) require(player.getMainHandItem().isEmpty(), "last pearl consumed before success callback");
        player.setYRot(120); player.setXRot(30);
        tick(manager);
        require(USES.size() == 1, "manual pearl waits through the first tick");
        tick(manager);
        require(world.entities.isEmpty() && USES.size() == 2 && USES.getLast().tick == 2
                && USES.getLast().item.equals("minecraft:wind_charge"), "manual throw fires after two ticks before server entity arrives");
        require(hitsProjectedPearl(USES.getFirst(), USES.getLast()), "manual original angle produces an intercept after camera turns");
        require(player.getYRot() == 120 && player.getXRot() == 30, "manual catch preserves camera");
        for (int i = 0; i < 6; i++) tick(manager);
        world.entities.add(pearl(player, new Vec3(0, 68, 7), new Vec3(0, .5, 1.2)));
        tick(manager); tick(manager); require(USES.size() == 2, "delayed server pearl never triggers a second charge");
        module.setEnabled(false);
    }

    private static void rejectedPearls() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        AutoPearlCatch module = new AutoPearlCatch(); module.setEnabled(true); manager.getModules().add(module);
        player.inventory.setItem(0, new ItemStack(Items.ENDER_PEARL)); player.offhand = new ItemStack(Items.WIND_CHARGE);
        mode.itemResult = InteractionResult.FAIL; mode.useItem(player, InteractionHand.MAIN_HAND);
        tick(manager); require(USES.size() == 1, "failed local pearl use cannot trigger a charge");
        mode.itemResult = InteractionResult.SUCCESS; player.cooldowns.addCooldown(player.getMainHandItem(), 20);
        require(LateBridge.captureItemUse(player, InteractionHand.MAIN_HAND) == null, "cooldown cannot arm a predicted pearl");
        player.cooldowns = new ItemCooldowns(); number(module, "pearlDelay", 3);
        mode.useItem(player, InteractionHand.MAIN_HAND); tick(manager); tick(manager);
        require(USES.stream().noneMatch(u -> u.item.equals("minecraft:wind_charge")), "explicit pearl delay is retained");
        tick(manager); require(USES.getLast().item.equals("minecraft:wind_charge"), "explicit pearl delay expires on its intended tick");
        module.setEnabled(false);
    }

    private static void atomicItemUse() throws Exception {
        setup(); player.inventory.setItem(3, new ItemStack(Items.ENDER_PEARL)); player.inventory.setItem(4, new ItemStack(Items.WIND_CHARGE));
        Stealth.beginTick(); Stealth.markRotation();
        require(!Stealth.useItemSelected(game, InteractionHand.MAIN_HAND, 3, "ender_pearl", 0, -45).consumesAction()
                && player.inventory.getSelectedSlot() == 0, "atomic item use respects an earlier rotation");
        Stealth.reset(); Stealth.beginTick(); player.cooldowns.addCooldown(player.inventory.getItem(3), 20);
        require(!Stealth.useItemSelected(game, InteractionHand.MAIN_HAND, 3, "ender_pearl", 0, -45).consumesAction()
                && player.inventory.getSelectedSlot() == 0, "cooldown checked before atomic selection");
        player.cooldowns = new ItemCooldowns();
        require(Stealth.useItemSelected(game, InteractionHand.MAIN_HAND, 3, "ender_pearl", 0, -45).consumesAction(), "one atomic selected use succeeds");
        require(!Stealth.useItemSelected(game, InteractionHand.MAIN_HAND, 4, "wind_charge", 0, -45).consumesAction()
                && USES.size() == 1 && player.inventory.getSelectedSlot() == 3, "shared interaction budget blocks a second use and swap");
        Stealth.beginTick(); require(Stealth.useItemSelected(game, InteractionHand.MAIN_HAND, 4, "wind_charge", 0, -45).consumesAction(), "next tick can select and use charge");
        Link link = (Link) player.connection;
        require(link.packets.size() == 2 && link.packets.getFirst() instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket,
                "exactly one carried-item packet per changed selection");
    }

    private static void timingAndPriority() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        var timing = new com.baggute.cheatclient.module.misc.ActionTiming(); manager.getModules().add(timing);
        number(timing, "minDelayMs", 25); number(timing, "maxDelayMs", 25);
        Probe probe = new Probe("Timed", -1, -1); probe.setEnabled(true);
        Field clock = field(com.baggute.cheatclient.module.misc.ActionTiming.class, "clock"); Object previous = clock.get(null);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        clock.set(null, (java.util.function.LongSupplier) now::get);
        try {
            com.baggute.cheatclient.module.misc.ActionTiming.run(probe, () -> {
                require(!Stealth.canInteract(game), "timing begins a bounded nonblocking wait");
                now.addAndGet(24_000_000L); require(!Stealth.canInteract(game), "blocked retries retain their deadline");
                now.addAndGet(1_000_000L); require(Stealth.canInteract(game), "fixed deadline becomes ready without resampling");
                Stealth.markInteraction();
            });
            Stealth.beginTick();
            com.baggute.cheatclient.module.misc.ActionTiming.run(probe, () -> require(!Stealth.canInteract(game), "next action samples a new delay"));
            try { com.baggute.cheatclient.module.misc.ActionTiming.run(probe, () -> { throw new IllegalStateException("scope fixture"); }); }
            catch (IllegalStateException expected) { }
            require(com.baggute.cheatclient.module.misc.ActionTiming.ready(), "failed callback cannot leak timing into input or rendering");
            AutoPearlCatch catcher = new AutoPearlCatch(); catcher.setEnabled(true);
            manager.getModules().add(new Module("BudgetCompetitor", "Fixture", Category.COMBAT) {
                { setEnabled(true); }
                @Override public void onTick() { if (game.ticks >= 2) Stealth.useItem(game, InteractionHand.OFF_HAND); }
            });
            manager.getModules().add(catcher);
            player.inventory.setItem(0, new ItemStack(Items.ENDER_PEARL)); player.inventory.setItem(1, new ItemStack(Items.WIND_CHARGE));
            player.setXRot(-45); mode.useItem(player, InteractionHand.MAIN_HAND); tick(manager); tick(manager);
            require(USES.size() == 2 && USES.getLast().item.equals("minecraft:wind_charge") && USES.getLast().tick == 2,
                    "pearl catch waits two ticks and wins the firing tick's shared budget");
            catcher.setEnabled(false); timing.setEnabled(false);
        } finally { clock.set(null, previous); com.baggute.cheatclient.module.misc.ActionTiming.reset(); }
    }

    private static void quietFeedback() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        for (Module module : List.of(new AutoXbow(), new LungeSwap(), new AutoInstacart(),
                new com.baggute.cheatclient.module.render.AntiVanish(), new com.baggute.cheatclient.module.misc.GamemodeDetector(),
                new com.baggute.cheatclient.module.automation.Baritone(), new com.baggute.cheatclient.module.misc.SeedCrackerX())) {
            manager.getModules().add(module);
            var feedback = (BoolSetting) module.getSettings().stream().filter(s -> s.getName().equals("sendChatFeedback")).findFirst().orElseThrow();
            require(!feedback.get() && feedback.getDisplayName().equals("Send Chat Feedback"), "visible opt-in feedback default for " + module.getName());
        }
        var config = new com.baggute.cheatclient.config.ConfigManager(manager);
        require(config.applySnapshot(com.google.gson.JsonParser.parseString("{\"modules\":{\"AutoXbow\":{\"settings\":{\"chatFeedback\":true}}}}").getAsJsonObject()),
                "legacy feedback key remains readable");
        require(!com.baggute.cheatclient.util.FeedbackPolicy.enabled("AutoXbow"), "legacy true cannot restore automatic chat feedback");
        LungeSwap lunge = (LungeSwap) manager.getByName("LungeSwap"); lunge.setEnabled(true);
        require(!lunge.arm(game) && gui.messages.isEmpty(), "missing spear does not write to chat by default");
        ((BoolSetting) lunge.getSettings().stream().filter(s -> s.getName().equals("sendChatFeedback")).findFirst().orElseThrow()).set(true);
        require(!lunge.arm(game) && gui.messages.size() == 1, "explicit feedback option displays missing-item feedback");
        gui.messages.clear(); kaptainwutax.seedcrackerX.util.Log.debug("automatic fixture");
        require(gui.messages.isEmpty(), "embedded seed logger is quiet by default");
        lunge.setEnabled(false);
    }

    private static void missControls() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        LungeSwap lunge = new LungeSwap(); lunge.setEnabled(true); manager.getModules().add(lunge);
        var registry = MinecraftBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
        var enchantment = registry.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace("lunge")));
        ItemStack spear = new ItemStack(Items.DIAMOND_SPEAR); spear.enchant(enchantment, 3); player.inventory.setItem(1, spear);
        DoubleSetting miss = (DoubleSetting) lunge.getSettings().stream().filter(s -> s.getName().equals("missChance")).findFirst().orElseThrow();
        require(miss.get() == 0, "LungeSwap default preserves every normal stab");
        miss.set(100); require(lunge.arm(game), "100 percent miss cycle arms"); miss.set(0);
        tick(manager); tick(manager); require(mode.stabs == 0 && player.inventory.getSelectedSlot() == 1, "miss decision is retained and cannot find a victim through a null target");
        tick(manager); tick(manager); require(player.inventory.getSelectedSlot() == 0, "intentional empty swing still returns to the fast hand");
        require(lunge.arm(game), "next normal cycle arms"); tick(manager); tick(manager); tick(manager); require(mode.stabs == 1, "zero percent sends the Lunge stab");
        tick(manager); lunge.setEnabled(false);
        AutoXbow xbow = new AutoXbow();
        DoubleSetting xmiss = (DoubleSetting) xbow.getSettings().stream().filter(s -> s.getName().equals("missChance")).findFirst().orElseThrow();
        require(xbow.getSettings().stream().filter(s -> s.getName().equals("missChance")).count() == 1 && xmiss.get() == 0, "one existing crossbow miss slider, default zero");
        var plan = AutoXbow.class.getDeclaredMethod("planShot", Vec3.class, Vec3.class); plan.setAccessible(true);
        Vec3 origin = player.getEyePosition(), target = new Vec3(0, 64.5, 3); xmiss.set(100);
        Object first = plan.invoke(xbow, origin, target); xmiss.set(0); Object retried = plan.invoke(xbow, origin, target);
        require(first.equals(retried), "crossbow retries keep the same miss and aim offsets");
        var yaw = first.getClass().getDeclaredMethod("yaw"); var pitch = first.getClass().getDeclaredMethod("pitch"); yaw.setAccessible(true); pitch.setAccessible(true);
        Vec3 shot = com.baggute.cheatclient.util.WindChargeIntercept.look((float) yaw.invoke(first), (float) pitch.invoke(first));
        require(new AABB(-.6, 64, 2.4, .6, 65, 3.6).inflate(.3).clip(origin, origin.add(shot.scale(10))).isEmpty(), "100 percent crossbow miss points away from the cart hitbox");
        System.out.println("PASS: two-tick manual/hotkey pearl catch before server spawn; priority, cooldown and duplicate guards; bounded timing; quiet default feedback; persistent 0/100 percent miss decisions");
    }

    private static boolean hitsProjectedPearl(Use pearl, Use wind) {
        Vec3 position = player.getEyePosition().add(0, .025, 0), velocity = direction(pearl).scale(1.5);
        for (int age = 0; age < wind.tick - pearl.tick; age++) {
            velocity = velocity.add(0, -.03, 0).scale(.99f); position = position.add(velocity);
        }
        Vec3 charge = player.getEyePosition(), flight = direction(wind).scale(1.5);
        for (int tick = 0; tick < 80; tick++) {
            velocity = velocity.add(0, -.03, 0).scale(.99f);
            Vec3 relative = position.subtract(charge), change = velocity.subtract(flight);
            double closest = change.lengthSqr() < 1e-12 ? 0 : Math.max(0, Math.min(1, -relative.dot(change) / change.lengthSqr()));
            if (relative.add(change.scale(closest)).length() <= .200001) return true;
            position = position.add(velocity); charge = charge.add(flight);
        }
        return false;
    }
    private static Vec3 direction(Use use) {
        double yaw = Math.toRadians(use.yaw), pitch = Math.toRadians(use.pitch);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    private static void livePearls() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        AutoPearlCatch module = new AutoPearlCatch(); module.setEnabled(true); manager.getModules().add(module);
        player.offhand = new ItemStack(Items.WIND_CHARGE); player.setYRot(90); player.setXRot(0);
        Pearl existing = pearl(player, new Vec3(0, 67, 3), new Vec3(0, .7, 1.2)); world.entities.add(existing);
        tick(manager); require(USES.isEmpty(), "enabling does not target an existing pearl");
        world.entities.add(pearl(allocate(Hand.class), new Vec3(2, 67, 0), new Vec3(1.2, .7, 0)));
        tick(manager); require(USES.isEmpty(), "foreign pearl never arms auto catch");
        world.entities.add(pearl(player, new Vec3(0, 67, 2), new Vec3(0, .7, 1.2)));
        tick(manager);
        require(USES.isEmpty(),"fresh observed pearl retains the longer wait");tick(manager);
        require(USES.size() == 1 && USES.getFirst().item.equals("minecraft:wind_charge")
                && Math.abs(USES.getFirst().yaw) < 1 && USES.getFirst().pitch < 0, "fresh own pearl supplies live angle to charge");
        require(player.getYRot() == 90 && player.getXRot() == 0, "live tracking retains visible aim");
        tick(manager); tick(manager); require(USES.size() == 1, "one answer to each observed pearl");
        game.focused = false; module.onInputSuspended();
        world.entities.add(pearl(player, new Vec3(0, 67, 2), new Vec3(0, .7, 1.2))); tick(manager);
        require(USES.size() == 1, "focus loss cannot fire a newly observed pearl");
        game.focused = true; tick(manager); require(USES.size() == 1, "focus regain primes existing entities without stale activation");
        module.setEnabled(false);
    }

    private static Pearl pearl(Entity owner, Vec3 position, Vec3 velocity) throws Exception {
        Pearl pearl = allocate(Pearl.class); pearl.thrower = owner; pearl.motion = velocity; pearl.tickCount = 1;
        pearl.setUUID(UUID.randomUUID()); field(Entity.class, "position").set(pearl, position);
        pearl.setBoundingBox(new AABB(position.x - .125, position.y - .125, position.z - .125,
                position.x + .125, position.y + .125, position.z + .125));
        return pearl;
    }

    private static void apexSequences() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        ApexModule catchModule = new ApexModule("ChompzaInstaCatch", "Fixture"); manager.getModules().add(catchModule); catchModule.setEnabled(true);
        player.inventory.setItem(1, new ItemStack(Items.ENDER_PEARL)); player.inventory.setItem(2, new ItemStack(Items.WIND_CHARGE)); player.setXRot(85);
        for (int i = 0; i < 9; i++) tick(manager);
        require(USES.stream().anyMatch(u -> u.item.equals("minecraft:ender_pearl"))
                && USES.stream().anyMatch(u -> u.item.equals("minecraft:wind_charge")), "fixed-period catch advances despite separate-slot equip ticks");
        catchModule.setEnabled(false); tick(manager); require(player.inventory.getSelectedSlot() == 0, "Apex catch restores owned slot");
        setup(); manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        ApexModule spearMace = new ApexModule("SpearMaceDtap", "Fixture"); manager.getModules().add(spearMace); spearMace.setEnabled(true);
        Hand victim = allocate(Hand.class); victim.setUUID(UUID.randomUUID()); field(Entity.class, "position").set(victim, new Vec3(0, 64, 2));
        field(Entity.class, "dimensions").set(victim, EntityDimensions.scalable(.6f, 1.8f)); field(Entity.class, "id").setInt(victim, 11);
        victim.setBoundingBox(new AABB(-.3, 64, 1.7, .3, 65.8, 2.3)); world.entities.add(victim);
        player.setYRot(0); player.setXRot((float) -Math.toDegrees(Math.atan2(1.8f * .6 - 1.62f, 2)));
        player.inventory.setItem(1, new ItemStack(Items.DIAMOND_SPEAR)); player.inventory.setItem(2, new ItemStack(Items.MACE));
        for (int i = 0; i < 9; i++) tick(manager);
        require(mode.stabs > 0 && mode.attacks > 0, "spear/mace phase survives separate-slot equipment deferrals");
        spearMace.setEnabled(false); tick(manager); require(player.inventory.getSelectedSlot() == 0, "spear/mace original hand restored");
    }

    @SuppressWarnings("unchecked")
    private static void delegates() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        Probe existing = new Probe("ESP", -1, -1); existing.setEnabled(true); manager.getModules().add(existing);
        ApexModule item = new ApexModule("ItemESP", "Fixture"), mob = new ApexModule("MobESP", "Fixture");
        manager.getModules().add(item); manager.getModules().add(mob); item.setEnabled(true); mob.setEnabled(true); tick(manager);
        item.setEnabled(false); mob.setEnabled(false); require(existing.isEnabled(), "delegate already enabled by player survives alias cleanup");
        existing.setEnabled(false); item.setEnabled(true); mob.setEnabled(true); tick(manager);
        item.setEnabled(false); require(existing.isEnabled(), "second owner keeps delegate active");
        existing.failDisable = true; mob.setEnabled(false); require(!existing.isEnabled(), "failed delegate disable still rolls back");
        require(((Map<?, ?>) field(ApexImpl.class, "DELEGATES").get(null)).isEmpty(), "failed cleanup cannot retain delegate ownership");
    }

    private static void combatRepairs() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        var options = allocate(net.minecraft.client.Options.class); field(Minecraft.class, "options").set(game, options);
        var attack = allocate(net.minecraft.client.KeyMapping.class); attack.setDown(true); field(net.minecraft.client.Options.class, "keyAttack").set(options, attack);
        var forward = allocate(net.minecraft.client.KeyMapping.class); forward.setDown(true); field(net.minecraft.client.Options.class, "keyUp").set(options, forward);
        var jump = allocate(net.minecraft.client.KeyMapping.class); field(net.minecraft.client.Options.class, "keyJump").set(options, jump);
        Hand victim = allocate(Hand.class); victim.setUUID(UUID.randomUUID());
        field(Entity.class, "position").set(victim, new Vec3(2, 64, 1));
        field(Entity.class, "dimensions").set(victim, EntityDimensions.scalable(.6f, 1.8f)); field(Entity.class, "id").setInt(victim, 11);
        victim.setBoundingBox(new AABB(1.7,64,.7,2.3,65.8,1.3)); victim.motion = Vec3.ZERO; world.entities.add(victim);
        player.setYRot(120); player.setXRot(25);
        AutoMace mace = new AutoMace(); manager.getModules().add(mace); mace.setEnabled(true);
        player.inventory.setItem(1, new ItemStack(Items.MACE));
        tick(manager); require(player.inventory.getSelectedSlot()==1 && mode.attacks==0, "AutoMace equips before attacking");
        tick(manager); require(mode.attacks==1 && mode.attackSlots.equals(List.of(1)), "AutoMace sends a real mace hit without an aim convergence wait");
        require(player.getYRot()==120 && player.getXRot()==25, "AutoMace silent aim never changes camera");
        Link link = (Link)player.connection;
        require(link.packets.stream().filter(p->p instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot).count()==2
                && mode.packetsAtAttack==2, "directed rotation precedes attack and restore follows");
        tick(manager); tick(manager); tick(manager); require(player.inventory.getSelectedSlot()==0, "AutoMace returns original hand");
        mace.setEnabled(false); manager.getModules().clear(); Stealth.reset();
        AutoBreachSwap breach = new AutoBreachSwap(); manager.getModules().add(breach); breach.setEnabled(true);
        ItemStack breached = new ItemStack(Items.MACE);
        var enchantments = MinecraftBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
        breached.enchant(enchantments.getOrThrow(Enchantments.BREACH), 4); player.inventory.setItem(2, breached); player.inventory.setItem(1, ItemStack.EMPTY);
        game.hitResult = new EntityHitResult(victim); player.charge = 0;
        tick(manager); require(player.inventory.getSelectedSlot()==2, "held click equips breach mace even after vanilla depleted charge");
        tick(manager); require(mode.attacks==2, "held breach cycle hits with selected mace");
        tick(manager); tick(manager); require(player.inventory.getSelectedSlot()==0, "breach swap restores hand");
        attack.setDown(false); tick(manager); require(player.inventory.getSelectedSlot()==0, "breach click requirement respected");
        breach.setEnabled(false); manager.getModules().clear(); attack.setDown(true); player.charge=1; Stealth.reset();
        AutoStunSlam stun = new AutoStunSlam(); manager.getModules().add(stun); stun.setEnabled(true);
        victim.using = new ItemStack(Items.SHIELD); player.inventory.setItem(1,new ItemStack(Items.DIAMOND_AXE));
        for(int i=0;i<7;i++) tick(manager);
        require(mode.attacks==4 && mode.attackSlots.subList(2,4).equals(List.of(1,2)), "stun slam really attacks with axe then mace");
        require(player.getYRot()==120 && player.getXRot()==25 && player.inventory.getSelectedSlot()==0, "both stun hits preserve view and restore hand");
        stun.setEnabled(false); manager.getModules().clear(); Stealth.reset();
        AntiMiss anti = new AntiMiss(); manager.getModules().add(anti); anti.setEnabled(true);
        game.hitResult = BlockHitResult.miss(new Vec3(0,64,1),Direction.UP,new BlockPos(0,64,1));
        require(GameplayHooks.blockClick(game) && attack.isDown(), "AntiMiss blocks miss without clearing held click");
        game.hitResult = new BlockHitResult(new Vec3(0,64,1),Direction.UP,new BlockPos(0,64,1),false);
        require(GameplayHooks.blockClick(game), "AntiMiss blocks block clicks");
        game.hitResult = new EntityHitResult(victim); require(!GameplayHooks.blockClick(game), "AntiMiss immediately allows a living crosshair entity");
        anti.setEnabled(false); manager.getModules().clear();
        Reach reach = new Reach(); manager.getModules().add(reach); reach.setEnabled(true);
        require(Reach.effectiveRange(3,player)==3.5 && Reach.effectiveRange(3,victim)==3, "Reach extends local player targeting only");
        attack.setDown(false); require(Reach.effectiveRange(3,player)==3, "Reach click gate respects release"); attack.setDown(true);
        reach.setEnabled(false); manager.getModules().clear();
        AutoClicker clicker = new AutoClicker(); manager.getModules().add(clicker); clicker.setEnabled(true); player.charge=0;
        game.hitResult = new EntityHitResult(victim); tick(manager); require(mode.attacks==5, "clicker sends attacks against a raised shield at its click rate");
        clicker.setEnabled(false); manager.getModules().clear();
        BlockPos block = new BlockPos(0,64,1);
        var actual = new BlockHitResult(new Vec3(0,64,1),Direction.UP,block,false);
        require(!AutoClicker.validMiningHit(game, actual), "air block rejected");
        world.blocks.put(block, Blocks.WATER.defaultBlockState()); require(!AutoClicker.validMiningHit(game,actual), "liquid block rejected");
        world.blocks.put(block,Blocks.STONE.defaultBlockState()); require(AutoClicker.validMiningHit(game,actual)
                && !AutoClicker.validMiningHit(game,BlockHitResult.miss(new Vec3(0,64,1),Direction.UP,block)), "solid block accepted, miss subclass rejected");
        AutoJumpReset reset = new AutoJumpReset(); manager.getModules().add(reset); reset.setEnabled(true);
        player.health=20; player.motion=Vec3.ZERO; GameplayHooks.beforeTick(game); tick(manager);
        player.health=18; player.motion=new Vec3(.2,.1,0); GameplayHooks.beforeTick(game);
        require(player.jumps==1 && !jump.isDown(), "jump reset uses vanilla jump before input without modifying physical binding");
        tick(manager); require(player.jumps==1, "pre/tail callbacks do not repeat jump"); reset.setEnabled(false); manager.getModules().clear();
        player.charge=1; SmartWTap smart = new SmartWTap(); manager.getModules().add(smart); smart.setEnabled(true);
        Stealth.setSprinting(game,true); int packetStart=link.packets.size(); mode.attack(player,victim);
        require(!player.sprint && link.packets.size()==packetStart+1, "SmartWTap resets sprint on actual hit");
        tick(manager); require(!player.sprint, "SmartWTap stays stopped across an input pass");
        tick(manager); require(player.sprint, "SmartWTap resumes sprint while moving forward"); smart.setEnabled(false); manager.getModules().clear();
        BowAimbot bow = new BowAimbot(); manager.getModules().add(bow); bow.setEnabled(true);
        ((BoolSetting)bow.getSettings().stream().filter(s->s.getName().equals("silent")).findFirst().orElseThrow()).set(true);
        player.using=new ItemStack(Items.BOW); player.inventory.setItem(0,new ItemStack(Items.BOW)); victim.motion=new Vec3(.2,0,0);
        float yaw=player.getYRot(),pitch=player.getXRot(); Object token=GameplayHooks.beginBowRelease(player);
        require(token!=null && player.getYRot()==yaw && player.getXRot()==pitch, "silent bow release aims server only");
        GameplayHooks.endBowRelease(player,token); require(player.getYRot()==yaw && player.getXRot()==pitch, "silent bow restore never moves view");
        Vec3 lead=bow.predictAim(new Vec3(0,65.62,0),new Vec3(0,65,25),new Vec3(.2,0,0),3);
        require(lead.x < -2 && lead.z > 8 && Double.isFinite(lead.y), "moving bow target receives flight-time lead and drop");
        for(int fps:new int[]{30,60,144}) {
            float error=20; for(int i=0;i<fps;i++) error-=Aimbot.smoothStep(error,.5,1.0/fps);
            require(Math.abs(error)<.001, "aim converges on center at " + fps + " FPS");
        }
        bow.setEnabled(false);manager.getModules().clear();player.using=null;player.charge=1;Stealth.reset();
        Triggerbot trigger=new Triggerbot();manager.getModules().add(trigger);number(trigger,"tickDelayMin",0);number(trigger,"tickDelayMax",0);number(trigger,"cpsCap",0);
        ((BoolSetting)trigger.getSettings().stream().filter(s->s.getName().equals("wtap")).findFirst().orElseThrow()).set(true);
        trigger.setEnabled(true);player.inventory.setItem(0,new ItemStack(Items.DIAMOND_SWORD));game.hitResult=new EntityHitResult(victim);
        Stealth.setSprinting(game,true);int attacksBefore=mode.attacks;tick(manager);
        require(mode.attacks==attacksBefore+1&&!player.sprint,"Triggerbot hits before resetting sprint");
        tick(manager);require(player.sprint,"Triggerbot restores after one tick instead of three");
        int packetsBefore=link.packets.size();Stealth.markRotation();
        var doAttack=Triggerbot.class.getDeclaredMethod("doAttack",Minecraft.class,Entity.class);doAttack.setAccessible(true);doAttack.invoke(trigger,game,victim);
        require(player.sprint&&link.packets.size()==packetsBefore,"failed Triggerbot hit cannot interrupt sprint");
        trigger.setEnabled(false);manager.getModules().clear();Stealth.reset();
        var menu=allocate(net.minecraft.world.inventory.InventoryMenu.class);menu.setCarried(ItemStack.EMPTY);
        var slots=net.minecraft.core.NonNullList.<net.minecraft.world.inventory.Slot>create();var contents=new net.minecraft.world.SimpleContainer(46);
        for(int i=0;i<46;i++)slots.add(new net.minecraft.world.inventory.Slot(contents,i,0,0));
        field(net.minecraft.world.inventory.AbstractContainerMenu.class,"slots").set(menu,slots);
        field(Player.class,"inventoryMenu").set(player,menu);player.containerMenu=menu;player.offhand=ItemStack.EMPTY;
        player.inventory.setItem(1,new ItemStack(Items.TOTEM_OF_UNDYING));
        var totem=new com.baggute.cheatclient.module.player.AutoTotem();manager.getModules().add(totem);totem.setEnabled(true);tick(manager);
        require(mode.inventoryClicks==1&&mode.lastButton==40&&mode.lastSlot==37&&mode.lastInput==net.minecraft.world.inventory.ContainerInput.SWAP
                &&menu.getCarried().isEmpty(),"AutoTotem uses one direct offhand swap without a cursor exchange");
        totem.setEnabled(false);manager.getModules().clear();
        var detector=new com.baggute.cheatclient.module.misc.GamemodeDetector();manager.getModules().add(detector);
        detector.setEnabled(true);
        mode.playerMode=GameType.CREATIVE;tick(manager);
        require(com.baggute.cheatclient.module.misc.GamemodeDetector.currentAlert()==null,"own gamemode changes are ignored");
        var info=allocate(PlayerInfo.class);
        field(PlayerInfo.class,"profile").set(info,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"OtherPlayer"));
        field(PlayerInfo.class,"gameMode").set(info,GameType.SURVIVAL);
        ((Link)player.connection).online=List.of(info);tick(manager);
        require(com.baggute.cheatclient.module.misc.GamemodeDetector.currentAlert()==null,"first sighting seeds the other player's mode");
        field(PlayerInfo.class,"gameMode").set(info,GameType.CREATIVE);tick(manager);
        require(com.baggute.cheatclient.module.misc.GamemodeDetector.currentAlert().contains("OtherPlayer"),"other player's mode change is reported");
        detector.setEnabled(false);manager.getModules().clear();
        require(com.baggute.cheatclient.util.LookupCommand.operatorStatus(game,victim).startsWith("unknown"),"remote operator status cannot become a false negative");
        var zoom=new com.baggute.cheatclient.module.player.Zoom();manager.getModules().add(zoom);zoom.setEnabled(true);
        require(zoom.applyFov(70)<70&&zoom.onScroll(.5),"zoom camera and fractional scroll work without persistent FOV writes");
        for(int i=0;i<100;i++)require(zoom.applyFov(70)>0,"finite bounded zoom");
        zoom.setEnabled(false);require(com.baggute.cheatclient.module.player.Zoom.smooth(.3,1,1,.1)>.99,"zoom restores smoothly");
        manager.getModules().clear();
        List<net.minecraft.gizmos.Gizmo> icons=new ArrayList<>();
        try(var collector=net.minecraft.gizmos.Gizmos.withCollector(icon->{icons.add(icon);return net.minecraft.gizmos.GizmoCollector.IGNORED;})){
            com.baggute.cheatclient.render.IndicatorExtractor.hearts(Vec3.ZERO,new org.joml.Quaternionf(),19,20,2,1.5,0xFFFF4040);
        }
        require(icons.size()>250&&icons.stream().anyMatch(i->i instanceof net.minecraft.gizmos.RectGizmo rect&&rect.style().fill()==0xFFFFCC32),"large pixel hearts include golden absorption");
        var hud=new Hud();player.inventory.setItem(0,new ItemStack(Items.POTION));player.inventory.setItem(2,new ItemStack(Items.POTION));player.inventory.setItem(3,new ItemStack(Items.SPLASH_POTION));
        var potions=HudRenderer.potionCounts(game,hud);
        require(potions.size()==2&&potions.getFirst().count()==2&&potions.getLast().icon().is(Items.SPLASH_POTION),"potion icons keep bottle types separate and sum counts");
        System.out.println("PASS: real mace/breach/shield combo hits, silent camera/packet order, AntiMiss input, local Reach, shield clicking, solid-only mining, pre-movement jump, attack-driven W-tap, moving bow prediction and frame-independent aim");
    }

    private static void requestedRepairs() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        manager.init();
        require(manager.getByName("Baritone")==null, "Baritone is not a client module");
        require(manager.getVisibleModules().stream().noneMatch(m->m.getName().equals("Appearance")||m.getName().equals("SeedCrackerX")), "themes and command services are absent from module lists");
        var health=new com.baggute.cheatclient.module.render.HealthIndicators();
        var armor=new com.baggute.cheatclient.module.render.ArmorIndicators();
        require(health.playersOnly.get()&&armor.playersOnly.get(), "indicators default to players only");
        require(armor.getSettings().contains(armor.playersOnly), "armor mob option is configurable");
        var contents=net.minecraft.world.item.alchemy.PotionContents.class;
        ItemStack strength=new ItemStack(Items.POTION), strong=new ItemStack(Items.POTION);
        strength.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.STRENGTH));
        strong.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.STRONG_STRENGTH));
        player.inventory.setItem(0,strength);player.inventory.setItem(1,strong);
        var rows=HudRenderer.potionCounts(game,new Hud());
        require(rows.size()==2&&rows.get(0).level()==1&&rows.get(1).level()==2, "strength I and II remain separate and carry numeric icon levels");
        var recipe=com.baggute.cheatclient.gui.PotionRecipeScreen.recipe("str");
        require(recipe.ingredients().equals(List.of(Items.NETHER_WART,Items.BLAZE_POWDER))&&recipe.extended().equals("8:00")&&recipe.strong().equals("1:30"), "strength guide includes real ingredient order and extended/strong variants");
        require(com.baggute.cheatclient.gui.PotionRecipeScreen.recipe("invis").ingredients().getLast()==Items.FERMENTED_SPIDER_EYE, "invisibility corrupts night vision");
        require(com.baggute.cheatclient.gui.PotionRecipeScreen.recipe("weakness").ingredients().equals(List.of(Items.FERMENTED_SPIDER_EYE)), "weakness starts from water without nether wart");

        setup();manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var menu=allocate(net.minecraft.world.inventory.InventoryMenu.class);menu.setCarried(ItemStack.EMPTY);
        var slots=net.minecraft.core.NonNullList.<net.minecraft.world.inventory.Slot>create();var container=new net.minecraft.world.SimpleContainer(46);
        for(int i=0;i<46;i++)slots.add(new net.minecraft.world.inventory.Slot(container,i,0,0));
        field(net.minecraft.world.inventory.AbstractContainerMenu.class,"slots").set(menu,slots);
        field(Player.class,"inventoryMenu").set(player,menu);player.containerMenu=menu;
        player.inventory.setItem(0,new ItemStack(Items.STONE,64));player.inventory.setItem(1,new ItemStack(Items.STONE,64));
        com.baggute.cheatclient.util.InventoryCommands.drop(new String[]{".drop","100","stone"});
        for(int i=0;i<40;i++)tick(manager);
        require(player.inventory.getItem(0).isEmpty()&&player.inventory.getItem(1).getCount()==28, "drop 100 spends exactly 100 across stacks");
        com.baggute.cheatclient.util.InventoryCommands.drop(new String[]{".drop","all","stone"});
        tick(manager);tick(manager); require(player.inventory.getItem(1).isEmpty(), "drop all removes the remaining matching item");
        var kit=com.google.gson.JsonParser.parseString("{\"slots\":[{\"slot\":0,\"itemId\":\"minecraft:stone\",\"count\":12}]}").getAsJsonObject();
        player.creative=true;player.inventory.setItem(1,new ItemStack(Items.DIAMOND));
        require(com.baggute.cheatclient.config.KitManager.apply(game,kit)==0&&player.inventory.getItem(0).getCount()==12&&player.inventory.getItem(1).isEmpty(), "creative kit replaces contents and clears unspecified slots");
        player.creative=false;player.inventory.setItem(1,new ItemStack(Items.DIAMOND));
        require(com.baggute.cheatclient.config.KitManager.apply(game,kit)==-1&&player.inventory.getItem(1).is(Items.DIAMOND), "remote survival cannot create a fake kit");

        setup();manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var cart=new AutoInstacart();manager.getModules().add(cart);cart.setEnabled(true);
        player.using=new ItemStack(Items.BOW);player.useTicks=3;
        player.inventory.setItem(1,new ItemStack(Items.RAIL));player.inventory.setItem(2,new ItemStack(Items.TNT_MINECART));
        player.setXRot(-20);cart.onBowReleased(player);player.using=null;
        tick(manager);tick(manager);tick(manager);
        require(field(AutoInstacart.class,"stage").getInt(cart)==5&&player.getXRot()==-20, "partial bow shot starts the camera-free placement delay");
        BlockPos support=new BlockPos(0,63,2);game.hitResult=new BlockHitResult(Vec3.atCenterOf(support),Direction.UP,support,false);
        player.setXRot(65);tick(manager);
        require(support.equals(field(AutoInstacart.class,"supportPos").get(cart))&&player.getXRot()==65, "cart target is captured after the player looks down");cart.setEnabled(false);
        setup();manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var options=allocate(net.minecraft.client.Options.class);field(Minecraft.class,"options").set(game,options);
        for(String key:List.of("keyLeft","keyRight","keyUp","keyDown","keyJump","keyShift")) field(net.minecraft.client.Options.class,key).set(options,allocate(net.minecraft.client.KeyMapping.class));
        var flight=new com.baggute.cheatclient.module.movement.Flight();flight.setEnabled(true);
        flight.onTick();require(player.motion.y==0&&player.noGravity,"neutral flight suspends gravity without passive ascent");
        options.keyJump.setDown(true);flight.onTick();require(player.motion.y>0,"flight ascends only on jump input");
        options.keyJump.setDown(false);options.keyShift.setDown(true);flight.onTick();require(player.motion.y<0,"flight descends on shift input");
        flight.setEnabled(false);require(!player.noGravity,"flight restores original gravity on disable");

        setup();manager=new ModuleManager();UtilityClient.moduleManager=manager;
        var alien=new ApexModule("AutoAlienWC","Fixture");manager.getModules().add(alien);alien.setEnabled(true);
        player.inventory.setItem(1,new ItemStack(Items.WIND_CHARGE));player.motion=new Vec3(0,-.5,0);player.setYRot(35);player.setXRot(-15);
        tick(manager);require(USES.isEmpty(),"AutoAlienWC requires equipped elytra and gliding");
        player.chest=new ItemStack(Items.ELYTRA);player.gliding=true;player.motion=new Vec3(0,.5,0);
        tick(manager);require(USES.isEmpty(),"AutoAlienWC cannot throw while rising");
        player.motion=new Vec3(0,-.5,0);tick(manager);
        require(USES.size()==1&&USES.getFirst().pitch==90&&player.getXRot()==90,"falling glider snaps down for one wind-charge throw");
        tick(manager);require(player.getXRot()==-15&&player.getYRot()==35,"AutoAlienWC restores the original view on the next tick");
        alien.setEnabled(false);
        System.out.println("PASS: separate Baritone, hidden command/theme entries, numeric potion levels, brewing recipes, exact drop quantities, forced creative kits, delayed partial-draw carts, neutral flight and elytra-only wind-charge view restoration");
    }

    private static void hud() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        Hud hud = new Hud(); manager.getModules().add(hud);
        for (Setting setting : hud.getSettings()) if (setting instanceof BoolSetting value && setting.getName().startsWith("show_")) value.set(false);
        ((BoolSetting) hud.getSettings().stream().filter(s -> s.getName().equals("show_fps")).findFirst().orElseThrow()).set(true);
        field(Minecraft.class, "font").set(game, allocate(FontShim.class));
        Graphics graphics = allocate(Graphics.class); graphics.matrix = new Matrix3x2fStack(32); graphics.lines = new ArrayList<>();
        HudRenderer.render(graphics); require(graphics.lines.isEmpty(), "disabled HUD does not draw gameplay widgets");
        HudRenderer.renderPreview(graphics); require(!graphics.lines.isEmpty() && !hud.isEnabled(), "disabled HUD has a working editor preview");
        graphics.lines.clear(); hud.setEnabled(true); LateBridge.onHudRender(graphics);
        require(!graphics.lines.isEmpty(), "bridge invokes enabled HUD drawing");
    }

    private static void panic() throws Exception {
        setup(); ModuleManager manager = new ModuleManager(); UtilityClient.moduleManager = manager;
        Probe broken = new Probe("CleanupFailure", -1, -1), healthy = new Probe("CleanupAfter", -1, -1);
        broken.failDisable = true; broken.setEnabled(true); healthy.setEnabled(true);
        Panic panic = new Panic(); bind(panic, "panic", 68); manager.getModules().add(broken); manager.getModules().add(healthy); manager.getModules().add(panic);
        gui.current = new Screen(Component.literal("Chat fixture")) {}; game.focused = false;
        require(LateBridge.onKeyPress(new KeyEvent(68, 0, 0), GLFW.GLFW_PRESS), "panic consumed even over chat/focus guard");
        require(!broken.isEnabled() && !healthy.isEnabled() && gui.current == null, "panic survives cleanup failure and closes screen");
    }

    private static void tick(ModuleManager manager) { game.ticks++; manager.onTick(); }
    private static KeybindSetting primary(Module module) { return (KeybindSetting) module.getSettings().stream().filter(s -> s.getName().equals("keybind")).findFirst().orElseThrow(); }
    private static void bind(Module module, String name, int key) { ((KeybindSetting) module.getSettings().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow()).setKey(key); }
    private static void number(Module module, String name, int value) { ((IntSetting) module.getSettings().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow()).set(value); }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static <T> T allocate(Class<T> type) throws InstantiationException { return type.cast(MEMORY.allocateInstance(type)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Probe extends Module {
        int enables, actions, ticks; boolean failEnable, failAction, failTick, failDisable;
        Probe(String name, int key, int action) { super(name, "Fixture", Category.MISC); addSetting(new KeybindSetting("keybind", "Toggle", key, false)); addSetting(new KeybindSetting("action", "Act", action, false)); }
        @Override protected void onEnable() { enables++; if (failEnable) throw new IllegalStateException("injected enable failure"); }
        @Override protected void onDisable() { if (failDisable) throw new IllegalStateException("injected cleanup failure"); }
        @Override public void onRawKey(int key, int action) { if (failAction) throw new IllegalStateException("injected action failure"); if (isEnabled() && action == GLFW.GLFW_PRESS) actions++; }
        @Override public void onTick() { if (failTick) throw new IllegalStateException("injected tick failure"); ticks++; }
    }
    private static final class Game extends Minecraft {
        boolean focused; int ticks;
        private Game() { super(null); }
        @Override public boolean isWindowActive() { return focused; }
        @Override public boolean isPaused() { return false; }
        @Override public com.mojang.blaze3d.platform.Window getWindow() { return null; }
        @Override public int getFps() { return 144; }
        @Override public ClientPacketListener getConnection() { return player == null ? null : player.connection; }
    }
    private static final class GuiShim extends Gui {
        Screen current; List<String> messages; ChatShim listener;
        private GuiShim() { super(null, null, null); }
        @Override public Screen screen() { return current; }
        @Override public void setScreen(Screen screen) { current = screen; }
        @Override public net.minecraft.client.multiplayer.chat.ChatListener chatListener() { return listener; }
    }
    private static final class ChatShim extends net.minecraft.client.multiplayer.chat.ChatListener {
        private ChatShim() { super(null); }
        @Override public void handleSystemMessage(Component component, boolean overlay) { gui.messages.add(component.getString()); }
    }
    private static final class Hand extends LocalPlayer {
        Inventory inventory; ItemStack offhand, using, chest; Vec3 motion; ItemCooldowns cooldowns;
        float health=20, charge=1; boolean sprint,ammo,creative,noGravity,gliding; int jumps,useTicks;
        private Hand() { super(null, null, null, null, null, null, false, null); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public BlockPos blockPosition() { return BlockPos.containing(position()); }
        @Override public ItemCooldowns getCooldowns() { return cooldowns; }
        @Override public ItemStack getMainHandItem() { return inventory.getSelectedItem(); }
        @Override public ItemStack getOffhandItem() { return offhand; }
        @Override public ItemStack getItemInHand(InteractionHand hand) { return hand == InteractionHand.MAIN_HAND ? getMainHandItem() : offhand; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isCreative() { return creative; }
        @Override public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) { return slot==net.minecraft.world.entity.EquipmentSlot.CHEST ? (chest==null?ItemStack.EMPTY:chest) : super.getItemBySlot(slot); }
        @Override public boolean isNoGravity() { return noGravity; }
        @Override public void setNoGravity(boolean value) { noGravity=value; }
        @Override public boolean isFallFlying() { return gliding; }
        @Override public void setDeltaMovement(Vec3 value) { motion=value; }
        @Override public float getHealth() { return health == 0 ? 20 : health; }
        @Override public float getAbsorptionAmount() { return 0; }
        @Override public boolean isSleeping() { return false; }
        @Override public boolean isInWater() { return false; }
        @Override public boolean onClimbable() { return false; }
        @Override public boolean isInvisible() { return false; }
        @Override public boolean isSprinting() { return sprint; }
        @Override public void setSprinting(boolean value) { sprint=value; }
        @Override public void jumpFromGround() { jumps++; }
        @Override public int getTicksUsingItem() { return useTicks; }
        @Override public ItemStack getProjectile(ItemStack weapon){return ammo?new ItemStack(Items.ARROW):ItemStack.EMPTY;}
        @Override public net.minecraft.world.entity.player.Abilities getAbilities(){return new net.minecraft.world.entity.player.Abilities();}
        @Override public InteractionHand getUsedItemHand(){return InteractionHand.MAIN_HAND;}
        @Override public EntityType<?> getType() { return EntityTypes.PLAYER; }
        @Override public Component getName() { return Component.literal("Fixture player"); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isShiftKeyDown() { return false; }
        @Override public boolean onGround() { return true; }
        @Override public void setOnGround(boolean value) {}
        @Override public boolean isUsingItem() { return using != null; }
        @Override public ItemStack getUseItem() { return using == null ? ItemStack.EMPTY : using; }
        @Override public Vec3 getDeltaMovement() { return motion; }
        @Override public float getAttackStrengthScale(float partial) { return charge; }
        @Override public double blockInteractionRange() { return 4.5; }
        @Override public double entityInteractionRange() { return 3; }
        @Override public RegistryAccess registryAccess() { return MinecraftBootstrap.registries(); }
        @Override public Level level() { return world; }
        @Override public void swing(InteractionHand hand) {}
    }
    private static final class Pearl extends net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl {
        Entity thrower; Vec3 motion;
        private Pearl() { super(EntityTypes.ENDER_PEARL, world); }
        @Override public Entity getOwner() { return thrower; }
        @Override public Vec3 getDeltaMovement() { return motion; }
        @Override public boolean isNoGravity() { return false; }
        @Override public boolean isInWater() { return false; }
    }
    private static final class World extends ClientLevel {
        Map<BlockPos, BlockState> blocks; List<Entity> entities; boolean realClip;
        private World() { super(null, null, null, null, 0, 0, null, false, 0, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public RegistryAccess registryAccess() { return MinecraftBootstrap.registries(); }
        @Override public Iterable<Entity> entitiesForRendering() { return entities; }
        @Override public Entity getEntity(int id) { return entities.stream().filter(e -> e.getId() == id).findFirst().orElse(null); }
        @Override public List<Entity> getEntities(Entity excluded, AABB box, Predicate<? super Entity> predicate) { return entities.stream().filter(e -> e != excluded && predicate.test(e) && e.getBoundingBox().intersects(box)).toList(); }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockHitResult clip(ClipContext context) { if(realClip)return super.clip(context);return BlockHitResult.miss(context.getTo(), Direction.UP, BlockPos.containing(context.getTo())); }
    }
    private static final class Link extends ClientPacketListener {
        List<Packet<?>> packets;
        Collection<PlayerInfo> online;
        private Link() { super(null, null, null); }
        @Override public void send(Packet<?> packet) { packets.add(packet); }
        @Override public Collection<PlayerInfo> getOnlinePlayers(){return online==null?List.of():online;}
    }
    private static final class Mode extends MultiPlayerGameMode {
        Game game; int stabs, attacks; boolean consumePearl; InteractionResult itemResult;
        List<Integer> attackSlots = new ArrayList<>(); int packetsAtAttack;
        int inventoryClicks,lastButton,lastSlot;net.minecraft.world.inventory.ContainerInput lastInput;GameType playerMode=GameType.SURVIVAL;
        private Mode() { super(null, null); }
        @Override public InteractionResult useItem(Player user, InteractionHand hand) {
            Object snapshot = LateBridge.captureItemUse(user, hand);
            ItemStack item = user.getItemInHand(hand);
            String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            USES.add(new Use(game.ticks, name, hand, user.getYRot(), user.getXRot()));
            if (item.is(Items.CROSSBOW)) player.using = item;
            if (consumePearl && item.is(Items.ENDER_PEARL) && itemResult.consumesAction()) item.shrink(1);
            return (InteractionResult) LateBridge.completeItemUse(itemResult, snapshot);
        }
        @Override public void releaseUsingItem(Player user) { player.using = null; }
        @Override public void piercingAttack(PiercingWeapon weapon) { stabs++; }
        @Override public GameType getPlayerMode(){return playerMode==null?GameType.SURVIVAL:playerMode;}
        @Override public void handleContainerInput(int container,int slot,int button,net.minecraft.world.inventory.ContainerInput input,Player user){
            inventoryClicks++;lastSlot=slot;lastButton=button;lastInput=input;
            if(input==net.minecraft.world.inventory.ContainerInput.THROW){
                int inventory=slot>=36&&slot<=44?slot-36:slot==45?40:slot>=5&&slot<=8?44-slot:slot;
                ItemStack stack=user.getInventory().getItem(inventory);
                stack.shrink(button==1?stack.getCount():1);
            }
        }
        @Override public void handleCreativeModeItemAdd(ItemStack stack,int slot) {}
        @Override public void attack(Player user, Entity target) {
            attacks++; if(attackSlots==null) attackSlots=new ArrayList<>(); attackSlots.add(user.getInventory().getSelectedSlot());
            packetsAtAttack=((Link)player.connection).packets.size(); GameplayHooks.afterAttack(user,target);
        }
    }
    private static final class FontShim extends Font {
        private FontShim() { super(null); }
        @Override public int width(String text) { return text.length() * 6; }
    }
    private static final class Graphics extends GuiGraphicsExtractor {
        Matrix3x2fStack matrix; List<String> lines;
        private Graphics() { super(null, null, 400, 300); }
        @Override public Matrix3x2fStack pose() { return matrix; }
        @Override public void text(Font font, String text, int x, int y, int color, boolean shadow) { lines.add(text); }
        @Override public void fill(int x1, int y1, int x2, int y2, int color) {}
    }
}
