package com.baggute.cheatclient.module.player;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.settings.IntSetting;
import com.baggute.cheatclient.util.InventoryUtils;
import com.baggute.cheatclient.util.Stealth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
public final class AutoTotem extends Module {
 private final BoolSetting replaceHeld=new BoolSetting("replaceHeld","Replace a non-totem offhand",true); private int cooldown;
 private final IntSetting refillTicks=new IntSetting("refillTicks","Ticks between offhand refill attempts",2,1,40);
 private final BoolSetting inventoryFirst=new BoolSetting("inventoryFirst","Use main-inventory totems before hotbar totems",false);
 public AutoTotem(){super("AutoTotem","Moves an inventory totem into the offhand slot.",Category.PLAYER);addSetting(replaceHeld);addSetting(refillTicks);addSetting(inventoryFirst);}
 public void onTick(){
  Minecraft mc=Minecraft.getInstance();
  if(mc.player==null||mc.gameMode==null)return;
  if(cooldown>0){cooldown--;return;}
  // Never click a menu the player is using, and never fight a stack on the cursor.
  if(mc.gui.screen()!=null||mc.isPaused())return;
  if(mc.player.containerMenu!=mc.player.inventoryMenu)return;
  if(!mc.player.containerMenu.getCarried().isEmpty())return;
  // A swap on the same tick as a rotation or an attack makes the server resolve the
  // interaction against the stack the player was holding before it. The offhand fill
  // waits for a tick that owns no other packet.
  if(Stealth.swapBlocked()||Stealth.attackedThisTick())return;
  if(mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING))return;
  if(!replaceHeld.get()&&!mc.player.getOffhandItem().isEmpty())return;
  var inv=mc.player.getInventory();
  // 0..35 only: 36..39 are armour slots, not menu hotbar slots.
  for(int index=0;index<36;index++){
   int i=inventoryFirst.get()?(index+9)%36:index;
   if(!inv.getItem(i).is(Items.TOTEM_OF_UNDYING))continue;
   int menu=i<9?InventoryUtils.hotbarMenuSlot(i):i;
   if(InventoryUtils.swapPlayerSlots(mc,menu,InventoryUtils.offhandMenuSlot()))cooldown=refillTicks.get();
   return;
  }
 }
}
