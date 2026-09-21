package com.lastsector.offline;
import java.util.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
class OfflineBodyTest {
    BodySnapshot snapshot(){return new BodySnapshot(new BodyPosition(UUID.randomUUID(),1,64,2,45,10),12,20,3,14,2,20,100,2,List.of(),Map.of(),null,2,4,.5f,44);}
    OfflineBody body(){var b=new OfflineBody(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"P",snapshot(),0,Duration.ofSeconds(120));b.spawned(UUID.randomUUID());return b;}
    @ParameterizedTest @CsvSource({"119999000000,false","120000000000,true","120000000001,true"})
    void exactMonotonicDeadline(long now,boolean expired){var b=body();assertEquals(expired,b.expired(now));assertEquals(!expired,b.beginReconnect(now));}
    @Test void lethalDamageWinsOverTimeoutAndReconnect(){var b=body();assertTrue(b.eliminate());assertFalse(b.eliminate());assertFalse(b.beginReconnect(1));}
    @Test void reconnectWinsOverDamageAndTimeout(){var b=body();assertTrue(b.beginReconnect(1));assertFalse(b.eliminate());b.restored();assertFalse(b.active());assertFalse(b.beginReconnect(2));assertFalse(b.eliminate());}
    @Test void failedReconnectKeepsBodyAuthorityAndAllowsRetry(){var b=body();var original=b.snapshot();assertTrue(b.beginReconnect(1));b.restoreFailed();assertTrue(b.active());assertSame(original,b.snapshot());assertTrue(b.beginReconnect(2));}
    @Test void zeroWindowExpiresImmediately(){var b=new OfflineBody(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"P",snapshot(),4,Duration.ZERO);assertTrue(b.expired(4));assertTrue(b.eliminate());}
    @Test void reservedSpawnFailureCanEliminateOnce(){var b=new OfflineBody(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"P",snapshot(),0,Duration.ofSeconds(120));assertTrue(b.eliminate());assertThrows(IllegalStateException.class,()->b.spawned(UUID.randomUUID()));}
    @Test void snapshotsAndIdentityRemainIndependent(){var a=body();var b=body();a.eliminate();assertTrue(b.active());assertNotEquals(a.session(),b.session());assertThrows(UnsupportedOperationException.class,()->b.snapshot().inventory().clear());}
}
