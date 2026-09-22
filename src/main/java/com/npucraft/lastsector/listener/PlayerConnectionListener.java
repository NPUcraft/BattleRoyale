package com.npucraft.lastsector.listener;
import com.npucraft.lastsector.service.PluginRuntime;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
/** Disconnect policy delegates to the runtime; no session lifecycle logic lives in this listener. */
public final class PlayerConnectionListener implements Listener {
    private final PluginRuntime runtime;
    public PlayerConnectionListener(PluginRuntime runtime) { this.runtime = runtime; }
    @EventHandler public void quit(PlayerQuitEvent event) { runtime.rooms().disconnected(event.getPlayer().getUniqueId()); }
}

