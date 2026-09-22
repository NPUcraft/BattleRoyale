package com.npucraft.lastsector.paper;

import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.PortalCreateEvent;

/** Portal-only restrictions. Pearls, chorus, terrain, time, weather and natural spawns stay vanilla. */
public final class WorldRules implements Listener {
    private final WorldSanitizer worlds;
    public WorldRules(WorldSanitizer worlds) { this.worlds=worlds; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void player(PlayerPortalEvent event) {
        if(worlds.active(event.getFrom().getWorld().getUID()) || event.getTo()!=null && worlds.active(event.getTo().getWorld().getUID())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event) {
        if(event.getCause()!=PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && event.getCause()!=PlayerTeleportEvent.TeleportCause.END_PORTAL
                && event.getCause()!=PlayerTeleportEvent.TeleportCause.END_GATEWAY) return;
        if(worlds.active(event.getFrom().getWorld().getUID()) || event.getTo()!=null && worlds.active(event.getTo().getWorld().getUID())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(EntityPortalEvent event) {
        if(worlds.active(event.getFrom().getWorld().getUID()) || event.getTo()!=null && worlds.active(event.getTo().getWorld().getUID())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void create(PortalCreateEvent event) {
        if(worlds.active(event.getWorld().getUID())) event.setCancelled(true);
    }
}
