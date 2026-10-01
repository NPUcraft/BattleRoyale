package com.npucraft.battleroyale.paper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The registry-backed PotionEffectType/Sound payloads need a live server, so this pins the pure throttle policy;
 *  the actual nausea/sound dispatch is covered by the Paper soak and probe. */
class ZoneDamageFeedbackTest {
    @Test void throttlesSoundToEverySecondPulse(){
        assertTrue(ZoneDamageFeedback.audible(2));assertTrue(ZoneDamageFeedback.audible(4));
        assertFalse(ZoneDamageFeedback.audible(1));assertFalse(ZoneDamageFeedback.audible(3));
        assertEquals(2,ZoneDamageFeedback.EMIT_INTERVAL);
    }
}
