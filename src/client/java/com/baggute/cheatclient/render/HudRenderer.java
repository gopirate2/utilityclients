// language: Java, file: HudRenderer.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.misc.Hud;
import com.baggute.cheatclient.module.misc.Notifications;
import com.baggute.cheatclient.module.render.ArrayListModule;
import com.baggute.cheatclient.module.render.Nametags;
import com.baggute.cheatclient.module.render.TargetHud;
import com.baggute.cheatclient.util.ModuleUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HudRenderer {

    private HudRenderer() {}

    /** The editor previews widgets even while Hud is off; gameplay state stays intact. */
    public static void renderPreview(GuiGraphicsExtractor g) {
        if (com.baggute.cheatclient.stealth.ScreenBypass.worldHidden() || UtilityClient.moduleManager == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;
        layer("widget preview", () -> drawWidgets(g, mc, true));
    }

    public static String visibilityReason() {
        if (UtilityClient.isUnloaded()) return "Client unloaded";
        if (com.baggute.cheatclient.stealth.ScreenBypass.panicHidden()) return "HUD hidden by panic";
        if (com.baggute.cheatclient.stealth.StealthConfig.get().bypassMode()) return "HUD hidden by bypass mode";
        if (com.baggute.cheatclient.stealth.ScreenBypass.hidingFromCapture())
            return HudOverlay.visible() ? "HUD shown in the overlay window" : "HUD hidden: capture protection unavailable";
        Module module = UtilityClient.moduleManager == null ? null : UtilityClient.moduleManager.getByName("Hud");
        return module == null || !module.isEnabled() ? "Hud is off; enable it for gameplay" : null;
    }

    public static void render(GuiGraphicsExtractor g) {
        if (com.baggute.cheatclient.stealth.ScreenBypass.worldHidden()) return;
        try {
            if (UtilityClient.moduleManager == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            // Every layer is isolated as well. A broken feature costs its own rows
            // and nothing else, and the first failure of each layer is written down.
            //
            // The three layers the secondary overlay window paints are skipped while
            // that window is up. Not a capture gate: the overlay is this client's own
            // top-level HWND, drawn in AWT, offset by its own region - running both
            // paths at once produced two copies of every widget at slightly different
            // positions, which read as flicker. One surface owns them at a time, and
            // the overlay only exists while hudOverlay is on and exclusion stuck.
            boolean overlayOwnsHud = HudOverlay.visible();
            layer("gamemode alerts",()->com.baggute.cheatclient.module.misc.GamemodeDetector.render(g,mc));
            if (!overlayOwnsHud) {
                layer("widgets", () -> drawWidgets(g, mc));
                layer("arraylist", () -> drawArrayList(g, mc));
                layer("watermark", () -> WatermarkRenderer.draw(g, mc));
            }
            if (!overlayOwnsHud) {
                layer("debug", () -> drawDebugBoard(g, mc));
                layer("notifications", () -> Notifications.render(g, mc));
            }
        } catch (Throwable failure) {
            report("render", failure);
        }
    }

    private interface Layer {
        void draw();
    }

    /** A layer that already failed once, so the log does not fill at 300 fps. */
    private static final Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void layer(String name, Layer body) {
        try {
            body.draw();
        } catch (Throwable failure) {
            report(name, failure);
        }
    }

    /**
     * Reports a layer failure once, then stays quiet.
     *
     * <p>Not swallowed silently: a HUD layer that throws every frame is the reason
     * a widget silently disappears, and that belongs in the log rather than in
     * someone's guess about why their array list stopped drawing.
     */
    static void report(String layer, Throwable failure) {
        // Both the trace and file entry are rate-limited per failing layer.
        if (!REPORTED.add(layer)) return;
        failure.printStackTrace();
        try {
            com.baggute.cheatclient.stealth.LogManager.log(
                    "ERROR", "HudRenderer: layer '" + layer + "' failed", failure);
        } catch (Throwable ignored) {
            // Logging must never be the thing that breaks the render pass.
        }
        try {
            UtilityClient.LOGGER.error("[HudRenderer] layer '{}' failed", layer, failure);
        } catch (Throwable ignored) {
            // Same.
        }
    }

    /** Stage board: one line per automation module currently mid-action. */
    private static void drawDebugBoard(GuiGraphicsExtractor g, Minecraft mc) {
        Module module = UtilityClient.moduleManager.getByName("Debug");
        if (!(module instanceof com.baggute.cheatclient.module.misc.Debug debug)) return;
        if (!debug.isOverlayOn()) return;

        java.util.List<String> lines = com.baggute.cheatclient.module.misc.Debug.snapshot();
        if (lines.isEmpty()) return;

        int lineH = 10;
        int pad = 4;
        int width = 0;
        for (String line : lines) width = Math.max(width, mc.font.width(line));

        int bx = 6;
        int by = mc.getWindow().getGuiScaledHeight() - (lines.size() * lineH) - pad * 2 - 6;
        g.fill(bx - 2, by - 2, bx + width + pad * 2, by + lines.size() * lineH + pad, 0xC0101216);
        g.fill(bx - 2, by - 2, bx, by + lines.size() * lineH + pad, 0xFF7C9CFF);

        for (int i = 0; i < lines.size(); i++) {
            g.text(mc.font, lines.get(i), bx + 2, by + i * lineH, 0xFFE7E9EE, false);
        }
    }

    // ---- widgets ----

    private static void drawWidgets(GuiGraphicsExtractor g, Minecraft mc) {
        drawWidgets(g, mc, false);
    }

    private static void drawWidgets(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
        Module module = UtilityClient.moduleManager.getByName("Hud");
        if (!(module instanceof Hud hud) || (!preview && !hud.isEnabled())) return;

        double scale = hud.getScale();
        // Balanced even when a widget throws: the pose matrix is shared with the
        // rest of the GUI frame, so a missing popMatrix corrupts every screen
        // drawn after the HUD.
        g.pose().pushMatrix();
        try {
            g.pose().scale((float) scale, (float) scale);

            for (Hud.Widget widget : Hud.Widget.values()) {
                if (!hud.isWidgetEnabled(widget)) continue;
                if (widget == Hud.Widget.POTIONS_COUNTER) {
                    drawPotionCounter(g, mc, hud); continue;
                }

                List<String> lines;
                try {
                    lines = widgetLines(widget, mc, hud);
                } catch (Throwable failure) {
                    report("widget " + widget.name(), failure);
                    continue;
                }

                int originX = hud.getX(widget);
                int originY = hud.getY(widget);
                double widgetScale = hud.getWidgetScale(widget);

                if (lines.isEmpty()) {
                    hud.setBounds(widget, originX, originY, 0, 10);
                    continue;
                }

                int maxWidth = 0;
                for (String line : lines) maxWidth = Math.max(maxWidth, mc.font.width(line));

                g.pose().pushMatrix();
                try {
                    g.pose().translate((float) originX, (float) originY);
                    g.pose().scale((float) widgetScale, (float) widgetScale);

                    if (hud.hasWidgetBackground(widget)) {
                        g.fill(-2, -2, maxWidth + 4, lines.size() * 10 + 2, 0x80000000);
                    }

                    int color = hud.getWidgetColor(widget);
                    int y = 0;
                    for (String line : lines) {
                        // Armour rows render as a piece icon plus durability, not the item
                        // id text: reading "diamond_chestplate 87%" mid-fight is nonsense.
                        if (widget == Hud.Widget.ARMOR && line.startsWith(ICON_MARK)) {
                            drawArmorRow(g, mc, line.substring(ICON_MARK.length()), color, y);
                        } else {
                            g.text(mc.font, line, 0, y, color, true);
                        }
                        y += 10;
                    }
                } catch (Throwable failure) {
                    report("widget " + widget.name(), failure);
                } finally {
                    g.pose().popMatrix();
                }

                hud.setBounds(widget, originX, originY,
                        (int) (maxWidth * widgetScale), (int) (lines.size() * 10 * widgetScale));
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * The text a widget would draw, without drawing it.
     *
     * <p>Public because the capture-excluded overlay window renders the same rows
     * with Java2D instead of the game's extractor. Two implementations of one
     * list would drift, so there is one source of truth and two back ends.
     */
    public static List<String> widgetLines(Hud.Widget widget, Minecraft mc, Hud hud) {
        List<String> lines = new ArrayList<>();

        switch (widget) {
            case MODULES -> {
                Set<String> hidden = parseCsv(hud.getHiddenSetting().get());
                List<String> enabled = new ArrayList<>();
                for (Module m : UtilityClient.moduleManager.getModules()) {
                    if (m.isEnabled() && !hidden.contains(m.getName())) enabled.add(m.getName());
                }
                enabled.sort(String.CASE_INSENSITIVE_ORDER);
                lines.addAll(enabled);
            }
            case COORDS -> lines.add("X: " + (int) Math.floor(mc.player.getX())
                    + "  Y: " + (int) Math.floor(mc.player.getY())
                    + "  Z: " + (int) Math.floor(mc.player.getZ()));
            case ARMOR -> {
                try {
                    for (int slot = 36; slot <= 39; slot++) {
                        ItemStack stack = mc.player.getInventory().getItem(slot);
                        if (stack.isEmpty()) continue;
                        // The marker carries the item name; drawArmorRow draws the glyph
                        // from it and prints the percentage beside it.
                        lines.add(ICON_MARK + BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).getPath() + "|" + durability(stack));
                    }
                } catch (RuntimeException ignored) {
                    // Armour slot indexing has moved between versions; report it
                    // instead of throwing out of the HUD render.
                }
                if (lines.isEmpty()) lines.add("armor: n/a");
            }
            case POTIONS -> {
                // "Strength II 1:30" from the effect instance, not the potion id:
                // the id carries the variant prefix, which is why it used to read
                // "strong_swiftness" instead of an effect at all.
                for (PotionWidget.Row row : PotionWidget.rows(mc, hud.getWidgetColor(widget))) {
                    lines.add(row.text());
                }
                if (lines.isEmpty()) lines.add("no effects");
            }
            case COUNTERS -> {
                String counters = hud.getItemCounters();
                if (counters == null || counters.isBlank()) lines.add("counters: none");
                else {
                    for (String raw : counters.split(",")) {
                        String id = raw.trim();
                        if (id.isEmpty()) continue;
                        lines.add(id + ": " + countItem(mc, id));
                    }
                }
            }
            case NAMETAGS -> {
                Nametags tags = module("Nametags") instanceof Nametags n && n.isEnabled() ? n : null;
                if (tags == null) break;
                double range = tags.getRange();
                java.util.List<String> rows = new java.util.ArrayList<>();
                for (var entity : mc.level.entitiesForRendering()) {
                    if (!(entity instanceof net.minecraft.world.entity.player.Player p) || p == mc.player) continue;
                    double dist = p.distanceTo(mc.player);
                    if (dist > range) continue;
                    StringBuilder row = new StringBuilder(p.getName().getString());
                    if (tags.showHealth()) row.append(" ").append((int) Math.ceil(p.getHealth())).append("h");
                    row.append(" ").append(String.format("%.1fm", dist));
                    rows.add(row.toString());
                }
                java.util.Collections.sort(rows);
                int limit = Math.min(rows.size(), tags.maxEntries());
                for (int i = 0; i < limit; i++) lines.add(rows.get(i));
                if (rows.isEmpty()) lines.add("nobody nearby");
            }
            case TARGET_HUD -> {
                TargetHud hudModule = module("TargetHud") instanceof TargetHud t && t.isEnabled() ? t : null;
                if (hudModule == null) break;
                var target = nearestPlayer(mc, hudModule.getRange());
                if (target == null) { lines.add("target: none"); break; }
                lines.add(target.getName().getString());
                lines.add("hp " + (int) Math.ceil(target.getHealth()) + "/" + (int) target.getMaxHealth());
                if (hudModule.showArmor()) lines.add("armor " + target.getArmorValue());
                if (hudModule.showDistance()) lines.add(String.format("%.1fm", target.distanceTo(mc.player)));
            }
            case TRAJECTORY -> {
                com.baggute.cheatclient.module.render.Trajectory trajectory =
                        module("Trajectory") instanceof com.baggute.cheatclient.module.render.Trajectory t
                                && t.isEnabled() ? t : null;
                if (trajectory == null) break;
                float pitch = mc.player.getXRot();
                double[] path = com.baggute.cheatclient.module.render.Trajectory.predict(
                        pitch, 3.0, trajectory.ticks());
                lines.add(String.format("range %.1fm", path[0]));
                lines.add(String.format("drop %.1f", path[1]));
                if (trajectory.showPitch()) lines.add(String.format("pitch %.0f", -pitch));
            }
            case POTIONS_COUNTER -> {
                java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                // getContainerSize() includes the offhand slot (index 40 in 26.2);
                // a hardcoded 40 dropped an offhand potion from the count.
                int slots = mc.player.getInventory().getContainerSize();
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack stack = mc.player.getInventory().getItem(slot);
                    if (stack.isEmpty() || !ModuleUtils.matches(stack, "potion")) continue;
                    String id = potionId(stack);
                    if (id.isEmpty()) continue;
                    counts.merge(id, stack.getCount(), Integer::sum);
                }

                Set<String> filter = parseCsv(hud.getPotionFilter());
                for (var entry : counts.entrySet()) {
                    if (!filter.isEmpty() && !filter.contains(entry.getKey())) continue;
                    lines.add(trimPrefix(entry.getKey()) + ": " + entry.getValue());
                }
                if (lines.isEmpty()) lines.add("potions: none");
            }
            case KEYSTROKES -> {
                lines.add(key(mc.options.keyUp.isDown()) + " " + key(mc.options.keyDown.isDown())
                        + " " + key(mc.options.keyLeft.isDown()) + " " + key(mc.options.keyRight.isDown()));
                lines.add("LMB " + key(mc.options.keyAttack.isDown())
                        + "  RMB " + key(mc.options.keyUse.isDown()));
            }
            case FPS -> lines.add(mc.getFps() + " fps");
            case PING -> {
                int ping = -1;
                if (mc.getConnection() != null && mc.player != null) {
                    var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                    if (info != null) ping = info.getLatency();
                }
                lines.add(ping < 0 ? "ping --" : "ping " + ping + "ms");
            }
            case CLOCK -> lines.add(java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
            case SPEED -> {
                net.minecraft.world.phys.Vec3 velocity = mc.player.getDeltaMovement();
                double blocksPerSecond = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0;
                lines.add(String.format("%.1f b/s", blocksPerSecond));
            }
            default -> { }
        }
        return lines;
    }

    private static Module module(String name) {
        for (var m : com.baggute.cheatclient.UtilityClient.moduleManager.getModules()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static net.minecraft.world.entity.player.Player nearestPlayer(Minecraft mc, double range) {
        if (mc.player == null || mc.level == null) return null;
        net.minecraft.world.entity.player.Player best = null;
        double bestDist = range * range;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player p) || p == mc.player) continue;
            if (p.getHealth() <= 0) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    private static String potionId(ItemStack stack) {
        try {
            var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (contents == null) return "";
            var potion = contents.potion();
            if (potion.isEmpty()) return "";
            var key = BuiltInRegistries.POTION.getKey(potion.get().value());
            return key == null ? "" : key.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
    public record PotionCount(ItemStack icon, int count) {
        public int level() {
            var contents = icon.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            int level = 1;
            if (contents != null) for (var effect : contents.getAllEffects()) level = Math.max(level, effect.getAmplifier() + 1);
            return level;
        }
    }
    public static List<PotionCount> potionCounts(Minecraft mc, Hud hud) {
        List<PotionCount> rows = new ArrayList<>(); Set<String> filter = parseCsv(hud.getPotionFilter());
        var inventory = mc.player.getInventory();
        for (int slot=0; slot<inventory.getContainerSize(); slot++) {
            ItemStack stack=inventory.getItem(slot);
            if (!stack.is(net.minecraft.world.item.Items.POTION) && !stack.is(net.minecraft.world.item.Items.SPLASH_POTION)
                    && !stack.is(net.minecraft.world.item.Items.LINGERING_POTION)) continue;
            if (!filter.isEmpty() && !filter.contains(potionId(stack))) continue;
            int match=-1;
            for(int i=0;i<rows.size();i++) if(ItemStack.isSameItemSameComponents(rows.get(i).icon(),stack)) { match=i; break; }
            if(match<0) rows.add(new PotionCount(stack.copyWithCount(1),stack.getCount()));
            else { var previous=rows.get(match); rows.set(match,new PotionCount(previous.icon(),previous.count()+stack.getCount())); }
        }
        return rows;
    }
    private static void drawPotionCounter(GuiGraphicsExtractor g, Minecraft mc, Hud hud) {
        var widget=Hud.Widget.POTIONS_COUNTER; var rows=potionCounts(mc,hud);
        int x=hud.getX(widget),y=hud.getY(widget),color=hud.getWidgetColor(widget);
        double scale=hud.getWidgetScale(widget); int width=rows.isEmpty()?mc.font.width("potions: 0"):32;
        for(var row:rows) width=Math.max(width,20+mc.font.width(Integer.toString(row.count())));
        int height=Math.max(1,rows.size())*20;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x,y); g.pose().scale((float)scale,(float)scale);
            if(hud.hasWidgetBackground(widget)) g.fill(-3,-3,width+3,height+1,0x80000000);
            if(rows.isEmpty()) g.text(mc.font,"potions: 0",0,4,color,true);
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i); g.item(row.icon(),0,i*20);
                String level = Integer.toString(row.level());
                g.fill(15-mc.font.width(level),i*20+7,17,i*20+17,0xB0000000);
                g.text(mc.font,level,16-mc.font.width(level),i*20+8,0xFFFFFFFF,true);
                g.text(mc.font,Integer.toString(row.count()),20,i*20+4,color,true);
            }
        } finally { g.pose().popMatrix(); }
        hud.setBounds(widget,x,y,(int)Math.ceil(width*scale),(int)Math.ceil(height*scale));
    }

    private static String trimPrefix(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String key(boolean down) {
        return down ? "[#]" : "[ ]";
    }

    /** Marks a HUD line that must be drawn as an icon instead of text. */
    private static final String ICON_MARK = "\u0001";

    /**
     * One armour piece: the game's own item texture, then the durability.
     *
     * <p>Rendered with the extractor's item pass, so the icon is the actual vanilla
     * asset with its enchantment glint and dyed colour. The hand-drawn fill bitmap is
     * kept only as a fallback for an id the item registry does not know.
     */
    private static void drawArmorRow(GuiGraphicsExtractor g, Minecraft mc, String payload, int color, int y) {
        int bar = payload.indexOf('|');
        String id = bar < 0 ? payload : payload.substring(0, bar);
        String label = bar < 0 ? "" : payload.substring(bar + 1);

        ItemStack icon = itemById(id);
        if (icon != null) {
            g.item(icon, 0, y - 4);
        } else {
            int tint = materialColor(id);
            byte[] mask = slotMask(id);
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    if ((mask[row] & (0x80 >>> col)) == 0) continue;
                    g.fill(col, y + row, col + 1, y + row + 1, tint);
                }
            }
        }

        if (!label.isEmpty()) {
            g.text(mc.font, label, icon != null ? 18 : 10, y, color, true);
        }
    }

    /** Registry id to a display stack, or null when the id is not a known item. */
    private static ItemStack itemById(String id) {
        try {
            var key = net.minecraft.resources.Identifier.tryParse(
                    id.contains(":") ? id : "minecraft:" + id);
            if (key == null) return null;
            var item = BuiltInRegistries.ITEM.get(key);
            return item.isEmpty() ? null : new ItemStack(item.get().value());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Bitmap of the armour slot, one byte per row, MSB = leftmost pixel. */
    private static byte[] slotMask(String id) {
        if (id.contains("helmet") || id.contains("cap") || id.contains("skull")) {
            return new byte[]{(byte) 0x3C, (byte) 0x7E, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, (byte) 0xE7, (byte) 0xE7, (byte) 0x00};
        }
        if (id.contains("leggings") || id.contains("pants")) {
            return new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xC3, (byte) 0xC3,
                    (byte) 0xC3, (byte) 0xC3, (byte) 0xC3, (byte) 0x00};
        }
        if (id.contains("boots")) {
            return new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0xC3, (byte) 0xC3,
                    (byte) 0xC3, (byte) 0xFF, (byte) 0xFF, (byte) 0x00};
        }
        // Chestplate (and anything unrecognised): a torso silhouette.
        return new byte[]{(byte) 0xE7, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xE7, (byte) 0x00};
    }

    /** Material colour, so netherite and iron are distinguishable at a glance. */
    private static int materialColor(String id) {
        if (id.contains("netherite")) return 0xFF4A3F3A;
        if (id.contains("diamond"))   return 0xFF4AEDD9;
        if (id.contains("gold"))      return 0xFFF0C24A;
        if (id.contains("iron"))      return 0xFFD8D8D8;
        if (id.contains("chain"))     return 0xFF8A8A8A;
        if (id.contains("leather"))   return 0xFF9C6B3F;
        if (id.contains("turtle"))    return 0xFF3FA34D;
        return 0xFFB0B0B0;
    }

    private static String durability(ItemStack stack) {
        if (!stack.isDamageableItem()) return "";
        int max = stack.getMaxDamage();
        int left = max - stack.getDamageValue();
        int pct = max <= 0 ? 0 : (int) (100L * left / max);
        return pct + "%";
    }

    // ---- array list ----

    private static void drawArrayList(GuiGraphicsExtractor g, Minecraft mc) {
        Module module = UtilityClient.moduleManager.getByName("ArrayList");
        if (module instanceof ArrayListModule list) list.renderList(g, mc);
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int countItem(Minecraft mc, String id) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) total += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString().equals(id)) total += offhand.getCount();
        return total;
    }
}
