package com.npucraft.battleroyale.listener;

import com.npucraft.battleroyale.loot.StorageGuardPolicy;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.session.GameState;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Live-match defence: map-native containers are emptied at world load (WorldSanitizer), so all
 *  container interaction stays legal during a match (smithing tables, furnaces, barrels...). Only
 *  the ender chest is denied: its contents are per-player global storage, which would smuggle loot
 *  across matches. Placement of storage blocks and item-bearing entities is still blocked. */
public final class StorageGuardListener implements Listener {
    private final PluginRuntime runtime;
    private final NamespacedKey airdrop;
    public StorageGuardListener(JavaPlugin plugin,PluginRuntime runtime){this.runtime=runtime;airdrop=new NamespacedKey(plugin,StorageGuardPolicy.AIRDROP_KEY);}
    private boolean running(World world){
        var matches=runtime.matches();if(matches==null)return false;
        for(var entry:matches.allEntries())if(entry.session.state()==GameState.RUNNING&&entry.inWorld(world.getUID()))return true;
        return false;
    }
    /** Only creative mode bypasses the match guard. The admin permission must not: server owners
     *  are also participants, and an OP bypass made every denial silently disappear for them. */
    private static boolean bypass(Player player){return player.getGameMode()==GameMode.CREATIVE;}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void interactBlock(PlayerInteractEvent event){
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null)return;
        if(bypass(event.getPlayer())||!running(event.getClickedBlock().getWorld()))return;
        if(event.getClickedBlock().getType()==Material.ENDER_CHEST){
            event.setCancelled(true);
            event.getPlayer().sendActionBar(UiText.warning(event.getPlayer(),"末影箱在比赛中无法使用。","Ender chests are unavailable during a match."));
        }
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent event){
        if(bypass(event.getPlayer())||!running(event.getRightClicked().getWorld()))return;
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void interact(PlayerInteractEntityEvent event){
        if(bypass(event.getPlayer())||!running(event.getRightClicked().getWorld()))return;
        if(!StorageGuardPolicy.removedEntity(event.getRightClicked().getType().name()))return;
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void interactAt(PlayerInteractAtEntityEvent event){interact(event);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event){
        if(!(event.getDamager() instanceof Player player)||bypass(player)||!running(event.getEntity().getWorld()))return;
        if(!StorageGuardPolicy.removedEntity(event.getEntity().getType().name()))return;
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void hanging(HangingBreakByEntityEvent event){
        if(!(event.getRemover() instanceof Player player)||bypass(player)||!running(event.getEntity().getWorld()))return;
        if(!StorageGuardPolicy.removedEntity(event.getEntity().getType().name()))return;
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void place(BlockPlaceEvent event){
        if(bypass(event.getPlayer())||!running(event.getBlock().getWorld()))return;
        if(!StorageGuardPolicy.blockedPlacement(event.getBlockPlaced().getType().name()))return;
        event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void placeEntity(EntityPlaceEvent event){
        if(event.getPlayer()==null||bypass(event.getPlayer())||!running(event.getEntity().getWorld()))return;
        if(!StorageGuardPolicy.blockedEntityPlacement(event.getEntity().getType().name()))return;
        event.setCancelled(true);
    }
}
