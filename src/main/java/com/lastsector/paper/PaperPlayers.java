package com.lastsector.paper;
import com.lastsector.map.GameWorld;
import com.lastsector.service.*;
import org.bukkit.Server;
import org.bukkit.World;
import java.util.*;
/** UUID-based player adapter. Never retains Player or World objects across calls. */
public final class PaperPlayers implements PlayerGateway {
    private final Server server;
    private final String lobby;
    private final MessageService messages;
    public PaperPlayers(Server server, String lobby, MessageService messages) {
        this.server = server; this.lobby = lobby; this.messages = messages;
        lobby();
    }
    public World lobby() {
        if (!server.isPrimaryThread()) throw new IllegalStateException("Player API requires server thread");
        World world = server.getWorld(lobby);
        if (world == null) throw new IllegalStateException("Configured lobby world does not exist: " + lobby);
        return world;
    }
    @Override public boolean toLobby(Collection<UUID> ids) {
        boolean success = move(ids, lobby());
        if (success) notify(ids, "returned");
        return success;
    }
    private boolean move(Collection<UUID> ids, World world) {
        boolean success = true;
        for (UUID id : ids) {
            var player = server.getPlayer(id);
            if (player != null && player.isOnline()) {
                // Isolation may already have returned this player and restored a held cursor item.
                // Re-teleporting would close that cursor again (and can drop it from a full inventory).
                if(player.getWorld().getUID().equals(world.getUID()) && player.getLocation().distanceSquared(world.getSpawnLocation())<.0001) continue;
                try { if (!player.teleport(world.getSpawnLocation())) success = false; }
                catch (Exception failure) { error("Teleport failed for " + id, failure); success = false; }
            }
        }
        return success;
    }
    @Override public void notify(Collection<UUID> ids, String event, Object... arguments) {
        for (UUID id : ids) {
            var player = server.getPlayer(id);
            if (player != null) messages.event(player, event, arguments);
        }
    }
    @Override public void error(String context, Throwable error) { messages.runtimeError(context, error); }
}

