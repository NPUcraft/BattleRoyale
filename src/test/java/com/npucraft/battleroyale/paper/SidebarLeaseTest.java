package com.npucraft.battleroyale.paper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SidebarLeaseTest {
    @Test void ownedBoardCanBeUpdatedAndRestoresExactlyThePreviousInstance(){
        Object previous=new Object(),owned=new Object();var lease=new SidebarLease<>(previous,owned);
        assertTrue(lease.mayUpdate(owned));assertTrue(lease.mayUpdate(owned));assertSame(previous,lease.restore(owned).orElseThrow());
    }
    @Test void anotherPluginTakingOverStopsUpdatesAndMustNeverBeOverwrittenOnCleanup(){
        Object previous=new Object(),owned=new Object(),foreign=new Object();var lease=new SidebarLease<>(previous,owned);
        assertFalse(lease.mayUpdate(foreign));assertTrue(lease.restore(foreign).isEmpty());
        assertFalse(lease.mayUpdate(foreign));assertFalse(lease.mayUpdate(owned));
        assertSame(previous,lease.restore(owned).orElseThrow());
    }
    @Test void identityRatherThanEqualityProtectsUnrelatedScoreboards(){
        String previous=new String("same"),owned=new String("same"),foreign=new String("same");var lease=new SidebarLease<>(previous,owned);
        assertTrue(owned.equals(foreign));assertFalse(lease.mayUpdate(foreign));assertTrue(lease.restore(foreign).isEmpty());
    }
}
