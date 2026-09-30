package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
/** Restricts only registered BattleRoyale spectators. Ordinary spectator flight remains native. */
public final class SpectatorListener implements Listener {
    private final PluginRuntime runtime;
    public SpectatorListener(PluginRuntime runtime){this.runtime=runtime;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(PlayerStartSpectatingEntityEvent event){if(!runtime.spectators().target(event.getPlayer(),event.getNewSpectatorTarget()))event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event){
        runtime.spectators().registry().find(event.getPlayer().getUniqueId()).ifPresent(p->{if(event.getTo()==null || !event.getTo().getWorld().getUID().equals(p.world()))event.setCancelled(true);});
    }
}
