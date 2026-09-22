package com.npucraft.lastsector.combat;
import com.npucraft.lastsector.config.CombatSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CombatTrackerTest {
    UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),victim=UUID.randomUUID(); long now;
    Set<UUID> members=Set.of(a,b,c,victim);
    CombatTracker tracker=new CombatTracker(members,CombatSettings.DEFAULT,()->now);
    void hit(UUID attacker,double amount) {tracker.record(victim,attacker,amount,DamageOrigin.PLAYER_MELEE,true);}
    DeathReason death(UUID direct,DamageOrigin origin) {return tracker.resolve(victim,direct,origin,members::contains);}
    @Test void recoveredAttributionPausesAcrossDowntimeAndStillExpires(){hit(a,4);now=10_000_000_000L;var saved=tracker.snapshot();now=1_000_000_000_000L;var restored=new CombatTracker(members,CombatSettings.DEFAULT,()->now);restored.restore(saved);assertEquals(Optional.of(a),restored.resolve(victim,null,DamageOrigin.FALL,members::contains).killer());now+=5_000_000_000L;assertEquals(Optional.of(a),restored.resolve(victim,null,DamageOrigin.FALL,members::contains).killer());now++;assertTrue(restored.resolve(victim,null,DamageOrigin.FALL,members::contains).killer().isEmpty());}
    @ParameterizedTest @CsvSource({"14999000000,true","15000000000,true","15000000001,false","16000000000,false"})
    void inclusiveMonotonicWindow(long time,boolean credit) {hit(a,4);now=time;assertEquals(credit,death(null,DamageOrigin.FALL).killer().isPresent());}
    @ParameterizedTest @EnumSource(value=DamageOrigin.class,names={"PLAYER_MELEE","PROJECTILE","EXPLOSION","MAGIC"})
    void directKillerOverridesLastAttacker(DamageOrigin cause) {hit(a,5);hit(b,4);assertEquals(Optional.of(a),death(a,cause).killer());assertTrue(death(a,cause).direct());}
    @ParameterizedTest @EnumSource(value=DamageOrigin.class,names={"FALL","LAVA","FIRE","ZONE","VOID","DROWNING","MOB"})
    void environmentUsesLastValidAttacker(DamageOrigin cause) {hit(a,8);now=5;hit(b,2);assertEquals(Optional.of(b),death(null,cause).killer());assertFalse(death(null,cause).direct());}
    @Test void multipleAttackersThresholdsExcludeKillerAndCountEachAssistOnce() {
        hit(a,1);hit(a,2);hit(b,8);hit(c,5);
        var reason=death(b,DamageOrigin.PLAYER_MELEE);assertEquals(Set.of(c),reason.assists()); // A = 18.75%, below 4 HP.
    }
    @Test void shareAloneQualifiesAndExactFourHpQualifies() {
        hit(a,1);hit(b,4);assertEquals(Set.of(a),death(b,DamageOrigin.PROJECTILE).assists());
        tracker.clear();hit(a,4);hit(b,100);assertEquals(Set.of(a),death(b,DamageOrigin.PLAYER_MELEE).assists());
    }
    @Test void expiredContributionsDoNotEnterDenominator() {hit(a,100);now=16_000_000_000L;hit(b,1);hit(c,4);assertEquals(Set.of(b),death(c,DamageOrigin.OTHER).assists());}
    @Test void selfCrossSessionZeroNegativeAndNanNeverRecorded() {
        hit(victim,10);hit(UUID.randomUUID(),10);hit(a,0);hit(a,-1);hit(a,Double.NaN);assertEquals(0,tracker.size(victim));
        assertTrue(death(victim,DamageOrigin.EXPLOSION).killer().isEmpty());assertTrue(death(UUID.randomUUID(),DamageOrigin.PROJECTILE).killer().isEmpty());
    }
    @Test void resolverRevalidatesParticipantMembership() {hit(a,4);assertTrue(tracker.resolve(victim,a,DamageOrigin.FALL,id->false).killer().isEmpty());}
    @Test void identicalFixturesInSeparateSessionsDoNotShareHistory() {
        hit(a,8);var other=new CombatTracker(members,CombatSettings.DEFAULT,()->now);assertTrue(other.resolve(victim,null,DamageOrigin.ZONE,members::contains).killer().isEmpty());
    }
    @Test void overkillClampsHealthAndIncludesAbsorption() {
        assertEquals(4,CombatTracker.effective(100,4,0));assertEquals(6,CombatTracker.effective(100,4,2));
        assertEquals(2,CombatTracker.effective(0,4,2));assertEquals(0,CombatTracker.effective(Double.NaN,4,0));
    }
    @Test void historyBoundedAndVictimClearDoesNotRemoveOtherVictims() {
        for(int i=0;i<5000;i++) hit(a,1);assertEquals(4096,tracker.size(victim));
        tracker.record(b,a,3,DamageOrigin.MAGIC,true);tracker.forget(victim);assertEquals(0,tracker.size(victim));assertEquals(1,tracker.size(b));
    }
}
