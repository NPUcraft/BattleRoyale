package com.npucraft.battleroyale.listener;

import com.npucraft.battleroyale.loot.StorageGuardPolicy;
import com.npucraft.battleroyale.paper.PaperDeathBoxes;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.session.GameState;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Live-match defence in depth: no guarded container or item-bearing entity is reachable.
 *  Chests stay openable for automatic container loot, and air-drop barrels and death boxes stay openable. */
public final class StorageGuardListener implements Listener {
    private final PluginRuntime runtime;
    private final NamespacedKey airdrop;
    public StorageGuardListener(JavaPlugin plugin,PluginRuntime runtime){this.runtime=runtime;airdrop=new NamespacedKey(plugin,StorageGuardPolicy.AIRDROP_KEY);}
    private boolean running(World world){
        var matches=runtime.matches();if(matches==null)return false;
        for(var entry:matches.allEntries())if(entry.session.state()==GameState.RUNNING&&entry.inWorld(world.getUID()))return true;
        return false;
    }
    private static boolean bypass(Player player){return player.getGameMode()==GameMode.CREATIVE||player.hasPermission("battleroyale.admin");}
    private static void deny(Player player){player.sendActionBar(UiText.warning(player,"该容器或装备在比赛中无法使用。","This container or gear is unavailable during a match."));}
    private static Block block(Inventory inventory){
        var holder=inventory.getHolder();
        if(holder instanceof BlockState state)return state.getBlock();
        if(holder instanceof DoubleChest chest&&chest.getLeftSide() instanceof BlockState state)return state.getBlock();
        return null;
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void opened(InventoryOpenEvent event){
        if(!(event.getPlayer() instanceof Player player)||bypass(player)||!running(player.getWorld()))return;
        // The shared death-box inventory is a plugin-held view, never a world container.
        if(event.getInventory().getHolder() instanceof PaperDeathBoxes.View)return;
        Block block=block(event.getInventory());
        if(block==null||!StorageGuardPolicy.blockedContainer(block.getType().name()))return;
        // A landed supply drop is the only barrel players are meant to open.
        if(block.getType()==Material.BARREL&&block.getState() instanceof Container container&&container.getPersistentDataContainer().has(airdrop,PersistentDataType.STRING))return;
        event.setCancelled(true);deny(player);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent event){
        if(bypass(event.getPlayer())||!running(event.getRightClicked().getWorld()))return;
        event.setCancelled(true);deny(event.getPlayer());
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
        event.setCancelled(true);deny(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void placeEntity(EntityPlaceEvent event){
        if(event.getPlayer()==null||bypass(event.getPlayer())||!running(event.getEntity().getWorld()))return;
        if(!StorageGuardPolicy.blockedEntityPlacement(event.getEntity().getType().name()))return;
        event.setCancelled(true);deny(event.getPlayer());
    }
}
