package com.npucraft.lastsector.combat;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProtectionTest {
    @Test void exactWindowAndZeroDuration() {
        var window=new ProtectionWindow(5_000_000_000L,Duration.ofSeconds(60));
        assertTrue(window.active(5_000_000_000L)); assertTrue(window.active(64_999_999_999L));
        assertFalse(window.active(65_000_000_000L)); assertFalse(new ProtectionWindow(0,Duration.ZERO).active(0));
        assertEquals(59,window.remaining(6_000_000_000L));
    }
    @Test void sameSessionPlayerHarmOnly() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),other=UUID.randomUUID();
        var window=new ProtectionWindow(0,Duration.ofSeconds(60)); var members=Set.of(a,b);
        assertTrue(ProtectionPolicy.blocks(window,0,members,a,b));
        assertFalse(ProtectionPolicy.blocks(window,0,members,null,b)); // Natural damage/mob without player provenance.
        assertFalse(ProtectionPolicy.blocks(window,0,members,other,b));
        assertFalse(ProtectionPolicy.blocks(window,0,Set.of(other),a,b));
        assertFalse(ProtectionPolicy.blocks(window,0,members,a,a));
        assertFalse(ProtectionPolicy.blocks(window,60_000_000_000L,members,a,b));
    }
    @Test void provenanceFlowsAndExpiresWithoutCrossSessionSharing() {
        var a=new PvPHazardTracker(); var b=new PvPHazardTracker(); UUID player=UUID.randomUUID(),world=UUID.randomUUID(),entity=UUID.randomUUID();
        var source=new PvPHazardTracker.BlockKey(world,0,60,0); var flow=new PvPHazardTracker.BlockKey(world,1,60,0);
        a.block(source,player); a.spread(source,flow); assertEquals(player,a.owner(flow)); assertNull(b.owner(flow));
        a.entity(entity,player); assertEquals(player,a.entityOwner(entity));
        a.burning(entity,player); assertEquals(player,a.burningOwner(entity));
        a.block(flow,null); assertNull(a.owner(flow));
        a.clear(); assertEquals(0,a.size()); assertNull(a.entityOwner(entity)); assertNull(a.burningOwner(entity));
    }
}

