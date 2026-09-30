package com.npucraft.battleroyale.listener;

import com.npucraft.battleroyale.service.PluginRuntime;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Route pending eliminated players to the lobby, then restore once after vanilla respawn. */
public final class PlayerRestoreListener implements Listener {
    private final JavaPlugin plugin;
    private final PluginRuntime runtime;
    public PlayerRestoreListener(JavaPlugin plugin,PluginRuntime runtime) { this.plugin=plugin; this.runtime=runtime; }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent event) { runtime.joined(event.getPlayer()); }
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent event) {
        var location=runtime.spectators().respawn(event.getPlayer().getUniqueId());
        if(location!=null)event.setRespawnLocation(location);
        else if(runtime.pendingRestore(event.getPlayer().getUniqueId())) event.setRespawnLocation(runtime.lobbySpawn());
        var id=event.getPlayer().getUniqueId(); plugin.getServer().getScheduler().runTask(plugin,()->{var player=plugin.getServer().getPlayer(id);if(player!=null){runtime.spectators().afterRespawn(player);runtime.restorePlayer(id);}});
    }
}
