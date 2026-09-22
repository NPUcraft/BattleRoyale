package com.lastsector.paper;
import com.lastsector.config.ConfigurationSnapshot;
import com.lastsector.recovery.*;
import com.lastsector.storage.*;
import com.lastsector.map.*;
import com.lastsector.session.*;
import com.lastsector.player.*;
import com.lastsector.loadout.LoadoutDefinition;
import com.lastsector.service.RoomRuntimeService;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
/** Coordinates bootstrap/checkpoints; SQL, filesystem safety and runtime DTO translation are separate services. */
public final class PaperRecoveryCoordinator implements AutoCloseable {
    private final JavaPlugin plugin;private final ConfigurationSnapshot config;private final RecoveryStorage storage;private final PaperDurablePlayers players;
    private final PlayerIsolation<MatchPlayerSnapshot,LoadoutDefinition> isolation;private final WorldFiles files;private final OnDemandWorldProvider worlds;
    private final PaperMatches matches;private final RoomRuntimeService rooms;private final SessionManager sessions;private final RecoveryEntityCleaner cleaner;
    private final PaperRecoverySnapshots snapshots;private final SnapshotCodec codec=new SnapshotCodec();private final org.bukkit.scheduler.BukkitTask pump;
    private final Map<UUID,Long> revisions=new HashMap<>();private final Map<UUID,SessionRecoverySnapshot> known=new HashMap<>();private final Set<UUID> completing=new HashSet<>();
    private boolean ready,closing,housekeeping;private long lastCheckpoint,lastLease,lastHousekeeping;private int recovered,abandoned,orphans;private String bootstrap="CONNECTING";
    public PaperRecoveryCoordinator(JavaPlugin plugin,ConfigurationSnapshot config,RecoveryStorage storage,PaperDurablePlayers players,PlayerIsolation<MatchPlayerSnapshot,LoadoutDefinition> isolation,WorldFiles files,OnDemandWorldProvider worlds,PaperMatches matches,RoomRuntimeService rooms,SessionManager sessions,WorldSanitizer sanitizer,RecoveryEntityCleaner cleaner) {
        this.plugin=plugin;this.config=config;this.storage=storage;this.players=players;this.isolation=isolation;this.files=files;this.worlds=worlds;this.matches=matches;this.rooms=rooms;this.sessions=sessions;this.cleaner=cleaner;
        snapshots=new PaperRecoverySnapshots(plugin,players,sanitizer,files,matches::now);pump=plugin.getServer().getScheduler().runTaskTimer(plugin,this::tick,1,1);
    }
    public boolean idle(){return known.isEmpty() && completing.isEmpty();}
    public boolean ready(){return ready && !closing;}
    public void start(){storage.initialize().thenRun(()->claims(0)).exceptionally(error->{fatal("Storage/schema unavailable; runtime directories preserved");return null;});}
    private void claims(int attempt){
        bootstrap="CLAIMING";storage.call(()->{
            var rows=storage.repository().sessions();long now=System.currentTimeMillis();for(var row:rows)if(!storage.repository().claim(row.session(),storage.owner(),now,now+RecoveryStorage.LEASE_MILLIS))return (List<RecoveryRepository.Row>)null;
            return storage.repository().sessions();
        }).whenComplete((rows,error)->{
            if(closing)return;if(error!=null){fatal("Recovery database read failed; worlds preserved");return;}
            if(rows==null){if(attempt>=4){fatal("Recovery lease held by another instance; worlds preserved");return;}bootstrap="WAITING_FOR_LEASE";plugin.getServer().getScheduler().runTaskLater(plugin,()->claims(attempt+1),200);return;}
            bootstrap="RECOVERING";var duplicates=RecoveryPlan.duplicates(rows);
            storage.call(()->storage.repository().restores()).thenAccept(originals->{players.load(originals);recoverNext(rows,duplicates,0);}).exceptionally(failure->{fatal("Cannot read durable originals; worlds preserved");return null;});
        });
    }
    private record Candidate(SessionRecoverySnapshot snapshot,GameWorld world) {}
    private void recoverNext(List<RecoveryRepository.Row> rows,Set<UUID> duplicates,int index){
        if(closing)return;if(index==rows.size()){finishBootstrap();return;}var row=rows.get(index);
        storage.call(()->{
            if(duplicates.contains(row.session()))throw new RecoveryPlan.Rejected("DUPLICATE_ROOM_OR_WORLD");
            SessionRecoverySnapshot saved;try{saved=codec.decode(row.version(),row.payload(),row.checksum(),SessionRecoverySnapshot.class);}catch(RuntimeException failure){throw new RecoveryPlan.Rejected("SNAPSHOT_VERSION_CHECKSUM_OR_DTO");}
            if(!saved.sessionId().equals(row.session()) || !saved.roomId().equals(row.room()) || !saved.mapId().equals(row.map()) || !saved.worldName().equals(row.world()) || saved.revision()!=row.revision() || !saved.gameState().equals(row.state()))throw new RecoveryPlan.Rejected("METADATA_MISMATCH");
            if(!config.settings().recovery().enabled() || !Set.of("RUNNING","ENDING").contains(saved.gameState()))throw new RecoveryPlan.Rejected("DISABLED_OR_UNRECOVERABLE_PHASE");
            var room=config.rooms().stream().filter(r->r.id().equals(saved.roomId())).findFirst().orElseThrow();var map=config.maps().stream().filter(m->m.id().equals(saved.mapId())).findFirst().orElseThrow();var zone=config.zoneProfiles().stream().filter(z->z.id().equals(room.zoneProfileId())).findFirst().orElseThrow();
            if(!PaperRecoverySnapshots.rules(room,map,zone).equals(saved.rulesHash()))throw new RecoveryPlan.Rejected("CONFIGURATION_CHANGED");
            try{return new Candidate(saved,files.recovery(saved.sessionId(),saved.roomId(),map,saved.worldName(),saved.relativePath()));}catch(java.io.IOException failure){throw new RecoveryPlan.Rejected("OWNERSHIP_PATH_OR_WORLD_FILES");}
        }).whenComplete((candidate,error)->{
            if(closing)return;if(error!=null){plugin.getLogger().warning("Recovery rejected "+row.session()+": "+error.getMessage());abandon(row,null,()->recoverNext(rows,duplicates,index+1));return;}
            var saved=candidate.snapshot();GameSession session=null;boolean loadedByUs=false;
            try {
                for(var p:saved.participants())if(saved.gameState().equals("RUNNING") && Set.of("ALIVE","DISCONNECTED").contains(p.state()) && !players.hasOriginal(p.id(),saved.sessionId()))throw new IllegalStateException("Missing durable original for active participant");
                if(plugin.getServer().getWorlds().stream().anyMatch(w->w.getName().equals(candidate.world().worldName()) || w.getWorldFolder().toPath().toAbsolutePath().normalize().equals(candidate.world().runtimePath())))throw new RecoveryPlan.Rejected("WORLD_ALREADY_LOADED");
                worlds.recover(candidate.world());loadedByUs=true;var world=Objects.requireNonNull(plugin.getServer().getWorld(candidate.world().worldName()));cleaner.register(world);
                var room=config.rooms().stream().filter(r->r.id().equals(saved.roomId())).findFirst().orElseThrow();session=GameSession.recovered(saved,room,candidate.world());GameSession recoveredSession=session;
                matches.recover(session,saved,failure->{plugin.getLogger().severe("Recovered session runtime failed: "+saved.sessionId());if(ready)rooms.debugEnd(saved.roomId());});
                rooms.recover(session);known.put(saved.sessionId(),saved);revisions.put(saved.sessionId(),saved.revision());recovered++;
                if(saved.outcome()!=null)for(UUID id:saved.outcome().players())plugin.getLogger().fine("Recovered winner identity "+id);
                plugin.getLogger().info("Recovered "+saved.gameState()+" session "+saved.sessionId()+" room="+saved.roomId()+" revision="+saved.revision());
                recoverNext(rows,duplicates,index+1);
            }catch(Exception failure){plugin.getLogger().warning("Runtime reconstruction rejected "+row.session()+": "+failure.getClass().getSimpleName());if(session!=null)matches.discardRecovery(session);try{if(loadedByUs)worlds.preserve(candidate.world());}catch(Exception ignored){plugin.getLogger().warning("Failed recovery world remains loaded; deletion forbidden");}abandon(row,candidate.world(),()->recoverNext(rows,duplicates,index+1));}
        });
    }
    private void abandon(RecoveryRepository.Row row,GameWorld knownWorld,Runnable next){
        abandoned++;plugin.getLogger().severe("Abandoning unsafe recovery session "+row.session()+"; durable originals retained, owned world delayed for orphan cleanup");
        storage.call(()->{
            for(var owned:files.ownedChildren(plugin.getLogger()::warning))if(owned.world().sessionId().equals(row.session()))files.orphan(owned.world(),Instant.now(),"RECOVERY_FAILED");
            storage.repository().retire(row.session(),storage.owner(),"ABANDONED");return null;
        }).whenComplete((ignored,error)->{if(error!=null){fatal("Cannot safely persist abandonment; worlds retained");return;}next.run();});
    }
    private void finishBootstrap(){
        var active=new HashMap<UUID,Set<UUID>>();for(var entry:matches.allEntries())if(entry.session.state()==GameState.RUNNING)active.put(entry.session.sessionId(),entry.session.players().keySet().stream().filter(entry.session::combatActive).collect(java.util.stream.Collectors.toSet()));
        players.route(isolation,active);ready=true;matches.recoveryCompleted();bootstrap="READY";lastCheckpoint=System.nanoTime();lastLease=0;
        plugin.getLogger().info("Recovery bootstrap complete: recovered="+recovered+" abandoned="+abandoned+" durableOriginals="+players.pendingCount());housekeep();
    }
    private void fatal(String text){bootstrap="FAILED";plugin.getLogger().severe(text);plugin.getServer().getPluginManager().disablePlugin(plugin);}
    private void tick(){
        storage.pump();if(!ready())return;long now=System.nanoTime();boolean periodic=now-lastCheckpoint>=config.settings().recovery().checkpoint().toNanos();
        if(periodic){lastCheckpoint=now;storage.retry();players.retry();}
        for(var entry:matches.allEntries())if(periodic || entry.priorityCheckpoint){try{
            long revision=revisions.merge(entry.session.sessionId(),1L,Long::sum);var saved=snapshots.capture(entry,revision);var payload=codec.encode(saved);known.put(saved.sessionId(),saved);
            storage.checkpoint(new RecoveryRepository.Row(saved.sessionId(),saved.roomId(),saved.mapId(),saved.worldName(),saved.gameState(),saved.revision(),payload.version(),payload.json(),payload.checksum(),System.currentTimeMillis(),"ACTIVE"));entry.priorityCheckpoint=false;
        }catch(RuntimeException error){plugin.getLogger().severe("Recovery capture rejected for "+entry.session.sessionId()+": "+error.getClass().getSimpleName());entry.priorityCheckpoint=false;}}
        for(var saved:List.copyOf(known.values()))if(sessions.find(saved.sessionId()).isEmpty() && completing.add(saved.sessionId()))complete(saved);
        if(now-lastLease>=5_000_000_000L){lastLease=now;var ids=matches.allEntries().stream().map(e->e.session.sessionId()).toList();if(!ids.isEmpty())storage.call(()->{long wall=System.currentTimeMillis();for(UUID id:ids)storage.repository().claim(id,storage.owner(),wall,wall+RecoveryStorage.LEASE_MILLIS);return null;});}
        if(now-lastHousekeeping>=300_000_000_000L){lastHousekeeping=now;housekeep();}
    }
    private void complete(SessionRecoverySnapshot saved){
        storage.call(()->{for(var owned:files.ownedChildren(plugin.getLogger()::warning))if(owned.world().sessionId().equals(saved.sessionId()))files.orphan(owned.world(),Instant.now(),"CLEANUP_RETAINED");return null;})
            .thenCompose(ignored->storage.retire(saved.sessionId(),"COMPLETED")).whenComplete((ignored,error)->{completing.remove(saved.sessionId());if(error==null){known.remove(saved.sessionId());revisions.remove(saved.sessionId());}});
    }
    private void housekeep(){
        if(housekeeping || !storage.healthy())return;housekeeping=true;
        storage.call(()->files.ownedChildren(plugin.getLogger()::warning)).whenComplete((candidates,error)->{
            if(error!=null || closing){housekeeping=false;return;}
            // Fresh server-thread acknowledgement after discovery, before submitting deletion.
            // Our runtime loader never reuses these identities after bootstrap; new matches use fresh UUIDs.
            var loaded=plugin.getServer().getWorlds().stream().map(w->w.getWorldFolder().toPath().toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
            var runtimeIds=sessions.all().stream().map(GameSession::sessionId).collect(java.util.stream.Collectors.toSet());
            storage.call(()->{
                var referenced=storage.repository().sessions().stream().map(RecoveryRepository.Row::session).collect(java.util.stream.Collectors.toSet());referenced.addAll(runtimeIds);int count=0;
                for(var candidate:candidates){if(referenced.contains(candidate.world().sessionId()) || loaded.contains(candidate.world().runtimePath()))continue;
                    try{if(candidate.status().equals("ACTIVE")){files.orphan(candidate.world(),Instant.now(),"NO_ACTIVE_RECOVERY_ROW");count++;}
                    else if(!files.deleteOrphan(candidate,Instant.now(),config.settings().recovery().orphanAge(),loaded,referenced))count++;}
                    catch(java.io.IOException failure){count++;plugin.getLogger().warning("Orphan retained after safety/IO check: "+candidate.world().sessionId());}
                }return count;
            }).whenComplete((count,failure)->{housekeeping=false;if(failure==null)orphans=count;});
        });
    }
    public String diagnostics(){return "bootstrap="+bootstrap+" active="+known.size()+" recovered="+recovered+" abandoned="+abandoned+" orphanWorlds="+orphans+" pendingPlayerRestores="+players.pendingCount();}
    public String sessionDiagnostics(UUID id){return "recoveryRevision="+revisions.getOrDefault(id,0L)+" lastCheckpointRevision="+storage.written(id)+" degraded="+!storage.healthy();}
    public void close(){closing=true;pump.cancel();}
    /** Called after graceful runtime/file cleanup; queued originals were durable before gameplay began. */
    public void finishClose(){if(ready)for(var saved:List.copyOf(known.values()))storage.call(()->{for(var owned:files.ownedChildren(plugin.getLogger()::warning))if(owned.world().sessionId().equals(saved.sessionId()))files.orphan(owned.world(),Instant.now(),"GRACEFUL_TERMINATION");storage.repository().retire(saved.sessionId(),storage.owner(),"COMPLETED");return null;});storage.close();}
}
