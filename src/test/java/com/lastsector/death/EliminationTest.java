package com.lastsector.death;
import com.lastsector.TestSupport;
import com.lastsector.combat.*;
import com.lastsector.config.CombatSettings;
import com.lastsector.loadout.StoredItem;
import com.lastsector.map.GameWorld;
import com.lastsector.player.PlayerState;
import com.lastsector.room.RoomDefinition;
import com.lastsector.session.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EliminationTest {
    UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),world=UUID.randomUUID(); long now;
    int notifications;
    GameSession session=session(1);
    CombatTracker tracker=new CombatTracker(Set.of(a,b,c),CombatSettings.DEFAULT,()->now);
    EliminationService service=new EliminationService(session,tracker,0,box->notifications++);
    GameSession session(int teams) {
        var room=new RoomDefinition("r","r",1,8,teams,Duration.ZERO,List.of("city"),"default","default",false);
        var s=GameSession.waiting(UUID.randomUUID(),room,Instant.EPOCH);for(UUID id:List.of(a,b,c))s.join(id);
        var map=TestSupport.map(Path.of("city"));s.prepare(map);s.starting(new GameWorld(s.sessionId(),"r","world",Path.of("runtime"),map));s.transition(GameState.RUNNING);return s;
    }
    EliminationRequest request(UUID victim,long tick) {return new EliminationRequest(victim,"Victim",new DeathPosition(world,0,64,0),DamageOrigin.FALL,null,List.of(),101,now,tick);}
    @Test void uniqueCommitBoxStatsNotificationAndState() {
        tracker.record(b,a,5,DamageOrigin.PROJECTILE,true);tracker.record(b,c,4,DamageOrigin.PLAYER_MELEE,true);
        var box=service.eliminate(request(b,100)).orElseThrow();assertTrue(service.eliminate(request(b,100)).isEmpty());
        assertEquals(1,notifications);assertEquals(1,service.boxes().size());assertEquals(50,box.storedXp());
        assertEquals(PlayerState.ELIMINATED,session.players().get(b).state());assertEquals(1,session.players().get(c).kills());assertEquals(1,session.players().get(a).assists());
        assertEquals(0,tracker.size(b));session.disconnected(b);assertEquals(PlayerState.ELIMINATED,session.players().get(b).state());
    }
    @Test void visualFailureCannotDuplicateLogicalPayloadOrStats() {
        var failing=new EliminationService(session,tracker,0,box->{throw new IllegalStateException("visual");});
        assertThrows(IllegalStateException.class,()->failing.eliminate(request(b,10)));assertEquals(1,failing.boxes().size());
        assertTrue(failing.eliminate(request(b,10)).isEmpty());
    }
    @Test void payloadCopiesBeforeCallerMutationAndClampsVoidVisualY() {
        var items=new ArrayList<>(List.of(new StoredItem("paper-native",1,"AQID")));
        var request=new EliminationRequest(b,"B",new DeathPosition(world,0,-1000,0).visible(-64,320),DamageOrigin.VOID,a,items,0,0,1);items.clear();
        var box=service.eliminate(request).orElseThrow();assertEquals(1,box.contents().size());assertEquals(-62,box.location().y());assertThrows(UnsupportedOperationException.class,()->box.contents().clear());
    }
    @Test void endingRejectsEliminationAndImmutableOutcomeCannotChange() {
        session.outcome(new MatchOutcome(Set.of(a),false,"winner",0,1));assertTrue(service.eliminate(request(b,1)).isEmpty());
        assertThrows(IllegalStateException.class,()->session.outcome(new MatchOutcome(Set.of(b),false,"other",1,2)));
        assertThrows(UnsupportedOperationException.class,()->session.outcome().orElseThrow().winnerIds().clear());
    }
    @Test void sameTickFinalBatchTiesOnlyAtBoundary() {
        service.eliminate(request(c,99)); service.eliminate(request(a,100));
        assertEquals(GameState.RUNNING,session.state());service.eliminate(request(b,100));
        var result=new SoloOutcomeResolver().resolve(session,service.eliminationTicks(),100,0).orElseThrow();assertTrue(result.tie());assertEquals(Set.of(a,b),result.winnerIds());
    }
    @Test void differentTicksDoNotFabricateTieAndRemainingPlayerWins() {
        service.eliminate(request(b,100));assertTrue(new SoloOutcomeResolver().resolve(session,service.eliminationTicks(),100,0).isEmpty());
        service.eliminate(request(c,101));var result=new SoloOutcomeResolver().resolve(session,service.eliminationTicks(),101,0).orElseThrow();assertEquals(Set.of(a),result.winnerIds());assertFalse(result.tie());
    }
    @Test void delayedCallbackNeverCombinesDeathsFromDifferentTicks() {
        service.eliminate(request(c,99));service.eliminate(request(a,100));service.eliminate(request(b,101));
        var result=new SoloOutcomeResolver().resolve(session,service.eliminationTicks(),101,0).orElseThrow();assertFalse(result.tie());assertTrue(result.winnerIds().isEmpty());
    }
    @Test void teamRoomNeverUsesSoloResolver() {
        var team=session(4);team.eliminate(b);team.eliminate(c);assertTrue(new SoloOutcomeResolver().resolve(team,Map.of(b,1L,c,1L),1,0).isEmpty());
    }
    @Test void disconnectedContendersDoNotGiveFreeWinsBeforeM6() {session.disconnected(b);service.eliminate(request(c,1));assertTrue(new SoloOutcomeResolver().resolve(session,service.eliminationTicks(),1,0).isEmpty());}
    @Test void boxAccessRechecksSessionStateMembershipWorldAndDistance() {
        var box=service.eliminate(request(b,1)).orElseThrow();var near=new DeathPosition(world,6,64,0);
        assertTrue(DeathBoxAccess.allowed(session,box,a,near,6));assertFalse(DeathBoxAccess.allowed(session,box,b,near,6));
        assertFalse(DeathBoxAccess.allowed(session,box,a,new DeathPosition(world,6.01,64,0),6));
        assertFalse(DeathBoxAccess.allowed(session,box,a,new DeathPosition(UUID.randomUUID(),0,64,0),6));
        assertFalse(DeathBoxAccess.allowed(session(1),box,a,near,6));assertFalse(DeathBoxAccess.allowed(session,box,UUID.randomUUID(),near,6));
        session.outcome(new MatchOutcome(Set.of(a),false,"winner",0,1));assertFalse(DeathBoxAccess.allowed(session,box,a,near,6));
    }
    @Test void offlineTimeoutUsesRecentCombatAndCommitsOnlyOnePayload() {
        session.disconnected(b);session.offlineCombatant(b,true);
        tracker.record(b,a,5,DamageOrigin.PROJECTILE,true);
        now=15_000_000_000L;
        var item=new StoredItem("paper-native",1,"AQID");
        var timeout=new EliminationRequest(b,"B",new DeathPosition(world,7,65,8),DamageOrigin.DISCONNECT_TIMEOUT,null,List.of(item),101,now,200);
        var box=service.eliminate(timeout).orElseThrow();
        assertEquals(DamageOrigin.DISCONNECT_TIMEOUT,box.reason().origin());
        assertEquals(Optional.of(a),box.reason().killer());assertEquals(1,session.players().get(a).kills());
        assertEquals(List.of(item),box.contents());assertEquals(50,box.storedXp());assertFalse(session.combatActive(b));
        assertTrue(service.eliminate(timeout).isEmpty());assertTrue(service.eliminate(request(b,200)).isEmpty());assertEquals(1,notifications);
        assertFalse(session.reconnect(b));
    }
    @Test void offlineTimeoutOutsideAttributionWindowHasNoKiller() {
        session.disconnected(b);session.offlineCombatant(b,true);tracker.record(b,a,5,DamageOrigin.PLAYER_MELEE,true);now=15_000_000_001L;
        var box=service.eliminate(new EliminationRequest(b,"B",new DeathPosition(world,0,64,0),DamageOrigin.DISCONNECT_TIMEOUT,null,List.of(),0,now,1)).orElseThrow();
        assertTrue(box.reason().killer().isEmpty());assertEquals(0,session.players().get(a).kills());
    }
    @Test void spectatorAndExternalCannotAccessSharedDeathBox() {
        var box=service.eliminate(request(b,1)).orElseThrow();session.spectating(b,true);var at=box.location();
        assertFalse(DeathBoxAccess.allowed(session,box,b,at,6));assertFalse(DeathBoxAccess.allowed(session,box,UUID.randomUUID(),at,6));
    }
}
