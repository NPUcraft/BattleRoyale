package com.npucraft.lastsector.paper;
import com.npucraft.lastsector.spectator.SpectatorRegistry;
import com.npucraft.lastsector.player.*;
import com.npucraft.lastsector.loadout.LoadoutDefinition;
import com.npucraft.lastsector.session.*;
import com.npucraft.lastsector.service.MessageService;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.Supplier;
/** Presence is separate from immutable participant/team membership and from the original snapshot. */
public final class PaperSpectators {
    private final JavaPlugin plugin;private final PlayerIsolation<MatchPlayerSnapshot,LoadoutDefinition> isolation;
    private final Supplier<PaperMatches> matches;private final MessageService messages;
    private final SpectatorRegistry registry=new SpectatorRegistry();
    private record Pending(UUID session,Location location) {}
    private final Map<UUID,Pending> pending=new HashMap<>();
    public PaperSpectators(JavaPlugin plugin,PlayerIsolation<MatchPlayerSnapshot,LoadoutDefinition> isolation,Supplier<PaperMatches> matches,MessageService messages) {
        this.plugin=plugin;this.isolation=isolation;this.matches=matches;this.messages=messages;
    }
    public SpectatorRegistry registry(){return registry;}
    public void eliminated(GameSession session,UUID id) {
        Player p=plugin.getServer().getPlayer(id);
        if(p==null){isolation.defer(session.sessionId(),id);return;}
        pending.put(id,new Pending(session.sessionId(),p.getLocation().clone()));
    }
    public Location respawn(UUID id) {
        var request=pending.get(id);if(request==null)return null;
        var entry=matches.get().entry(request.session());
        return entry!=null && visible(entry.session)?safe(entry,request.location()):null;
    }
    public void afterRespawn(Player player) {
        var request=pending.remove(player.getUniqueId());if(request==null)return;
        var entry=matches.get().entry(request.session());
        if(entry==null || !visible(entry.session)){isolation.defer(request.session(),player.getUniqueId());isolation.retry(player.getUniqueId());return;}
        enter(player,entry,SpectatorRegistry.Kind.DEAD_PARTICIPANT,entry.session.sessionId(),safe(entry,request.location()));
    }
    public void external(Player player,GameSession session) {
        if(!session.room().allowExternalSpectators())throw new IllegalStateException("External spectators are disabled");
        if(!visible(session))throw new IllegalStateException("Only RUNNING or ENDING sessions may be watched");
        if(registry.find(player.getUniqueId()).isPresent() || pending.containsKey(player.getUniqueId()) || matches.get().participant(player.getUniqueId())!=null || isolation.blocked(player.getUniqueId()))throw new IllegalStateException("Leave your current match or spectator session first");
        UUID token=UUID.randomUUID();isolation.applyAsync(token,List.of(player.getUniqueId()),new LoadoutDefinition("spectator",Map.of(),0),()->player.isOnline() && visible(session)).whenComplete((ignored,error)->{
            if(error!=null){messages.send(player,"Spectator entry failed; original state retained.");return;}
            try{var entry=Objects.requireNonNull(matches.get().entry(session.sessionId()));enter(player,entry,SpectatorRegistry.Kind.EXTERNAL,token,safe(entry,null));}
            catch(RuntimeException failure){registry.remove(player.getUniqueId());isolation.end(token);messages.send(player,"Spectator entry failed; returning to Lobby.");}
        });
    }
    private boolean visible(GameSession session){return session.state()==GameState.RUNNING || session.state()==GameState.ENDING;}
    private Location safe(PaperMatches.Entry entry,Location requested) {
        World world=Objects.requireNonNull(plugin.getServer().getWorld(entry.session.gameWorld().orElseThrow().worldName()));
        if(requested!=null && requested.getWorld().getUID().equals(world.getUID()))return new Location(world,requested.getX(),Math.max(world.getMinHeight()+2,Math.min(world.getMaxHeight()-3,requested.getY())),requested.getZ());
        var metadata=entry.session.selectedMap().orElseThrow().metadata();if(metadata!=null && metadata.spectator()!=null){var p=metadata.spectator();return new Location(world,p.x(),Math.max(world.getMinHeight()+2,Math.min(world.getMaxHeight()-3,p.y())),p.z(),p.yaw(),p.pitch());}
        var zone=entry.session.zone().orElse(null);return zone==null?world.getSpawnLocation():new Location(world,zone.current().centerX(),Math.max(world.getMinHeight()+5,world.getSpawnLocation().getY()+10),zone.current().centerZ());
    }
    private void enter(Player p,PaperMatches.Entry entry,SpectatorRegistry.Kind kind,UUID snapshot,Location location) {
        registry.add(new SpectatorRegistry.Presence(p.getUniqueId(),entry.session.sessionId(),location.getWorld().getUID(),snapshot,kind));
        try {
            p.setItemOnCursor(null);p.closeInventory();p.getInventory().clear();p.setLevel(0);p.setExp(0);p.setTotalExperience(0);p.setFireTicks(0);p.setGameMode(GameMode.SPECTATOR);
            if(!p.teleport(location))throw new IllegalStateException("Spectator teleport rejected");
            if(kind==SpectatorRegistry.Kind.DEAD_PARTICIPANT)entry.session.spectating(p.getUniqueId(),true);
            registry.preferred(entry.session,p.getUniqueId()).map(id->matches.get().combatEntity(entry,id)).filter(Objects::nonNull).ifPresent(p::setSpectatorTarget);
            entry.session.zone().ifPresent(zone->entry.ui.render(p,zone,0,entry.session.activeCount(),0,entry.session.activeTeamCount(),true));
            messages.event(p,"spectator-joined",entry.session.room().id());
        } catch(RuntimeException failure){registry.remove(p.getUniqueId());isolation.defer(snapshot,p.getUniqueId());isolation.retry(p.getUniqueId());throw failure;}
    }
    public boolean leave(UUID id,boolean disconnect) {
        var presence=registry.remove(id);var waiting=pending.remove(id);
        if(presence==null && waiting==null)return false;
        UUID sessionId=presence!=null?presence.session():waiting.session();UUID snapshot=presence!=null?presence.snapshotOwner():sessionId;
        var entry=matches.get().entry(sessionId);if(entry!=null){entry.ui.detach(id);if(entry.session.players().containsKey(id))entry.session.spectating(id,false);}
        Player p=plugin.getServer().getPlayer(id);if(p!=null && p.getGameMode()==GameMode.SPECTATOR)p.setSpectatorTarget(null);
        isolation.defer(snapshot,id);if(!disconnect){isolation.retry(id);if(p!=null)messages.event(p,"spectator-left");}
        if(presence!=null && presence.kind()==SpectatorRegistry.Kind.EXTERNAL)isolation.end(snapshot);
        return true;
    }
    public boolean target(Player viewer,Entity target) {
        var presence=registry.find(viewer.getUniqueId()).orElse(null);if(presence==null)return true;
        var entry=matches.get().entry(presence.session());if(entry==null || !target.getWorld().getUID().equals(presence.world()))return false;
        UUID combatant=matches.get().combatIdentity(entry,target);return combatant!=null && registry.target(viewer.getUniqueId(),entry.session,combatant);
    }
    public void cleanup(GameSession session) {
        for(var presence:registry.session(session.sessionId()))leave(presence.player(),false);
        for(var id:List.copyOf(pending.keySet()))if(pending.get(id).session().equals(session.sessionId())){pending.remove(id);isolation.defer(session.sessionId(),id);}
    }
}
