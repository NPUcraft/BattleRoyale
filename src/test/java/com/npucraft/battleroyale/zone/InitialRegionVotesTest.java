package com.npucraft.battleroyale.zone;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialRegionVotesTest {
    private final InitialZoneCenters centers=new InitialZoneCenters(List.of(
            new InitialZoneCenters.Region("north","North",0,100,0,100),
            new InitialZoneCenters.Region("south","South",0,100,-100,0),
            new InitialZoneCenters.Region("west","West",-100,0,0,100)));
    private final UUID first=UUID.randomUUID(),second=UUID.randomUUID(),third=UUID.randomUUID();
    @Test void oneVotePerPlayerCanBeChangedAndRepeatsNeverAddVotes(){
        var votes=new InitialRegionVotes(centers);
        assertTrue(votes.vote(first,"north"));assertFalse(votes.vote(first,"north"));assertEquals(Optional.of("north"),votes.choice(first));
        assertTrue(votes.vote(first,"south"));assertEquals(Map.of("north",0,"south",1,"west",0),votes.counts(Set.of(first)));
        assertThrows(IllegalArgumentException.class,()->votes.vote(first,"unknown"));assertEquals(Optional.of("south"),votes.choice(first));
        assertThrows(IllegalArgumentException.class,()->votes.vote(first,null));assertEquals(Optional.of("south"),votes.choice(first));
    }
    @Test void clearPluralityWinsEveryTimeAndReportsOnlyEligibleVotes(){
        var votes=new InitialRegionVotes(centers);votes.vote(first,"north");votes.vote(second,"north");votes.vote(third,"west");
        for(int seed=0;seed<50;seed++){
            var result=votes.choose(Set.of(first,second,third),new Random(seed));assertEquals("north",result.region().id());assertEquals(2,result.votes());assertEquals(3,result.totalVotes());
        }
        var result=votes.choose(Set.of(third),new Random(1));assertEquals("west",result.region().id());assertEquals(1,result.votes());assertEquals(1,result.totalVotes());
    }
    @Test void tiesAreRandomOnlyAmongLeaders(){
        var votes=new InitialRegionVotes(centers);votes.vote(first,"north");votes.vote(second,"south");
        var winners=new HashMap<String,Integer>();var random=new Random(66);
        for(int i=0;i<4000;i++){var result=votes.choose(Set.of(first,second),random);winners.merge(result.region().id(),1,Integer::sum);assertEquals(1,result.votes());assertEquals(2,result.totalVotes());}
        assertEquals(Set.of("north","south"),winners.keySet());assertEquals(2000,winners.get("north"),120);assertEquals(2000,winners.get("south"),120);
    }
    @Test void noEligibleVotesRandomizesAcrossAllRegions(){
        var votes=new InitialRegionVotes(centers);votes.vote(first,"north");var random=new Random(907);var winners=new HashMap<String,Integer>();
        for(int i=0;i<6000;i++){var result=votes.choose(Set.of(),random);assertEquals(0,result.votes());assertEquals(0,result.totalVotes());winners.merge(result.region().id(),1,Integer::sum);}
        assertEquals(Set.of("north","south","west"),winners.keySet());winners.values().forEach(count->assertEquals(2000,count,150));
    }
    @Test void departedPlayersCanBeFilteredOrExplicitlyRemovedAndRetained(){
        var votes=new InitialRegionVotes(centers);votes.vote(first,"north");votes.vote(second,"south");votes.vote(third,"south");
        assertEquals(0,votes.counts(Set.of(first)).get("south"));assertEquals(Optional.of("south"),votes.choice(second),"Tallying does not silently mutate ballots");
        votes.retain(Set.of(first,second));assertTrue(votes.choice(third).isEmpty());
        assertTrue(votes.remove(second));assertFalse(votes.remove(second));assertEquals(Map.of("north",1,"south",0,"west",0),votes.counts(Set.of(first,second,third)));
        votes.clear();assertTrue(votes.choice(first).isEmpty());assertEquals(Map.of("north",0,"south",0,"west",0),votes.counts(Set.of(first)));
    }
    @Test void independentSessionMapBallotsNeverLeakAndCountsAreImmutable(){
        var a=new InitialRegionVotes(centers);var b=new InitialRegionVotes(centers);a.vote(first,"north");b.vote(first,"south");
        assertEquals("north",a.choose(Set.of(first),new Random()).region().id());assertEquals("south",b.choose(Set.of(first),new Random()).region().id());
        var counts=a.counts(Set.of(first));assertEquals(List.of("north","south","west"),new ArrayList<>(counts.keySet()));
        assertThrows(UnsupportedOperationException.class,()->counts.put("north",99));a.clear();assertEquals(1,counts.get("north"));
        assertThrows(IllegalArgumentException.class,()->new InitialRegionVotes(new InitialZoneCenters(0,List.of(new InitialZoneCenters.Point(951,862)))));
    }
}