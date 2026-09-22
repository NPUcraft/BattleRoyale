package com.lastsector.paper;
import com.lastsector.offline.*;
import com.lastsector.combat.*;
import com.lastsector.config.DisconnectSettings;
import com.lastsector.death.*;
import com.lastsector.player.PlayerState;
import com.lastsector.session.GameState;
import com.lastsector.service.MessageService;
import com.lastsector.zone.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.Consumer;
/** One registry per session. Driven by the session loop, never a repeating task per body. */
public final class PaperOfflineBodies {
    private final JavaPlugin plugin;private final PaperMatches.Entry entry;private final DisconnectSettings settings;private final GameClock clock;
    private final PaperBodySnapshots snapshots;private final VillagerBodyRepresentation representation;private final MessageService messages;
    private final Consumer<UUID> restoreLobby;private final Map<UUID,OfflineBody> bodies=new LinkedHashMap<>();private final Map<UUID,UUID> entities=new HashMap<>();
    public PaperOfflineBodies(JavaPlugin plugin,PaperMatches.Entry entry,DisconnectSettings settings,GameClock clock,PaperBodySnapshots snapshots,MessageService messages,Consumer<UUID> restoreLobby) {
        this.plugin=plugin;this.entry=entry;this.settings=settings;this.clock=clock;this.snapshots=snapshots;this.messages=messages;this.restoreLobby=restoreLobby;representation=new VillagerBodyRepresentation(plugin,snapshots);
    }
    public java.util.Map<UUID,OfflineBody> snapshotBodies(){for(var body:bodies.values())if(body.active() && representation.valid(body))body.snapshot(representation.capture(body));return java.util.Map.copyOf(bodies);}
    public void recover(UUID player,String name,BodySnapshot state,long remainingNanos){
        var participant=entry.session.players().get(player);var body=new OfflineBody(player,entry.session.sessionId(),participant.teamId().orElseThrow(),name,state,clock.nanoTime(),java.time.Duration.ofNanos(remainingNanos));
        if(bodies.putIfAbsent(player,body)!=null)throw new IllegalStateException("Duplicate body");entry.session.disconnected(player);entry.session.offlineCombatant(player,true); // Spawn only after all rooms and durable originals are ready.
    }
    public OfflineBody find(UUID player){return bodies.get(player);}
    public OfflineBody entity(Entity entity){UUID id=entities.get(entity.getUniqueId());var body=bodies.get(id);return body!=null && representation.marked(entity,body)?body:null;}
    public LivingEntity carrier(UUID player){var body=bodies.get(player);return body==null?null:representation.carrier(body);}
    public void disconnect(Player p) {
        var participant=entry.session.players().get(p.getUniqueId());
        if(participant==null || !Set.of(PlayerState.ALIVE,PlayerState.WAITING).contains(participant.state()) || bodies.containsKey(p.getUniqueId()))return;
        BodySnapshot captured=snapshots.player(p);
        OfflineBody body=new OfflineBody(p.getUniqueId(),entry.session.sessionId(),participant.teamId().orElseThrow(),p.getName(),captured,clock.nanoTime(),settings.reconnectWindow());
        entry.changed();bodies.put(p.getUniqueId(),body);entry.session.disconnected(p.getUniqueId());entry.session.offlineCombatant(p.getUniqueId(),true);
        // playerdata must not remain another source of carried match items.
        snapshots.quarantine(p);
        if(entry.session.state()==GameState.RUNNING)spawn(body);
        for(UUID id:entry.session.players().keySet()){var viewer=plugin.getServer().getPlayer(id);if(viewer!=null)messages.event(viewer,"disconnected",p.getName(),settings.reconnectWindow().toSeconds());}
    }
    public void planned(UUID player,Location location){var body=bodies.get(player);if(body!=null && body.state()==OfflineBody.State.RESERVED)body.snapshot(body.snapshot().at(PaperBodySnapshots.position(location)));}
    public void started(){for(var body:List.copyOf(bodies.values()))spawn(body);}
    private void spawn(OfflineBody body) {
        if(!body.active())return;
        if(body.expired(clock.nanoTime())){eliminate(body,DamageOrigin.DISCONNECT_TIMEOUT,null);return;}
        try{body.spawned(representation.spawn(body));representation.entityIds(body).forEach(id->entities.put(id,body.player()));}
        catch(RuntimeException failure){messages.runtimeError("OfflineBody spawn failed for "+body.player(),failure);eliminate(body,DamageOrigin.DISCONNECT_BODY_FAILURE,null);}
    }
    public boolean reconnect(Player p) {
        OfflineBody body=bodies.get(p.getUniqueId());if(body==null)return false;
        snapshots.quarantine(p);
        if(entry.session.state()!=GameState.RUNNING){
            if(entry.session.state()==GameState.PREPARING || entry.session.state()==GameState.STARTING)return true; // Remain frozen until planned landing.
            restoreLobby.accept(p.getUniqueId());return true;
        }
        if(body.expired(clock.nanoTime())){eliminate(body,DamageOrigin.DISCONNECT_TIMEOUT,null);restoreLobby.accept(p.getUniqueId());return true;}
        if(!representation.valid(body)){eliminate(body,DamageOrigin.DISCONNECT_BODY_FAILURE,null);restoreLobby.accept(p.getUniqueId());return true;}
        body.snapshot(representation.capture(body));if(!body.beginReconnect(clock.nanoTime()))return true;
        try {
            snapshots.restore(p,body.snapshot());
            if(!entry.session.reconnect(p.getUniqueId()))throw new IllegalStateException("Reconnect state changed");
            body.restored();entry.changed();remove(body);messages.event(p,"reconnected");
        } catch(RuntimeException error){
            snapshots.quarantine(p);if(body.state()==OfflineBody.State.RESTORING)body.restoreFailed();
            messages.runtimeError("Reconnect restore failed; body retained for "+p.getUniqueId(),error);
            p.kick(messages.reconnectFailure());
        }
        return true;
    }
    public void tick(long tick,boolean zonePulse) {
        if(!zonePulse && tick%10!=0 && (!settings.mobAggro() || tick%settings.mobInterval()!=0))return;
        long now=clock.nanoTime();
        for(var body:List.copyOf(bodies.values())) {
            if(!body.active())continue;
            if(body.expired(now)){eliminate(body,DamageOrigin.DISCONNECT_TIMEOUT,null);continue;}
            if(!representation.valid(body)){eliminate(body,DamageOrigin.DISCONNECT_BODY_FAILURE,null);continue;}
            body.snapshot(representation.capture(body));
            if(zonePulse){var at=body.snapshot().position();var zone=entry.session.zone().orElseThrow();double damage=ZoneDamage.amount(zone.current(),at.x(),at.z(),zone.stage());
                if(damage>=body.snapshot().health()){eliminate(body,DamageOrigin.ZONE,null);continue;}
                if(damage>0)representation.health(body,body.snapshot().health()-damage);
            }
            if(settings.mobAggro() && tick%settings.mobInterval()==0)aggro(body);
            Player waiting=plugin.getServer().getPlayer(body.player());if(waiting!=null && waiting.isOnline())reconnect(waiting);
        }
    }
    private void aggro(OfflineBody body) {
        LivingEntity carrier=representation.carrier(body);int count=0;
        for(Entity nearby:carrier.getNearbyEntities(settings.mobRadius(),settings.mobRadius(),settings.mobRadius())){
            if(++count>128)break;
            if(nearby instanceof Monster mob && (mob.getTarget()==null || !mob.getTarget().isValid()))mob.setTarget(carrier);
        }
    }
    public void eliminate(OfflineBody body,DamageOrigin cause,UUID attacker) {
        if(!body.active() || entry.session.state()!=GameState.RUNNING)return;
        if(representation.valid(body))body.snapshot(representation.capture(body));
        if(!body.eliminate())return;entry.changed();
        messages.offlineResult(body.player(),cause==DamageOrigin.DISCONNECT_TIMEOUT);
        if(cause==DamageOrigin.DISCONNECT_TIMEOUT)for(UUID id:entry.session.players().keySet()){Player viewer=plugin.getServer().getPlayer(id);if(viewer!=null)messages.event(viewer,"offline-timeout",body.name());}
        var state=body.snapshot();var at=state.position();var world=plugin.getServer().getWorld(at.world());
        var position=new DeathPosition(at.world(),at.x(),at.y(),at.z());if(world!=null)position=position.visible(world.getMinHeight(),world.getMaxHeight());
        try{entry.combat.eliminate(new EliminationRequest(body.player(),body.name(),position,cause,attacker,state.carriedItems(),state.totalXp(),clock.nanoTime(),Bukkit.getCurrentTick()));}
        finally{remove(body);restoreLobby.accept(body.player());}
    }
    public void death(OfflineBody body,org.bukkit.damage.DamageSource source,PaperDamageProvenance provenance){
        if(body.active())body.snapshot(representation.capture(body));
        eliminate(body,provenance.origin(source),provenance.attacker(source,entry));
    }
    private void remove(OfflineBody body){representation.entityIds(body).forEach(entities::remove);representation.remove(body);bodies.remove(body.player());}
    public void ending(){for(var body:List.copyOf(bodies.values())){body.retire();entry.session.offlineCombatant(body.player(),false);remove(body);restoreLobby.accept(body.player());}}
    public String diagnostics(){return "offline="+(bodies.isEmpty()?"none":bodies.values().stream().map(b->"player="+b.name()+" id="+b.player()+" entity="+b.representation()+" state="+b.state()+" health="+(representation.valid(b)?representation.capture(b).health():b.snapshot().health())+" location="+b.snapshot().position()+" remaining="+b.remainingNanos(clock.nanoTime())/1e9).toList());}
    public void close(){ending();}
}
