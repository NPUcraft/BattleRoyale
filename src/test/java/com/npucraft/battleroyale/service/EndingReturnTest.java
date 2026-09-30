package com.npucraft.battleroyale.service;

import com.npucraft.battleroyale.TestSupport;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.map.MapSelector;
import com.npucraft.battleroyale.player.PlayerIsolation;
import com.npucraft.battleroyale.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Returning one participant must not retire their frozen match or restore anyone else. */
class EndingReturnTest {
    UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
    Map<UUID,String> items=new HashMap<>(Map.of(alice,"original-alice",bob,"original-bob"));
    Set<UUID> offline=new HashSet<>();List<UUID> pendingJournal=new ArrayList<>();
    TestSupport.Worlds worlds=new TestSupport.Worlds();TestSupport.Players players=new TestSupport.Players();
    RoomRuntimeService runtime;Consumer<GameSession> finished;int earlyCalls;boolean rejectEarly;
    CompletableFuture<Void> resultDurable=CompletableFuture.completedFuture(null);
    PlayerIsolation<String,String> isolation=new PlayerIsolation<>(new PlayerIsolation.Gateway<>() {
        public String capture(UUID id){return items.get(id);}
        public void apply(UUID id,String loadout){items.put(id,loadout);}
        public boolean restore(UUID id,String original){assertTrue(pendingJournal.contains(id));if(offline.contains(id))return false;items.put(id,original);return true;}
    },(id,error)->{throw error;});
    @BeforeEach void setup(){
        isolation.durability(new PlayerIsolation.Durability<>(){
            public java.util.concurrent.CompletionStage<Void> store(UUID session,Map<UUID,String> originals){return CompletableFuture.completedFuture(null);}
            public void pending(UUID player){pendingJournal.add(player);}
        });
        var config=new ConfigurationSnapshot(new PluginSettings(false,"sqlite","auto",Path.of("runtime")),
                List.of(TestSupport.room("a",2,3),TestSupport.room("b",2,3)),List.of(TestSupport.map(Path.of("city"))),List.of());
        MatchLifecycle matches=new MatchLifecycle(){
            public void onFinished(Consumer<GameSession> callback){finished=callback;}
            public void start(GameSession session,Runnable ready,Consumer<Throwable> failed){isolation.applyAsync(session.sessionId(),List.copyOf(session.players().keySet()),"match-items").whenComplete((unused,error)->{if(error==null)ready.run();else failed.accept(error);});}
            public void running(GameSession session,Consumer<Throwable> failed){}
            public void leaveEnding(GameSession session,UUID player){earlyCalls++;EndingReturnPolicy.require(session,player,resultDurable);if(rejectEarly)throw new IllegalStateException("restore blocked");isolation.defer(session.sessionId(),player);isolation.retry(player);}
            public void restore(GameSession session){isolation.end(session.sessionId());}
            public void stop(GameSession session,Runnable drained){drained.run();}
            public void disconnected(UUID player){}
            public void close(){isolation.close();}
        };
        runtime=new RoomRuntimeService(()->config,new SessionManager(),new TestSupport.Scheduler(),MapSelector.random(new Random(7)),worlds,players,Clock.systemUTC(),matches);
    }
    GameSession running(){runtime.join(alice,"a");runtime.join(bob,"a");runtime.debugStart("a");var session=runtime.session("a").orElseThrow();worlds.succeed(session.sessionId());return session;}
    GameSession ending(){var session=running();session.outcome(new MatchOutcome(Set.of(alice),false,"LAST_ALIVE",123,42));return session;}
    @Test void singleReturnKeepsResultParticipantsAndOtherPlayerUntilNormalCleanup(){
        var session=ending();var result=session.outcome().orElseThrow();var teams=session.teams();var participants=session.players();
        runtime.leave(alice);
        assertEquals("original-alice",items.get(alice));assertEquals("match-items",items.get(bob));assertEquals(List.of(alice),pendingJournal);
        assertEquals(GameState.ENDING,session.state());assertSame(result,session.outcome().orElseThrow());assertEquals(teams,session.teams());assertEquals(participants,session.players());
        assertSame(session,runtime.participant(alice).orElseThrow());assertTrue(worlds.released.isEmpty());assertFalse(runtime.canReload());
        assertThrows(IllegalStateException.class,()->runtime.join(alice,"b"));
        items.put(alice,"new-lobby-items");finished.accept(session);
        assertEquals("new-lobby-items",items.get(alice));assertEquals("original-bob",items.get(bob));assertEquals(1,worlds.released.size());
        assertTrue(runtime.participant(alice).isEmpty());assertTrue(runtime.session("a").isEmpty());assertSame(result,session.outcome().orElseThrow());
        runtime.join(alice,"b");assertEquals(GameState.WAITING,runtime.participant(alice).orElseThrow().state());
    }
    @Test void repeatedEarlyExitNeverRestoresTheSameOriginalTwice(){
        var session=ending();runtime.leave(alice);items.put(alice,"lobby-change");runtime.leave(alice);
        assertEquals("lobby-change",items.get(alice));assertEquals(List.of(alice),pendingJournal);assertEquals(2,earlyCalls);
        assertEquals(GameState.ENDING,session.state());assertEquals("match-items",items.get(bob));
    }
    @Test void pendingRestoreSurvivesTheSharedShowcaseAndNormalCleanup(){
        var session=ending();offline.add(alice);runtime.leave(alice);
        assertTrue(isolation.blocked(alice));assertEquals("match-items",items.get(alice));assertEquals("match-items",items.get(bob));
        finished.accept(session);assertTrue(isolation.blocked(alice));assertEquals("original-bob",items.get(bob));
        offline.remove(alice);assertTrue(isolation.retry(alice));assertEquals("original-alice",items.get(alice));
    }
    @Test void livingPlayersCannotUseTheEndingExitWhileRunning(){
        var session=running();assertThrows(IllegalStateException.class,()->runtime.leave(alice));
        assertEquals(0,earlyCalls);assertEquals(GameState.RUNNING,session.state());assertEquals("match-items",items.get(alice));assertTrue(pendingJournal.isEmpty());
    }
    @Test void undecidedEndingCannotReleaseAnOriginal(){
        var session=running();session.transition(GameState.ENDING);
        assertThrows(IllegalStateException.class,()->runtime.leave(alice));assertEquals(0,earlyCalls);assertTrue(pendingJournal.isEmpty());
    }
    @Test void rejectedRestorationDoesNotRetireOrMutateTheMatch(){
        var session=ending();rejectEarly=true;var outcome=session.outcome().orElseThrow();
        assertThrows(IllegalStateException.class,()->runtime.leave(alice));assertSame(session,runtime.participant(alice).orElseThrow());
        assertEquals(GameState.ENDING,session.state());assertSame(outcome,session.outcome().orElseThrow());assertTrue(worlds.released.isEmpty());
    }
    @Test void resultMustReachTheDurableOutboxBeforeReturningAnOriginal(){
        var session=ending();resultDurable=new CompletableFuture<>();
        assertThrows(IllegalStateException.class,()->runtime.leave(alice));assertTrue(pendingJournal.isEmpty());assertEquals("match-items",items.get(alice));
        resultDurable.complete(null);runtime.leave(alice);assertEquals("original-alice",items.get(alice));assertEquals(GameState.ENDING,session.state());
    }
    @Test void failedOrCancelledResultNeverReleasesTheOriginal(){
        var session=ending();resultDurable=CompletableFuture.failedFuture(new IllegalStateException("disk unavailable"));
        assertThrows(IllegalStateException.class,()->runtime.leave(alice));
        resultDurable=new CompletableFuture<>();resultDurable.cancel(false);assertThrows(IllegalStateException.class,()->runtime.leave(alice));
        assertTrue(pendingJournal.isEmpty());assertEquals("match-items",items.get(alice));assertEquals(GameState.ENDING,session.state());
    }
}
