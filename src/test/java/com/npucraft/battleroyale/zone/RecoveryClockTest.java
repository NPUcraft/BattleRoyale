package com.npucraft.battleroyale.zone;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RecoveryClockTest {
 @Test void slowMultiRoomBootstrapDoesNotConsumeGameplayTime(){long[] source={100};var clock=new RecoveryClock(()->source[0]);assertEquals(100,clock.nanoTime());source[0]+=300_000_000_000L;assertEquals(100,clock.nanoTime());clock.resume();assertEquals(100,clock.nanoTime());source[0]+=1_000_000_000L;assertEquals(1_000_000_100L,clock.nanoTime());clock.resume();assertEquals(1_000_000_100L,clock.nanoTime());}
}
