package com.npucraft.battleroyale.service;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomRegionVoteTest {
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
    TestSupport.Worlds worlds;TestSupport.Players players;TestSupport.Scheduler clock;RoomRuntimeService rooms;
    @BeforeEach void setup(){
        var centers=new InitialZoneCenters(List.of(new InitialZoneCenters.Region("north","North",0,100,0,100),new InitialZoneCenters.Region("south","South",0,100,-500,-400)));
        var profile=new ZoneProfile("default",List.of(new ZoneProfile.InitialSize(8,200)),List.of(new ZoneProfile.Stage(Duration.ofSeconds(60),Duration.ofSeconds(30),0,1,0,1)),0,Map.of("city",centers));
        var configuration=new ConfigurationSnapshot(new PluginSettings(false,"sqlite","auto",Path.of("runtime")),
                List.of(TestSupport.room("a",2,3),TestSupport.room("b",2,3)),List.of(new MapTemplate("city","City",Path.of("city"),new PlayableArea(-2000,2000,-2000,2000))),List.of(profile));
        worlds=new TestSupport.Worlds();players=new TestSupport.Players();clock=new TestSupport.Scheduler();
        rooms=new RoomRuntimeService(()->configuration,new SessionManager(),clock,MapSelector.random(new Random(1)),worlds,players,Clock.systemUTC(),players.matches());
    }
    @Test void changedVotesAreSingleAndHighestFreezesBeforeWorldPreparation(){
        rooms.join(a,"a");rooms.join(b,"a");rooms.join(c,"a");
        rooms.voteRegion(a,"city","north");rooms.voteRegion(a,"city","south");rooms.voteRegion(a,"city","south");
        rooms.voteRegion(b,"city","south");rooms.voteRegion(c,"city","north");
        var options=rooms.regionOptions(a);assertEquals(3,options.stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum());
        assertEquals("south",options.stream().filter(RoomRuntimeService.RegionOption::selected).findFirst().orElseThrow().region().id());
        rooms.debugStart("a");var session=rooms.session("a").orElseThrow();
        assertEquals(GameState.PREPARING,session.state());assertEquals("south",session.initialRegionId().orElseThrow());
        assertTrue(worlds.pending.containsKey(session.sessionId()));assertTrue(players.events.contains("region-selected"));
        assertThrows(IllegalStateException.class,()->rooms.voteRegion(a,"city","north"));assertTrue(rooms.regionOptions(a).isEmpty());
    }
    @Test void disconnectAndWithdrawRemoveVotesWhileOtherRoomsStayIndependent(){
        rooms.join(a,"a");rooms.join(b,"a");rooms.join(c,"b");rooms.voteRegion(a,"city","north");rooms.voteRegion(b,"city","south");rooms.voteRegion(c,"city","north");
        rooms.disconnected(a);assertEquals(1,rooms.regionOptions(b).stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum());
        rooms.clearRegionVotes(b);assertEquals(0,rooms.regionOptions(b).stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum());
        assertEquals(1,rooms.regionOptions(c).stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum());
        rooms.debugStart("a");assertTrue(rooms.session("a").orElseThrow().initialRegionId().isPresent());assertTrue(players.events.contains("region-random"));
    }
    @Test void countdownFreezesAndNewSessionDoesNotInheritBallots(){
        rooms.join(a,"a");rooms.join(b,"a");rooms.voteRegion(a,"city","north");clock.seconds(3);
        var first=rooms.session("a").orElseThrow();assertEquals("north",first.initialRegionId().orElseThrow());
        worlds.succeed(first.sessionId());rooms.debugEnd("a");rooms.join(a,"a");
        assertNotEquals(first.sessionId(),rooms.session("a").orElseThrow().sessionId());
        assertTrue(rooms.regionOptions(a).stream().noneMatch(RoomRuntimeService.RegionOption::selected));assertEquals(0,rooms.regionOptions(a).stream().mapToInt(RoomRuntimeService.RegionOption::votes).sum());
    }
    @Test void outsiderForeignMapAndRegionAreRejected(){
        assertThrows(IllegalStateException.class,()->rooms.voteRegion(a,"city","north"));rooms.join(a,"a");
        assertThrows(IllegalArgumentException.class,()->rooms.voteRegion(a,"foreign","north"));
        assertThrows(IllegalArgumentException.class,()->rooms.voteRegion(a,"city","foreign"));
    }
}
