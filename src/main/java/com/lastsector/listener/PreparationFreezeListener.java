package com.lastsector.listener;
import com.lastsector.service.PluginRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.entity.*;
/** Frozen roster owns its lobby snapshot already. Prevent moving match items before safe landing. */
public final class PreparationFreezeListener implements Listener {
    private final PluginRuntime runtime;
    public PreparationFreezeListener(PluginRuntime runtime){this.runtime=runtime;}
    private boolean frozen(Player player){return runtime.matches().frozen(player.getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getWhoClicked() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void entity(PlayerInteractEntityEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);if(event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(org.bukkit.event.block.BlockBreakEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(org.bukkit.event.block.BlockPlaceEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void food(FoodLevelChangeEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent event){if(frozen(event.getPlayer()) && !event.getMessage().equalsIgnoreCase("/ls team"))event.setCancelled(true);}
}
