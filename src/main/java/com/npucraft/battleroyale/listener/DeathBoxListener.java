package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.paper.PaperDeathBoxes;
import com.npucraft.battleroyale.service.PluginRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

/** Conservative transfer allowlist; shared inventory is mutated only on the server thread. */
public final class DeathBoxListener implements Listener {
    private final PluginRuntime runtime;
    public DeathBoxListener(PluginRuntime runtime) { this.runtime=runtime; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent event) {
        for(var entry:runtime.matches().combatEntries()) if(entry.combat.boxes().visual(event.getRightClicked())!=null) {
            event.setCancelled(true); entry.combat.boxes().open(event.getRightClicked(),event.getPlayer()); return;
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactAt(PlayerInteractAtEntityEvent event) { interact(event); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent event) {
        for(var entry:runtime.matches().combatEntries()) if(entry.combat.boxes().visual(event.getEntity())!=null) {event.setCancelled(true);return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof PaperDeathBoxes.View view)) return;
        boolean previouslyCancelled=event.isCancelled(); event.setCancelled(true);
        if(previouslyCancelled || !(event.getWhoClicked() instanceof Player player) || !view.owner.allowed(view,player)) return;
        if(event instanceof InventoryCreativeEvent) return;
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT && event.getClick()!=ClickType.SHIFT_LEFT && event.getClick()!=ClickType.SHIFT_RIGHT) return;
        if(event.getClickedInventory()==event.getView().getBottomInventory()) {
            // Normal bottom inventory rearrangement cannot address the box. Transfers and collection stay blocked.
            if(!event.isShiftClick() && switch(event.getAction()) {
                case PICKUP_ALL,PICKUP_HALF,PICKUP_ONE,PICKUP_SOME,PLACE_ALL,PLACE_ONE,PLACE_SOME,SWAP_WITH_CURSOR,NOTHING -> true;
                default -> false;
            }) event.setCancelled(false);
            return;
        }
        int slot=event.getRawSlot(); if(slot<0 || slot>=54) return;
        ItemStack item=view.inventory.getItem(slot); if(item==null || item.getType().isAir()) return;
        if(event.isShiftClick()) {
            ItemStack copy=item.clone(); view.inventory.setItem(slot,null);
            var remaining=player.getInventory().addItem(copy);
            if(!remaining.isEmpty()) view.inventory.setItem(slot,remaining.values().iterator().next());
        } else {
            ItemStack cursor=player.getItemOnCursor(); boolean empty=cursor==null || cursor.getType().isAir();
            if(!empty && !cursor.isSimilar(item)) return;
            int current=empty?0:cursor.getAmount(),capacity=item.getMaxStackSize()-current;
            int take=Math.min(capacity,event.getClick()==ClickType.RIGHT?(item.getAmount()+1)/2:item.getAmount()); if(take<=0) return;
            ItemStack result=item.clone(); result.setAmount(current+take);
            int left=item.getAmount()-take;
            if(left==0) view.inventory.setItem(slot,null); else { ItemStack rest=item.clone();rest.setAmount(left);view.inventory.setItem(slot,rest); }
            player.setItemOnCursor(result);
        }
        var entry=runtime.matches().entry(view.box.sessionId());if(entry!=null)entry.changed();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof PaperDeathBoxes.View) event.setCancelled(true);
    }
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof PaperDeathBoxes.View view){var entry=runtime.matches().entry(view.box.sessionId());if(entry!=null)entry.changed();}
    }
}
