package com.npucraft.battleroyale.paper;

import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The registry-backed PotionEffectType/Sound payloads need a live server, so this pins the pure policy here
 *  and leaves the actual nausea/sound dispatch to the Paper soak and probe. */
class ZoneDamageFeedbackTest {
    private static final PlainTextComponentSerializer TEXT=PlainTextComponentSerializer.plainText();
    @Test void throttlesSoundAndTextToEverySecondPulse(){
        assertTrue(ZoneDamageFeedback.audible(2));assertTrue(ZoneDamageFeedback.audible(4));
        assertFalse(ZoneDamageFeedback.audible(1));assertFalse(ZoneDamageFeedback.audible(3));
        assertEquals(2,ZoneDamageFeedback.EMIT_INTERVAL);
    }
    @Test void warningIsLocalizedAndReadsAsContinuingZoneDamage(){
        assertTrue(TEXT.serialize(ZoneDamageFeedback.warning(Locale.SIMPLIFIED_CHINESE)).contains("安全区外持续受伤"));
        assertTrue(TEXT.serialize(ZoneDamageFeedback.warning(Locale.ENGLISH)).contains("outside the safe zone"));
    }
}
