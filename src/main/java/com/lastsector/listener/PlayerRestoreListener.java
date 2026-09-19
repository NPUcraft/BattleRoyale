package com.lastsector.listener;

import com.lastsector.service.PluginRuntime;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Retry pending in-memory snapshots after login or vanilla respawn; no death/drop interception. */
public final class PlayerRestoreListener implements Listener {
    private final JavaPlugin plugin;
    private final PluginRuntime runtime;
    public PlayerRestoreListener(JavaPlugin plugin,PluginRuntime runtime) { this.plugin=plugin; this.runtime=runtime; }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent event) { runtime.restorePlayer(event.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) {
        var id=event.getPlayer().getUniqueId(); plugin.getServer().getScheduler().runTask(plugin,()->runtime.restorePlayer(id));
    }
}
