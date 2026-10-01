package com.npucraft.battleroyale.flight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentPolicyTest {
    private static final long TIMEOUT=90_000_000_000L;
    @Test void aGliderLandsWhereverItTouchesDownInsideOrOutsideTheInitialSquare(){
        assertEquals(DeploymentPolicy.Outcome.LAND,DeploymentPolicy.descending(80,-64,304,false,true,1_000_000_000L,TIMEOUT));
        assertEquals(DeploymentPolicy.Outcome.LAND,DeploymentPolicy.descending(70,-64,304,false,true,TIMEOUT-1,TIMEOUT));
    }
    @Test void hazardVoidAndTimeoutStillFallBack(){
        assertEquals(DeploymentPolicy.Outcome.FALLBACK,DeploymentPolicy.descending(80,-64,304,true,true,1,TIMEOUT));
        assertEquals(DeploymentPolicy.Outcome.FALLBACK,DeploymentPolicy.descending(-61,-64,304,false,true,1,TIMEOUT));
        assertEquals(DeploymentPolicy.Outcome.FALLBACK,DeploymentPolicy.descending(200,-64,304,false,false,TIMEOUT+1,TIMEOUT));
    }
    @Test void airborneAboveTheFloorKeepsDescending(){
        assertEquals(DeploymentPolicy.Outcome.DESCEND,DeploymentPolicy.descending(303,-64,304,false,false,1_000_000_000L,TIMEOUT));
        assertEquals(DeploymentPolicy.Outcome.DESCEND,DeploymentPolicy.descending(80,-64,304,false,false,1_000_000_000L,TIMEOUT));
    }
    @Test void passengersAreCarriedOnlyWhileStandingOnThePlatform(){
        assertTrue(DeploymentPolicy.carried(true,true));
        assertFalse(DeploymentPolicy.carried(false,true));
        assertFalse(DeploymentPolicy.carried(true,false));
    }
}
