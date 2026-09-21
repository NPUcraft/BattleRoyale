package com.lastsector.team;
import com.lastsector.TestSupport;
import com.lastsector.combat.*;
import com.lastsector.session.*;
import com.lastsector.map.GameWorld;
import com.lastsector.room.RoomDefinition;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
class TeamSystemTest {
    @ParameterizedTest @CsvSource({"1,1,1","2,2,1","4,4,1","5,4,2","10,4,3","12,4,3","3,20,1"})
    void balancedDeterministicCompleteAssignment(int count,int cap,int teams) {
        var roster=new ArrayList<UUID>();for(int i=0;i<count;i++)roster.add(new UUID(0,i));UUID session=UUID.randomUUID();
        var input=TeamAssignmentInput.automatic(roster,cap);var result=TeamAssignment.assign(session,input,new Random(4));
        assertEquals(result,TeamAssignment.assign(session,input,new Random(4)));assertEquals(teams,result.size());
        assertEquals(count,result.stream().flatMap(t->t.playerIds().stream()).distinct().count());
        var sizes=result.stream().mapToInt(t->t.playerIds().size()).summaryStatistics();assertTrue(sizes.getMax()<=cap);assertTrue(sizes.getMax()-sizes.getMin()<=1);
        assertNotEquals(result.getFirst().teamId(),TeamAssignment.assign(UUID.randomUUID(),input,new Random(4)).getFirst().teamId());
        assertThrows(UnsupportedOperationException.class,()->result.getFirst().playerIds().clear());
    }
    GameSession session(int count,int cap) {
        var room=new RoomDefinition("r","r",1,20,cap,Duration.ZERO,List.of("city"),"default","default",true);
        var session=GameSession.waiting(UUID.randomUUID(),room,Instant.EPOCH);for(int i=0;i<count;i++)session.join(new UUID(0,i));
        var map=TestSupport.map(Path.of("city"));session.prepare(map,new Random(3));session.starting(new GameWorld(session.sessionId(),"r","runtime",Path.of("runtime"),map));session.transition(GameState.RUNNING);return session;
    }
    @Test void eliminationAndSpectatingDoNotChangeMembershipAndDeadTeammateWins() {
        var s=session(4,2);var teams=new ArrayList<>(s.teams().values());var winner=teams.getFirst();var dead=winner.playerIds().iterator().next();s.eliminate(dead);s.spectating(dead,true);
        var deaths=new HashMap<UUID,Long>();for(UUID id:teams.getLast().playerIds()){s.eliminate(id);deaths.put(id,10L);}
        var result=new TeamOutcomeResolver().resolve(s,deaths,10,0).orElseThrow();assertEquals(winner.playerIds(),result.winnerIds());assertEquals(Set.of(winner.teamId()),result.winningTeamIds());
        s.spectating(dead,false);assertEquals(winner.playerIds(),new TeamOutcomeResolver().resolve(s,deaths,10,0).orElseThrow().winnerIds());assertEquals(4,s.teams().values().stream().mapToInt(t->t.playerIds().size()).sum());
    }
    @Test void liveOfflineBodyCountsAndDeadBodyDoesNot() {
        var s=session(2,1);var ids=new ArrayList<>(s.players().keySet());UUID offline=ids.getFirst();s.disconnected(offline);s.offlineCombatant(offline,true);
        assertTrue(s.combatActive(offline));assertEquals(2,s.activeCount());assertTrue(new TeamOutcomeResolver().resolve(s,Map.of(),1,0).isEmpty());
        s.eliminate(offline);assertFalse(s.combatActive(offline));assertEquals(Set.of(ids.getLast()),new TeamOutcomeResolver().resolve(s,Map.of(offline,1L),1,0).orElseThrow().winnerIds());
    }
    @Test void finalTeamsTieIncludesEarlierDeadMembersButNotEarlierEliminatedTeams() {
        var s=session(6,2);var teams=new ArrayList<>(s.teams().values());var deaths=new HashMap<UUID,Long>();
        for(int index=0;index<3;index++){var ids=new ArrayList<>(teams.get(index).playerIds());s.eliminate(ids.getFirst());deaths.put(ids.getFirst(),8L);s.eliminate(ids.getLast());deaths.put(ids.getLast(),index==0?9L:10L);}
        var result=new TeamOutcomeResolver().resolve(s,deaths,10,0).orElseThrow();assertTrue(result.tie());assertEquals(4,result.winnerIds().size());assertFalse(result.winningTeamIds().contains(teams.getFirst().teamId()));
    }
    @Test void teamPolicyAppliesAfterProtectionAndAcrossEveryProvenanceType() {
        var s=session(4,2);var teams=new ArrayList<>(s.teams().values());var mates=new ArrayList<>(teams.getFirst().playerIds());UUID enemy=teams.getLast().playerIds().iterator().next();
        for(var origin:DamageOrigin.values()){assertTrue(CombatPolicy.blocks(s,0,mates.getFirst(),mates.getLast()),origin.toString());assertFalse(CombatPolicy.blocks(s,0,enemy,mates.getLast()));}
        s.disconnected(mates.getLast());s.offlineCombatant(mates.getLast(),true);assertTrue(CombatPolicy.blocks(s,0,mates.getFirst(),mates.getLast()));
        assertFalse(CombatPolicy.blocks(s,0,null,mates.getLast()));assertFalse(CombatPolicy.blocks(s,0,UUID.randomUUID(),mates.getLast()));
    }
    @Test void noFalseTieAcrossTicks() {var s=session(2,1);var ids=new ArrayList<>(s.players().keySet());ids.forEach(s::eliminate);assertFalse(new TeamOutcomeResolver().resolve(s,Map.of(ids.get(0),1L,ids.get(1),2L),2,0).orElseThrow().tie());}
}
