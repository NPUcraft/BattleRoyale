package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.service.PluginRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.entity.*;
/** Protect pending login recovery and frozen rosters until their player snapshots are authoritative. */
public final class PreparationFreezeListener implements Listener {
    private final PluginRuntime runtime;
    public PreparationFreezeListener(PluginRuntime runtime){this.runtime=runtime;}
    private boolean loginPending(Player player){return !runtime.recoveryReady()||runtime.lobby().deferredJoin(player.getUniqueId());}
    private boolean frozen(Player player){return loginPending(player)||runtime.matches().frozen(player.getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent event){
        // Keep pending logins still, but allow server/plugin teleports that restore an authoritative snapshot.
        if(!(event instanceof PlayerTeleportEvent)&&loginPending(event.getPlayer())){
            var target=event.getTo().clone();target.setX(event.getFrom().getX());target.setY(event.getFrom().getY());target.setZ(event.getFrom().getZ());event.setTo(target);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void launch(ProjectileLaunchEvent event){if(event.getEntity().getShooter() instanceof Player player&&frozen(player))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void enter(org.bukkit.event.vehicle.VehicleEnterEvent event){if(event.getEntered() instanceof Player player&&frozen(player))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event){if(event.getWhoClicked() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void use(PlayerInteractEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void entity(PlayerInteractEntityEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityAt(PlayerInteractAtEntityEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void armorStand(PlayerArmorStandManipulateEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);if(event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(org.bukkit.event.block.BlockBreakEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(org.bukkit.event.block.BlockPlaceEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent event){if(frozen(event.getPlayer()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void food(FoodLevelChangeEvent event){if(event.getEntity() instanceof Player p && frozen(p))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent event){if(frozen(event.getPlayer()) && !event.getMessage().equalsIgnoreCase("/br team"))event.setCancelled(true);}
}
