package com.lastsector.progression;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import com.lastsector.storage.*;
import static org.junit.jupiter.api.Assertions.*;
class ProgressionTest {
    @TempDir Path root;
    static MatchResult result(UUID session, UUID player) {
        UUID team=UUID.randomUUID();
        var p=new MatchResult.PlayerResult(player,"Player",team,1,true,false,3,0,2,42.5,90,1000,0,1000,0);
        return new MatchResult(session,"solo","city",1,0,1000,1,1,MatchResult.CompletionReason.NORMAL,Map.of(team,1),List.of(p),PeriodKeys.at(Instant.ofEpochMilli(1000),ZoneId.of("UTC")));
    }
    @Test void placementBatchesSurviveRecovery() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
        var tracker=new PlacementTracker(Set.of(a,b,c,d));tracker.observe(Set.of(a,b),10);
        assertEquals(Map.of(c,3,d,3),tracker.snapshot().assigned());
        tracker=new PlacementTracker(tracker.snapshot());tracker.observe(Set.of(),11);
        assertEquals(Map.of(a,1,b,1,c,3,d,3),tracker.snapshot().assigned());
    }
    @Test void sequentialPlacements() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
        var tracker=new PlacementTracker(Set.of(a,b,c,d));tracker.observe(Set.of(a,b,c),1);tracker.observe(Set.of(a,b),2);tracker.observe(Set.of(a),3);
        assertEquals(Map.of(a,1,b,2,c,3,d,4),tracker.snapshot().assigned());
    }
    @Test void ratingAndKillScore() {
        var calculator=new PlacementRatingCalculator(RankingSettings.defaults());
        assertEquals(40,calculator.delta(1000,1,1));assertEquals(40,calculator.delta(1000,1,4));
        assertEquals(-15,calculator.delta(1000,4,4));assertEquals(-3,calculator.delta(3,4,4));assertEquals(36,calculator.killScore(3,2));
        assertThrows(IllegalArgumentException.class,()->new RankingSettings(1000,0,List.of(new RankingSettings.Band(.5,2)),10,3,ZoneId.of("UTC"),30));
    }
    @Test void periodsUseIsoWeekYearAndConfiguredZone() {
        assertEquals("2020-W53",PeriodKeys.at(Instant.parse("2021-01-01T00:00:00Z"),ZoneId.of("UTC")).week());
        assertEquals("2026-10",PeriodKeys.at(Instant.parse("2026-09-30T23:00:00Z"),ZoneId.of("Asia/Shanghai")).month());
        assertEquals("2026-03-08",PeriodKeys.at(Instant.parse("2026-03-08T07:01:00Z"),ZoneId.of("America/New_York")).day());
    }
    @Test void resultIsAtomicAndIdempotentAcrossRestart()throws Exception {
        var provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));new JdbcRecoveryRepository(provider).migrate();
        UUID player=UUID.randomUUID();var repository=new PermanentRepository(provider,RankingSettings.defaults());
        var facts=result(UUID.randomUUID(),player);var committed=repository.finalizeResult(facts);
        assertEquals(40,committed.players().getFirst().ratingDelta());
        assertEquals(committed,new PermanentRepository(provider,RankingSettings.defaults()).finalizeResult(facts));
        var profile=repository.load(player,"Renamed",2000);
        assertEquals(1,profile.matches());assertEquals(1,profile.wins());assertEquals(3,profile.kills());assertEquals(36,profile.killScore());assertEquals(1040,profile.highestRating());assertEquals("Renamed",profile.name());
        try(var c=provider.connect();var s=c.createStatement()) {
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM player_period_stats")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));}
            s.executeUpdate("CREATE TRIGGER reject_period BEFORE UPDATE ON player_period_stats BEGIN SELECT RAISE(ABORT,'test rollback'); END");
        }
        UUID failed=UUID.randomUUID();assertThrows(Exception.class,()->repository.finalizeResult(result(failed,player)));
        assertTrue(repository.result(failed).isEmpty());assertEquals(1,repository.load(player,"Renamed",3000).matches());
    }
    @Test void outboxSurvivesNewInstanceAndDoesNotDiscardUncommittedResult()throws Exception {
        var facts=result(UUID.randomUUID(),UUID.randomUUID());var outbox=new ResultOutbox(root.resolve("outbox"));
        outbox.persist(facts);outbox.persist(facts);assertEquals(List.of(facts),new ResultOutbox(root.resolve("outbox")).pending());
        outbox.acknowledge(facts.sessionId());assertTrue(outbox.pending().isEmpty());
    }
    @Test void outboxCorruptionIsRetainedAndRejected()throws Exception {
        var facts=result(UUID.randomUUID(),UUID.randomUUID());var outbox=new ResultOutbox(root.resolve("outbox"));outbox.persist(facts);
        var file=root.resolve("outbox").resolve(facts.sessionId()+".json");var original=java.nio.file.Files.readString(file);java.nio.file.Files.writeString(file,original.replace("Player","Tampered"));
        assertThrows(java.io.IOException.class,outbox::pending);assertTrue(java.nio.file.Files.exists(file));
    }
    @Test void adminCompletionIsLedgerOnly()throws Exception {
        var provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));new JdbcRecoveryRepository(provider).migrate();var repository=new PermanentRepository(provider,RankingSettings.defaults());
        var normal=result(UUID.randomUUID(),UUID.randomUUID());var aborted=new MatchResult(normal.sessionId(),normal.roomId(),normal.mapId(),normal.teamSize(),normal.startedAt(),normal.completedAt(),normal.totalPlayers(),normal.totalTeams(),MatchResult.CompletionReason.ADMIN_END,normal.placements(),normal.players(),normal.periods());
        var saved=repository.finalizeResult(aborted);assertEquals(0,saved.players().getFirst().ratingDelta());assertEquals(0,repository.load(normal.players().getFirst().playerId(),"Player",2).matches());assertTrue(repository.result(normal.sessionId()).isPresent());
    }

}
