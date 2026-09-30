package com.npucraft.battleroyale.loot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeaconBuffPolicyTest {
    private boolean allow(double distance,int amplifier,int ticks){return BeaconBuffPolicy.allows(true,true,true,true,true,false,false,distance,amplifier,ticks);}
    @Test void radiusIsThreeDimensionalAndInclusiveWithFiveSecondLevelOnePulse(){
        assertEquals(24,BeaconBuffPolicy.RADIUS);assertEquals(100,BeaconBuffPolicy.DURATION_TICKS);assertEquals(0,BeaconBuffPolicy.AMPLIFIER);
        assertTrue(allow(0,-1,0));assertTrue(allow(24*24,-1,0));assertFalse(allow(24*24+.01,-1,0));
        assertFalse(allow(24*24+24*24,-1,0));assertFalse(allow(Double.NaN,-1,0));assertFalse(allow(-1,-1,0));
    }
    @Test void onlyActivePrimaryInWorldLivingParticipantsAreEligible(){
        assertFalse(BeaconBuffPolicy.allows(false,true,true,true,true,false,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,false,true,true,true,false,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,true,false,true,true,false,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,true,true,false,true,false,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,true,true,true,false,false,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,true,true,true,true,true,false,0,-1,0));
        assertFalse(BeaconBuffPolicy.allows(true,true,true,true,true,false,true,0,-1,0));
    }
    @Test void strongerOrLongerPlayerEffectsArePreservedWithoutRemovingAnything(){
        assertFalse(allow(0,1,1));assertFalse(allow(0,4,500));assertFalse(allow(0,0,100));assertFalse(allow(0,0,10000));
        assertFalse(allow(0,0,-1));assertFalse(allow(0,1,-1));
        assertTrue(allow(0,0,20));assertTrue(allow(0,0,99));
    }
}
