package com.npucraft.battleroyale.listener;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.*;
/** A real tick-end boundary, never a guessed millisecond delay. */
public final class MatchTickListener implements Listener {
    private final PluginRuntime runtime;
    public MatchTickListener(PluginRuntime runtime) {this.runtime=runtime;}
    @EventHandler(priority=EventPriority.MONITOR) public void end(ServerTickEndEvent event) {runtime.matches().endTick(event.getTickNumber());}
}
