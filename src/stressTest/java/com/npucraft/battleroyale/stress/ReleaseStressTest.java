package com.npucraft.battleroyale.stress;

import static org.junit.jupiter.api.Assertions.*;

import com.npucraft.battleroyale.*;
import com.npucraft.battleroyale.combat.*;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.progression.*;
import com.npucraft.battleroyale.room.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.storage.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repeatable logical and actual-file database load; does not claim equivalent live client capacity.
 */
class ReleaseStressTest {
  @TempDir Path root;

  @Test
  void twentyRoomsThirtyTwoPlayersOneHundredCycles() {
    long start = System.nanoTime();
    Set<UUID> identities = new HashSet<>();
    for (int cycle = 0; cycle < 100; cycle++) {
      var manager = new SessionManager();
      for (int r = 0; r < 20; r++) {
        var room =
            new RoomDefinition(
                "r" + r,
                "Room",
                1,
                32,
                4,
                Duration.ZERO,
                List.of("city"),
                "default",
                "default",
                true);
        var session = GameSession.waiting(UUID.randomUUID(), room, Instant.EPOCH);
        for (int p = 0; p < 32; p++) session.join(UUID.randomUUID());
        var map = TestSupport.map(root.resolve("template"));
        session.prepare(map, new Random(cycle));
        session.starting(
            new GameWorld(
                session.sessionId(),
                room.id(),
                "logical-" + session.sessionId(),
                root.resolve(session.sessionId().toString()),
                map));
        session.transition(GameState.RUNNING);
        manager.register(session);
        assertEquals(8, session.teams().size());
        for (var team : session.teams().values()) assertTrue(identities.add(team.teamId()));
        var winner = session.teams().values().iterator().next();
        var deaths = new HashMap<UUID, Long>();
        for (var player : session.players().keySet())
          if (!winner.playerIds().contains(player)) {
            session.eliminate(player);
            deaths.put(player, 1L);
          }
        var outcome = new TeamOutcomeResolver().resolve(session, deaths, 1, 0).orElseThrow();
        assertEquals(winner.playerIds(), outcome.winnerIds());
      }
      assertEquals(20, manager.all().size());
      for (var session : List.copyOf(manager.all())) {
        session.transition(GameState.ENDING);
        session.transition(GameState.CLEANUP);
        manager.retire(session);
      }
      assertTrue(manager.all().isEmpty());
    }
    System.out.println(
        "Logical soak: 100 cycles x 20 rooms x 32 players; duration-ms="
            + (System.nanoTime() - start) / 1_000_000);
  }

  @Test
  void zoneHundredSessionsSixtyFourParticipantsBounded() {
    long samples = 0;
    for (int cycle = 0; cycle < 100; cycle++)
      for (int session = 0; session < 100; session++) {
        var zone = new Zone(session * 2000, 0, 500 - cycle);
        for (int player = 0; player < 64; player++) {
          double x = zone.maxX() - player;
          assertTrue(Double.isFinite(zone.distanceOutside(x, 20)));
          var points = ParticleWall.sample(zone, x, 64, zone.maxZ() - 2, ZoneUiSettings.DEFAULT);
          assertTrue(points.size() <= ZoneUiSettings.DEFAULT.maximumParticles());
          samples += points.size();
        }
      }
    System.out.println(
        "Zone logical stress: 100 sessions x 64 participants x 100 iterations; particle samples="
            + samples);
  }

  @Test
  void oneHundredThousandDamageEventsExpireWithoutCrossSessionAttribution() {
    var clock = new long[] {0};
    var trackers = new ArrayList<CombatTracker>();
    var rosters = new ArrayList<List<UUID>>();
    for (int i = 0; i < 20; i++) {
      var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
      rosters.add(ids);
      trackers.add(new CombatTracker(Set.copyOf(ids), CombatSettings.DEFAULT, () -> clock[0]));
    }
    for (int i = 0; i < 100000; i++) {
      int index = i % 20;
      var ids = rosters.get(index);
      trackers.get(index).record(ids.get(0), ids.get(1), 1, DamageOrigin.PLAYER_MELEE, true);
    }
    for (int i = 0; i < 20; i++) {
      var ids = rosters.get(i);
      assertTrue(trackers.get(i).snapshot().size() <= 4096);
      assertEquals(
          ids.get(1),
          trackers
              .get(i)
              .resolve(ids.get(0), null, DamageOrigin.FALL, x -> true)
              .killer()
              .orElseThrow());
    }
    clock[0] = Duration.ofMinutes(5).toNanos();
    for (var tracker : trackers) assertTrue(tracker.snapshot().isEmpty());
    System.out.println(
        "Combat logical stress: 100000 events / 20 isolated sessions, bounded history, lazy expiry"
            + " verified");
  }

  @Test
  void thousandOutboxResultsTransientFailureRestartReplay() throws Exception {
    long start = System.nanoTime();
    var provider = new JdbcStorageProvider(StorageSettings.defaults("sqlite", root));
    new JdbcRecoveryRepository(provider).migrate();
    var repo = new PermanentRepository(provider, RankingSettings.defaults());
    var outbox = new ResultOutbox(root.resolve("outbox"));
    UUID player = UUID.randomUUID();
    for (int i = 0; i < 1000; i++) {
      UUID team = UUID.randomUUID();
      var p =
          new MatchResult.PlayerResult(
              player, "Stress", team, 1, true, false, 1, 0, 0, 5, 20, 1000, 0, 1000, 0);
      outbox.persist(
          new MatchResult(
              UUID.randomUUID(),
              "room",
              "city",
              1,
              0,
              1000 + i,
              1,
              1,
              MatchResult.CompletionReason.NORMAL,
              Map.of(team, 1),
              List.of(p),
              PeriodKeys.at(Instant.EPOCH, ZoneOffset.UTC)));
    }
    try (var c = provider.connect();
        var statement = c.createStatement()) {
      statement.execute(
          "CREATE TRIGGER unavailable BEFORE INSERT ON match_results BEGIN SELECT"
              + " RAISE(ABORT,'transient'); END");
    }
    var pending = new ResultOutbox(root.resolve("outbox")).pending();
    for (var result : pending.subList(0, 10))
      assertThrows(Exception.class, () -> repo.finalizeResult(result));
    assertEquals(1000, outbox.pending().size());
    try (var c = provider.connect();
        var statement = c.createStatement()) {
      statement.execute("DROP TRIGGER unavailable");
    }
    for (var result : pending) {
      repo.finalizeResult(result);
      repo.finalizeResult(result);
      outbox.acknowledge(result.sessionId());
    }
    assertTrue(new ResultOutbox(root.resolve("outbox")).pending().isEmpty());
    assertEquals(1000, repo.load(player, "Stress", 9999).matches());
    assertEquals(10000, repo.load(player, "Stress", 9999).killScore());
    try (var c = provider.connect();
        var statement = c.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM match_results")) {
      assertTrue(rows.next());
      assertEquals(1000, rows.getInt(1));
    }
    System.out.println(
        "SQLite integration stress: 1000 durable results, 10 transient failures, restart/replay and"
            + " duplicate attempts; duration-ms="
            + (System.nanoTime() - start) / 1_000_000);
  }

  @Test
  void checkpointCoalescingKeepsQueueBoundedAndRevisionsMonotonic() throws Exception {
    var provider = new JdbcStorageProvider(StorageSettings.defaults("sqlite", root));
    var storage = new RecoveryStorage(provider, message -> {});
    var init = storage.initialize();
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (!init.isDone() && System.nanoTime() < deadline) {
      storage.pump();
      Thread.sleep(1);
    }
    init.join();
    UUID id = UUID.randomUUID();
    for (int i = 1; i <= 5000; i++)
      storage.checkpoint(
          new RecoveryRepository.Row(
              id, "stress", "city", "world", "RUNNING", i, 1, "{}", "hash", 0, "ACTIVE"));
    while (storage.written(id) < 5000 && System.nanoTime() < deadline) {
      storage.pump();
      Thread.sleep(1);
    }
    assertEquals(5000, storage.written(id));
    assertTrue(storage.diagnostics().contains("queue=0"));
    storage.close();
    System.out.println(
        "SQLite checkpoints: 5000 revisions coalesced to latest, bounded queue: "
            + storage.diagnostics());
  }

  @Test
  @Tag("mysql")
  void mysqlThousandIdempotentResultsAndPeriodReads() throws Exception {
    var provider =
        new JdbcStorageProvider(
            new StorageSettings(
                "mysql",
                root.resolve("unused"),
                "127.0.0.1",
                Integer.parseInt(System.getenv("BATTLEROYALE_MYSQL_TEST_PORT")),
                "battleroyale_test",
                "battleroyale_test",
                "battleroyale-isolated-test",
                5000));
    new JdbcRecoveryRepository(provider).migrate();
    var repo = new PermanentRepository(provider, RankingSettings.defaults());
    UUID player = UUID.randomUUID();
    long started = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
      UUID team = UUID.randomUUID();
      var p =
          new MatchResult.PlayerResult(
              player, "MySqlStress", team, 1, true, false, 1, 0, 0, 5, 20, 1000, 0, 1000, 0);
      var result =
          new MatchResult(
              UUID.randomUUID(),
              "room",
              "city",
              1,
              0,
              1000 + i,
              1,
              1,
              MatchResult.CompletionReason.NORMAL,
              Map.of(team, 1),
              List.of(p),
              PeriodKeys.at(Instant.EPOCH, ZoneOffset.UTC));
      assertEquals(repo.finalizeResult(result), repo.finalizeResult(result));
    }
    assertEquals(1000, repo.load(player, "MySqlStress", 9999).matches());
    var boards = new LeaderboardRepository(provider);
    assertFalse(
        boards
            .query(
                new LeaderboardRepository.Query(
                    LeaderboardRepository.Scope.DAY,
                    "1970-01-01",
                    LeaderboardRepository.Metric.WINS,
                    0,
                    45))
            .isEmpty());
    System.out.println(
        "MySQL 8.4 stress: 1000 results, duplicate retries and period leaderboard; duration-ms="
            + (System.nanoTime() - started) / 1_000_000);
  }

  @Test
  void fiveHundredOfflineBodiesResolveOnce() {
    var bodies = new ArrayList<com.npucraft.battleroyale.offline.OfflineBody>();
    for (int i = 0; i < 500; i++) {
      var snapshot =
          new com.npucraft.battleroyale.offline.BodySnapshot(
              new com.npucraft.battleroyale.offline.BodyPosition(UUID.randomUUID(), 1, 64, 2, 45, 10),
              12,
              20,
              3,
              14,
              2,
              20,
              100,
              2,
              List.of(),
              Map.of(),
              null,
              2,
              4,
              .5f,
              44);
      var body =
          new com.npucraft.battleroyale.offline.OfflineBody(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "P",
              snapshot,
              0,
              Duration.ofSeconds(120));
      body.spawned(UUID.randomUUID());
      bodies.add(body);
    }
    for (int i = 0; i < bodies.size(); i++) {
      var body = bodies.get(i);
      if (i % 2 == 0) {
        assertTrue(body.beginReconnect(1));
        body.restored();
        assertFalse(body.eliminate());
      } else {
        assertTrue(body.expired(120_000_000_000L));
        assertTrue(body.eliminate());
        assertFalse(body.eliminate());
      }
    }
    assertTrue(bodies.stream().noneMatch(com.npucraft.battleroyale.offline.OfflineBody::active));
    bodies.clear();
    assertTrue(bodies.isEmpty());
  }

  @Test void fiveHundredLogicalDeathBoxesAreIdempotentAndClear() {
    var room=new RoomDefinition("boxes","Boxes",1,512,1,Duration.ZERO,List.of("city"),"default","default",true);
    var session=GameSession.waiting(UUID.randomUUID(),room,Instant.EPOCH);for(int i=0;i<500;i++)session.join(UUID.randomUUID());var map=TestSupport.map(root.resolve("map"));session.prepare(map);var world=new GameWorld(session.sessionId(),room.id(),"world",root.resolve("world"),map);session.starting(world);session.transition(GameState.RUNNING);var tracker=new CombatTracker(session.players().keySet(),CombatSettings.DEFAULT,()->0);var committed=new ArrayList<com.npucraft.battleroyale.death.DeathBox>();var elimination=new com.npucraft.battleroyale.death.EliminationService(session,tracker,0,committed::add);
    for(var id:session.players().keySet()){var request=new com.npucraft.battleroyale.death.EliminationRequest(id,"Player",new com.npucraft.battleroyale.death.DeathPosition(UUID.randomUUID(),0,64,0),DamageOrigin.FALL,null,List.of(),0,1,1);assertTrue(elimination.eliminate(request).isPresent());assertTrue(elimination.eliminate(request).isEmpty());}
    assertEquals(500,committed.size());assertEquals(500,elimination.boxes().size());elimination.clear();assertTrue(elimination.boxes().isEmpty());assertTrue(elimination.eliminationTicks().isEmpty());System.out.println("DeathBox logical stress: 500 once-only eliminations, complete registry cleanup; Paper shared inventory is separately integration-tested");
  }
  @Test void hundredOverlappingPurchaseClicksHaveOneExternalWithdrawal()throws Exception {
    var provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));new JdbcRecoveryRepository(provider).migrate();var repository=new com.npucraft.battleroyale.cosmetic.CosmeticRepository(provider);var queued=new ArrayDeque<Runnable>();var service=new com.npucraft.battleroyale.cosmetic.PurchaseService(repository,new com.npucraft.battleroyale.cosmetic.PurchaseService.AsyncDatabase(){public <T> java.util.concurrent.CompletableFuture<T> call(java.util.concurrent.Callable<T> work){var result=new java.util.concurrent.CompletableFuture<T>();queued.add(()->{try{result.complete(work.call());}catch(Exception e){result.completeExceptionally(e);}});return result;}});
    var withdrawals=new java.util.concurrent.atomic.AtomicInteger();var economy=new com.npucraft.battleroyale.api.economy.EconomyProvider(){public boolean isAvailable(){return true;}public java.math.BigDecimal getBalance(UUID p){return java.math.BigDecimal.valueOf(1000);}public boolean has(UUID p,java.math.BigDecimal a){return true;}public boolean deposit(UUID p,java.math.BigDecimal a){return true;}public boolean withdraw(UUID p,java.math.BigDecimal a){withdrawals.incrementAndGet();return true;}};
    var definition=new com.npucraft.battleroyale.cosmetic.CosmeticDefinition("stress",com.npucraft.battleroyale.cosmetic.CosmeticCategory.KILL_EFFECT,"Stress",List.of(),"DIAMOND",java.math.BigDecimal.TEN,"particle_burst",Map.of());UUID player=UUID.randomUUID();var first=service.buy(player,definition,economy,"contract","coins");for(int i=1;i<100;i++)assertThrows(java.util.concurrent.CompletionException.class,()->service.buy(player,definition,economy,"contract","coins").join());while(!queued.isEmpty())queued.remove().run();assertEquals("Purchased",first.join());assertEquals(1,withdrawals.get());assertTrue(repository.owns(player,"stress"));System.out.println("Purchase contract stress: 100 overlapping clicks, one withdrawal and one unlock");
  }
}
