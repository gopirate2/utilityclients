// language: Java, file: NametagRenderer.java, target: Fabric 26.2
package com.baggute.cheatclient.render;

import com.baggute.cheatclient.UtilityClient;
import com.baggute.cheatclient.friends.FriendsManager;
import com.baggute.cheatclient.module.render.Nametags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Emits the floating labels for {@link Nametags}.
 *
 * <p>Runs inside the level render's feature pass, so labels share the gizmo
 * pipeline with the ESP boxes: correct camera transform, correct depth handling,
 * and no per-frame matrix bookkeeping in module code.
 *
 * <p>Counted against {@code maxEntries} while walking, not after sorting, because
 * the point of the cap is to bound the work this loop does on a busy server.
 */
public final class NametagRenderer {

    private NametagRenderer() {}

    public static void emit(LevelRenderState state) {
        if (UtilityClient.moduleManager == null) return;

        Nametags tags = UtilityClient.moduleManager.getByName("Nametags") instanceof Nametags n ? n : null;
        if (tags == null || !tags.isEnabled()) return;
        // Friends get a label of their own even when the mob pass is off, so the
        // early out has to count them too.
        if (!tags.showItems() && !tags.showMobs() && FriendsManager.size() == 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 camera = state.cameraRenderState.pos;
        double rangeSq = tags.getRange() * tags.getRange();
        int limit = tags.maxEntries();
        int drawn = 0;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (drawn >= limit) break;
            if (entity == mc.player) continue;
            if (entity.distanceToSqr(camera) > rangeSq) continue;

            if (entity instanceof ItemEntity item) {
                if (!tags.showItems()) continue;
                ItemStack stack = item.getItem();
                if (stack.isEmpty()) continue;

                String label = stack.getHoverName().getString();
                if (stack.getCount() > 1) label += " x" + stack.getCount();

                Gizmos.billboardTextOverMob(item, 2, label,
                        tags.itemsColor(), (float) tags.itemsScale()).setAlwaysOnTop();
                drawn++;
                continue;
            }

            if (entity instanceof LivingEntity living) {
                // Friends are labelled in green whether or not the mob pass is on;
                // everyone else still needs showMobs, and other players are left to
                // the vanilla nametag.
                boolean friend = living instanceof Player player && FriendsManager.isFriend(player);
                if (!friend) {
                    if (!tags.showMobs()) continue;
                    if (living instanceof Player) continue;
                }

                String label = living.getName().getString();
                if (tags.showHealth()) {
                    label += " " + (int) Math.ceil(living.getHealth())
                            + "/" + (int) Math.ceil(living.getMaxHealth()) + "h";
                }

                Gizmos.billboardTextOverMob(living, 2, label,
                        friend ? FriendsManager.FRIEND_COLOR : tags.mobsColor(),
                        (float) tags.mobsScale()).setAlwaysOnTop();
                drawn++;
            }
        }
    }
}
