package com.lastsector.player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlayerIsolationTest {
    UUID a=UUID.randomUUID(),b=UUID.randomUUID(),session=UUID.randomUUID();
    Map<UUID,String> states=new HashMap<>(Map.of(a,"original-a",b,"original-b"));
    boolean captureFailure,applyFailure,offline,restoreFailure; List<String> events=new ArrayList<>();
    PlayerIsolation<String,String> isolation=new PlayerIsolation<>(new PlayerIsolation.Gateway<>() {
        public String capture(UUID id) { events.add("capture"); if(captureFailure && id.equals(b)) throw new IllegalStateException(); return states.get(id); }
        public void apply(UUID id,String loadout) { events.add("apply"); states.put(id,loadout); if(applyFailure && id.equals(b)) throw new IllegalStateException(); }
        public boolean restore(UUID id,String original) { if(restoreFailure) throw new IllegalStateException(); if(offline && id.equals(b)) return false; states.put(id,original); return true; }
    },(id,error)->{});
    @Test void captureEveryoneBeforeApplyAndRestoreOnlyOriginal() {
        isolation.apply(session,List.of(a,b),"loadout"); assertEquals(List.of("capture","capture","apply","apply"),events);
        states.put(a,"match loot"); isolation.end(session); assertEquals("original-a",states.get(a)); assertEquals("original-b",states.get(b));
        isolation.end(session); assertEquals(0,isolation.pendingCount());
    }
    @Test void spectatorRetainsOriginalUntilLeaveAndCleanupDoesNotRestoreTwice() {
        isolation.apply(session,List.of(a),"match");states.put(a,"empty spectator inventory");assertFalse(isolation.blocked(a));
        isolation.defer(session,a);isolation.retry(a);assertEquals("original-a",states.get(a));states.put(a,"lobby additions");isolation.end(session);assertEquals("lobby additions",states.get(a));
    }
    @Test void externalSpectatorUsesIndependentSnapshotAndDisconnectQueuesRestore() {
        UUID external=UUID.randomUUID();isolation.apply(external,List.of(b),"empty spectator inventory");offline=true;isolation.defer(external,b);isolation.end(external);
        assertTrue(isolation.blocked(b));offline=false;assertTrue(isolation.retry(b));assertEquals("original-b",states.get(b));
    }
    @Test void eliminatedPlayerRestoresExactlyOnceAcrossRespawnAndSessionEnd() {
        isolation.apply(session,List.of(a,b),"match");isolation.defer(session,a);assertTrue(isolation.blocked(a));
        assertEquals("match",states.get(a));assertTrue(isolation.retry(a));states.put(a,"new lobby possessions");
        isolation.defer(session,a);isolation.end(session);assertEquals("new lobby possessions",states.get(a));assertEquals("original-b",states.get(b));
    }
    @Test void applyOnceAndDifferentSessionsIndependent() {
        isolation.apply(session,List.of(a),"one"); UUID other=UUID.randomUUID(); isolation.apply(other,List.of(b),"two");
        assertThrows(IllegalStateException.class,()->isolation.apply(session,List.of(a),"three")); isolation.end(session);
        assertEquals("two",states.get(b)); isolation.close(); assertEquals("original-b",states.get(b));
    }
    @Test void failedCaptureNeverMutatesAnyone() {
        captureFailure=true; assertThrows(IllegalStateException.class,()->isolation.apply(session,List.of(a,b),"bad"));
        assertEquals(List.of("capture","capture"),events); assertEquals("original-a",states.get(a));
    }
    @Test void PartialApplyFailureRollsBackEveryone() {
        applyFailure=true; assertThrows(IllegalStateException.class,()->isolation.apply(session,List.of(a,b),"bad"));
        assertEquals("original-a",states.get(a)); assertEquals("original-b",states.get(b)); assertEquals(0,isolation.pendingCount());
    }
    @Test void pendingRestoreSurvivesSessionEndAndBlocksNewSnapshot() {
        isolation.apply(session,List.of(a,b),"match"); offline=true; isolation.end(session);
        assertFalse(isolation.blocked(a)); assertTrue(isolation.blocked(b)); assertFalse(isolation.retry(b));
        assertThrows(IllegalStateException.class,()->isolation.apply(UUID.randomUUID(),List.of(b),"new"));
        offline=false; assertTrue(isolation.retry(b)); assertFalse(isolation.blocked(b)); assertEquals("original-b",states.get(b));
    }
    @Test void failedRestorationRetainsOriginalForRetry() {
        isolation.apply(session,List.of(a),"match"); restoreFailure=true; isolation.end(session);
        assertTrue(isolation.blocked(a)); restoreFailure=false; assertTrue(isolation.retry(a)); assertEquals("original-a",states.get(a));
    }
}
