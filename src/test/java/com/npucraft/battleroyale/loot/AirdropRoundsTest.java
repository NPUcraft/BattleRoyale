package com.npucraft.battleroyale.loot;

import com.npucraft.battleroyale.zone.ZonePhase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirdropRoundsTest {
    @Test void longWaitClaimsOnlyAtSixtySecondsAndShortWaitClaimsImmediately() {
        var longWait=new AirdropRounds();
        assertTrue(longWait.poll(0,ZonePhase.WAITING,120).isEmpty());
        assertTrue(longWait.poll(0,ZonePhase.WAITING,60.001).isEmpty());
        assertEquals(0,longWait.poll(0,ZonePhase.WAITING,60).orElseThrow());
        assertTrue(longWait.poll(0,ZonePhase.WAITING,15).isEmpty());
        assertTrue(longWait.poll(0,ZonePhase.SHRINKING,120).isEmpty());
        assertEquals(0,new AirdropRounds().poll(0,ZonePhase.WAITING,1).orElseThrow());
    }
    @Test void skippedStagesStillIssueOnlyOneClaimPerPollWithAdvanceWarning() {
        var rounds=new AirdropRounds();
        for(int expected=0;expected<=3;expected++)assertEquals(expected,rounds.poll(3,ZonePhase.WAITING,30).orElseThrow());
        assertTrue(rounds.poll(3,ZonePhase.SHRINKING,120).isEmpty());
    }
    @Test void recoveryDuringWaitingCanCheckLedgerButMidShrinkDoesNotReannounce() {
        var waiting=new AirdropRounds(2,ZonePhase.WAITING);
        assertTrue(waiting.poll(2,ZonePhase.WAITING,61).isEmpty());
        assertEquals(2,waiting.poll(2,ZonePhase.WAITING,60).orElseThrow());
        assertTrue(waiting.poll(2,ZonePhase.WAITING,59).isEmpty());
        var shrinking=new AirdropRounds(2,ZonePhase.SHRINKING);
        assertTrue(shrinking.poll(2,ZonePhase.SHRINKING,59).isEmpty());
        assertEquals(3,shrinking.poll(3,ZonePhase.WAITING,30).orElseThrow());
    }
    @Test void invalidWarningRemainingCannotConsumeTheNextRound() {
        var rounds=new AirdropRounds();
        for(double remaining:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1})
            assertThrows(IllegalArgumentException.class,()->rounds.poll(0,ZonePhase.WAITING,remaining));
        assertEquals(0,rounds.poll(0,ZonePhase.WAITING,60).orElseThrow());
    }

    @Test void oneDropIsDueWhenEachShrinkStartsAndNeverAgainDuringTheSameStage() {
        var rounds=new AirdropRounds();
        assertTrue(rounds.poll(0,ZonePhase.WAITING).isEmpty());
        assertEquals(0,rounds.poll(0,ZonePhase.SHRINKING).orElseThrow());
        for(int i=0;i<100;i++)assertTrue(rounds.poll(0,ZonePhase.SHRINKING).isEmpty());
        assertTrue(rounds.poll(1,ZonePhase.WAITING).isEmpty());
        assertEquals(1,rounds.poll(1,ZonePhase.SHRINKING).orElseThrow());
        assertTrue(rounds.poll(1,ZonePhase.FINAL).isEmpty());
    }
    @Test void skippedTicksQueueEveryElapsedRoundButAtMostOnePerPoll() {
        var rounds=new AirdropRounds();
        assertEquals(0,rounds.poll(3,ZonePhase.WAITING).orElseThrow());
        assertEquals(1,rounds.poll(3,ZonePhase.WAITING).orElseThrow());
        assertEquals(2,rounds.poll(3,ZonePhase.WAITING).orElseThrow());
        assertTrue(rounds.poll(3,ZonePhase.WAITING).isEmpty());
        assertEquals(3,rounds.poll(3,ZonePhase.FINAL).orElseThrow());
        assertTrue(rounds.poll(3,ZonePhase.FINAL).isEmpty());
        assertTrue(rounds.poll(0,ZonePhase.SHRINKING).isEmpty());
    }
    @Test void recoveryDuringWaitingSkipsEarlierStagesButKeepsTheUpcomingShrink() {
        var recovered=new AirdropRounds(2,ZonePhase.WAITING);
        assertTrue(recovered.poll(2,ZonePhase.WAITING).isEmpty());
        assertEquals(2,recovered.poll(2,ZonePhase.SHRINKING).orElseThrow());
        assertTrue(recovered.poll(2,ZonePhase.SHRINKING).isEmpty());
    }
    @Test void recoveryMidShrinkOrFinalNeverReplaysTheCurrentOrEarlierRound() {
        var shrinking=new AirdropRounds(2,ZonePhase.SHRINKING);
        assertTrue(shrinking.poll(2,ZonePhase.SHRINKING).isEmpty());
        assertTrue(shrinking.poll(3,ZonePhase.WAITING).isEmpty());
        assertEquals(3,shrinking.poll(3,ZonePhase.SHRINKING).orElseThrow());
        var finalRound=new AirdropRounds(3,ZonePhase.FINAL);
        for(int i=0;i<100;i++)assertTrue(finalRound.poll(3,ZonePhase.FINAL).isEmpty());
    }
    @Test void negativeStagesFailWithoutConsumingAFutureRound() {
        var rounds=new AirdropRounds();
        assertThrows(IllegalArgumentException.class,()->rounds.poll(-1,ZonePhase.WAITING));
        assertThrows(IllegalArgumentException.class,()->new AirdropRounds(-1,ZonePhase.FINAL));
        assertEquals(0,rounds.poll(0,ZonePhase.SHRINKING).orElseThrow());
    }
}
