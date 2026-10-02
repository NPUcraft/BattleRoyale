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
import org.bukkit.inventory.BlockInventoryHolder;
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
    /** Only creative mode bypasses the match guard. The admin permission must not: server owners
     *  are also participants, and an OP bypass made every denial silently disappear for them. */
    private static boolean bypass(Player player){return player.getGameMode()==GameMode.CREATIVE;}
    private static void deny(Player player){player.sendActionBar(UiText.warning(player,"该容器或装备在比赛中无法使用。","This container or gear is unavailable during a match."));}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void opened(InventoryOpenEvent event){
        if(!(event.getPlayer() instanceof Player player)||bypass(player)||!running(player.getWorld()))return;
        // The shared death-box inventory is a plugin-held view, never a world container.
        if(event.getInventory().getHolder() instanceof PaperDeathBoxes.View)return;
        // Deny by default: only loot-bearing storage opens during a match. Block containers stay
        // limited to the loot chests and the landed airdrop barrel; entity holders (horses, donkeys,
        // the player-held ender chest view) and unknown containers all fail closed. Custom plugin
        // holders are neither, so in-match plugin UIs keep working.
        var holder=event.getInventory().getHolder();
        if(holder instanceof Entity){deny(event);return;}
        if(holder instanceof BlockState state){
            var type=state.getType();
            if(type==Material.CHEST||type==Material.TRAPPED_CHEST||type==Material.COPPER_CHEST)return;
            if(type==Material.BARREL&&state instanceof Container container&&container.getPersistentDataContainer().has(airdrop,PersistentDataType.STRING))return;
        } else if(!(holder instanceof BlockInventoryHolder)) {
            return;
        }
        deny(event);
    }
    /** Jukeboxes and beacons hold items without firing an open event; the ender chest is denied for symmetry. */
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void interactBlock(PlayerInteractEvent event){
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null)return;
        if(bypass(event.getPlayer())||!running(event.getClickedBlock().getWorld()))return;
        var type=event.getClickedBlock().getType();
        if(type==Material.JUKEBOX||type==Material.BEACON||type==Material.ENDER_CHEST){event.setCancelled(true);deny(event.getPlayer());}
    }
    private static void deny(InventoryOpenEvent event){
        if(event.getPlayer() instanceof Player player)deny(player);
        event.setCancelled(true);
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
