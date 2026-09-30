package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.recovery.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.player.*;
import com.npucraft.battleroyale.zone.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Main-thread translation of owned runtime registries. Never scans the world to checkpoint. */
public final class PaperRecoverySnapshots {
    private final GameClock clock;private final JavaPlugin plugin;private final PaperDurablePlayers players;private final WorldSanitizer sanitizer;private final WorldFiles files;
    private final PaperBodySnapshots bodies=new PaperBodySnapshots(new NativeItemSerializer());
    public PaperRecoverySnapshots(JavaPlugin plugin,PaperDurablePlayers players,WorldSanitizer sanitizer,WorldFiles files,GameClock clock){this.clock=clock;this.plugin=plugin;this.players=players;this.sanitizer=sanitizer;this.files=files;}
    public static String rules(com.npucraft.battleroyale.room.RoomDefinition room,MapTemplate map,ZoneProfile zone){return SnapshotCodec.hash(room.toString()+map.id()+map.playableArea().toString()+zone.toString());}
    public SessionRecoverySnapshot capture(PaperMatches.Entry entry,long revision){
        long now=clock.nanoTime();var session=entry.session;var world=session.gameWorld().orElseGet(()->files.descriptor(session.sessionId(),session.room().id(),session.selectedMap().orElseThrow()));
        var offline=entry.offline.snapshotBodies();var participants=new ArrayList<SessionRecoverySnapshot.Participant>();
        for(var p:session.players().values()){
            var player=plugin.getServer().getPlayer(p.playerId());var body=offline.get(p.playerId());
            var current=body!=null?body.snapshot():p.state()==PlayerState.ALIVE && player!=null && !player.isDead()?bodies.player(player):null;
            String name=player!=null?player.getName():entry.combat!=null?entry.combat.name(p.playerId()):plugin.getServer().getOfflinePlayer(p.playerId()).getName();
            participants.add(new SessionRecoverySnapshot.Participant(p.playerId(),name==null?p.playerId().toString():name,p.state().name(),p.teamId().orElseThrow(),p.kills(),p.assists(),players.original(p.playerId()),current,body==null?0:body.remainingNanos(now)));
        }
        var teams=session.teams().values().stream().map(t->new SessionRecoverySnapshot.Team(t.teamId(),t.displayIndex(),t.playerIds())).toList();
        var outcome=session.outcome().map(o->new SessionRecoverySnapshot.Outcome(o.winnerIds(),o.winningTeamIds(),o.tie(),o.reason(),o.tick())).orElse(null);
        return new SessionRecoverySnapshot(1,session.sessionId(),session.room().id(),session.selectedMap().orElseThrow().id(),world.worldName(),world.runtimePath().getFileName().toString(),session.state().name(),revision,entry.combat==null?0:entry.combat.elapsedNanos(),rules(session.room(),world.template(),entry.profile),teams,participants,session.zone().map(ZoneRuntime::snapshot).orElse(null),session.protection().map(p->Math.max(0,Math.round(p.remaining(now)*1e9))).orElse(0L),entry.combat==null?List.of():entry.combat.tracker().snapshot(),entry.combat==null?List.of():entry.combat.boxes().snapshot(),sanitizer.blockKeys(entry.worldId),sanitizer.entityKeys(entry.worldId),entry.recoveredLootComplete || entry.loot!=null && entry.loot.state()==PaperLootRuntime.State.COMPLETE?"COMPLETE":entry.loot==null?"NOT_STARTED":entry.loot.state().name(),outcome,entry.showcase==null?0:entry.showcase.remainingNanos(),entry.progress==null?null:entry.progress.snapshot(),session.selectedMap().orElseThrow().metadata());
    }
}
