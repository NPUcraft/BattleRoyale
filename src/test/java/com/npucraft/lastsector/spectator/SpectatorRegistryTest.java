package com.npucraft.lastsector.spectator;
import com.npucraft.lastsector.TestSupport;
import com.npucraft.lastsector.session.*;
import com.npucraft.lastsector.map.GameWorld;
import com.npucraft.lastsector.room.RoomDefinition;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SpectatorRegistryTest {
    GameSession session() {
        var s=GameSession.waiting(UUID.randomUUID(),new RoomDefinition("r","r",1,4,2,Duration.ZERO,List.of("city"),"default","default",true),Instant.EPOCH);
        for(int i=0;i<4;i++)s.join(UUID.randomUUID());var map=TestSupport.map(Path.of("city"));s.prepare(map,new Random(4));s.starting(new GameWorld(s.sessionId(),"r","world",Path.of("runtime"),map));s.transition(GameState.RUNNING);return s;
    }
    @Test void sameSessionOnlyAndExternalDoesNotEnterParticipantCount() {
        var s=session();var other=session();UUID external=UUID.randomUUID();var registry=new SpectatorRegistry();
        registry.add(new SpectatorRegistry.Presence(external,s.sessionId(),UUID.randomUUID(),UUID.randomUUID(),SpectatorRegistry.Kind.EXTERNAL));
        assertEquals(4,s.players().size());assertEquals(4,s.activeCount());assertTrue(registry.target(external,s,s.players().keySet().iterator().next()));
        assertFalse(registry.target(external,other,other.players().keySet().iterator().next()));assertFalse(registry.target(external,s,UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->registry.add(registry.find(external).orElseThrow()));
    }
    @Test void deadSpectatorPrefersTeammateThenEnemyThenFreeCamera() {
        var s=session();var team=s.teams().values().iterator().next();var members=new ArrayList<>(team.playerIds());UUID viewer=members.getFirst(),mate=members.getLast();s.eliminate(viewer);s.spectating(viewer,true);
        var registry=new SpectatorRegistry();assertEquals(Optional.of(mate),registry.preferred(s,viewer));s.eliminate(mate);
        var target=registry.preferred(s,viewer).orElseThrow();assertFalse(team.playerIds().contains(target));
        s.players().keySet().stream().filter(s::combatActive).toList().forEach(s::eliminate);assertTrue(registry.preferred(s,viewer).isEmpty());
    }
    @Test void disconnectRemovesPresenceWithoutChangingTeamRoster() {
        var s=session();UUID id=s.players().keySet().iterator().next();s.eliminate(id);s.spectating(id,true);
        var registry=new SpectatorRegistry();registry.add(new SpectatorRegistry.Presence(id,s.sessionId(),UUID.randomUUID(),s.sessionId(),SpectatorRegistry.Kind.DEAD_PARTICIPANT));
        registry.remove(id);s.spectating(id,false);s.disconnected(id);
        assertTrue(registry.empty());assertEquals(com.npucraft.lastsector.player.PlayerState.ELIMINATED,s.players().get(id).state());assertEquals(4,s.teams().values().stream().mapToInt(t->t.playerIds().size()).sum());
    }
}
