package com.npucraft.battleroyale.progression;
import com.npucraft.battleroyale.storage.*;
import com.npucraft.battleroyale.cosmetic.*;
import com.npucraft.battleroyale.economy.*;
import com.npucraft.battleroyale.api.economy.EconomyProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class PermanentContractsTest {
    @TempDir Path root;
    StorageProvider provider;CosmeticRepository cosmetics;PermanentRepository profiles;
    @BeforeEach void setup()throws Exception {
        provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));new JdbcRecoveryRepository(provider).migrate();
        cosmetics=new CosmeticRepository(provider);profiles=new PermanentRepository(provider,RankingSettings.defaults());
    }
    static CosmeticDefinition definition(String id,CosmeticCategory category,int price) {
        return new CosmeticDefinition(id,category,id,List.of(),"EMERALD",BigDecimal.valueOf(price),category==CosmeticCategory.DEATHBOX_SKIN?"block_display":category==CosmeticCategory.WIN_EFFECT?"firework_burst":"particle_burst",Map.of());
    }
    static final class Economy implements EconomyProvider {
        BigDecimal balance=BigDecimal.valueOf(1000);int withdrawals,deposits;boolean failWithdraw,failRefund;
        public boolean isAvailable(){return true;}public BigDecimal getBalance(UUID p){return balance;}
        public boolean has(UUID p,BigDecimal a){return balance.compareTo(a)>=0;}
        public boolean withdraw(UUID p,BigDecimal a){withdrawals++;if(failWithdraw||!has(p,a))return false;balance=balance.subtract(a);return true;}
        public boolean deposit(UUID p,BigDecimal a){deposits++;if(failRefund)return false;balance=balance.add(a);return true;}
    }
    PurchaseService service(){return new PurchaseService(cosmetics,new PurchaseService.AsyncDatabase(){public <T> CompletableFuture<T> call(Callable<T> work){try{return CompletableFuture.completedFuture(work.call());}catch(Exception e){return CompletableFuture.failedFuture(e);}}});}
    @Test void successfulPurchaseAndDuplicateAndFree()throws Exception {
        UUID player=UUID.randomUUID();var economy=new Economy();var service=service();var item=definition("effect",CosmeticCategory.KILL_EFFECT,10);
        assertEquals("Purchased",service.buy(player,item,economy,"test","coins").join());assertTrue(cosmetics.owns(player,"effect"));
        assertEquals("Already owned",service.buy(player,item,economy,"test","coins").join());assertEquals(1,economy.withdrawals);
        service.buy(player,definition("free",CosmeticCategory.LOBBY_EFFECT,0),null,"none","coins").join();assertTrue(cosmetics.owns(player,"free"));assertEquals(1,economy.withdrawals);
    }
    @Test void insufficientAndFailedWithdrawNeverUnlock()throws Exception {
        UUID player=UUID.randomUUID();var economy=new Economy();var service=service();var item=definition("effect",CosmeticCategory.KILL_EFFECT,2000);
        assertThrows(CompletionException.class,()->service.buy(player,item,economy,"test","coins").join());assertEquals(0,economy.withdrawals);
        economy.failWithdraw=true;assertEquals("Withdrawal declined",service.buy(player,definition("effect",CosmeticCategory.KILL_EFFECT,10),economy,"test","coins").join());assertFalse(cosmetics.owns(player,"effect"));
    }
    @Test void unlockFailureCompensatesOrRequiresManualReview()throws Exception {
        try(var c=provider.connect();var s=c.createStatement()){s.executeUpdate("CREATE TRIGGER reject_unlock BEFORE INSERT ON cosmetic_unlocks BEGIN SELECT RAISE(ABORT,'fixture'); END");}
        UUID player=UUID.randomUUID();var economy=new Economy();var service=service();var item=definition("effect",CosmeticCategory.KILL_EFFECT,10);
        assertEquals("Purchase failed; refunded",service.buy(player,item,economy,"test","coins").join());assertEquals(BigDecimal.valueOf(1000),economy.balance);assertFalse(cosmetics.owns(player,"effect"));
        economy.failRefund=true;assertTrue(service.buy(player,item,economy,"test","coins").join().contains("review"));assertEquals(1,cosmetics.manualReview(0).size());assertFalse(cosmetics.owns(player,"effect"));
    }
    @Test void ambiguousRestartDoesNotRetryExternalSideEffects()throws Exception {
        UUID player=UUID.randomUUID();cosmetics.intent(new Purchase(UUID.randomUUID(),player,"effect","vault","default",BigDecimal.TEN,Purchase.Status.WITHDRAWING,1,1));
        cosmetics.quarantineAmbiguous();cosmetics.quarantineAmbiguous();assertEquals(1,cosmetics.manualReview(0).size());assertFalse(cosmetics.owns(player,"effect"));
    }
    @Test void equipPreservesOtherCategoriesAndRevokeUnequips()throws Exception {
        UUID id=UUID.randomUUID();profiles.load(id,"Player",1);
        cosmetics.grant(id,"a",1);cosmetics.grant(id,"b",1);cosmetics.grant(id,"win",1);
        cosmetics.equip(id,CosmeticCategory.KILL_EFFECT,"a");cosmetics.equip(id,CosmeticCategory.WIN_EFFECT,"win");cosmetics.equip(id,CosmeticCategory.KILL_EFFECT,"b");
        var profile=profiles.load(id,"Player",2);assertEquals(Map.of("KILL_EFFECT","b","WIN_EFFECT","win"),profile.equipped());assertTrue(profile.unlocks().contains("a"));
        cosmetics.revoke(id,"b");assertEquals(Map.of("WIN_EFFECT","win"),profiles.load(id,"Player",3).equipped());assertThrows(Exception.class,()->cosmetics.equip(id,CosmeticCategory.KILL_EFFECT,"missing"));
    }
    @Test void moneyRejectsSilentRoundingOverflowAndNegative() {
        assertEquals(1.25,Money.amount(new BigDecimal("1.25"),2));assertEquals(0,Money.amount(BigDecimal.ZERO,0));
        assertThrows(ArithmeticException.class,()->Money.amount(new BigDecimal("1.001"),2));assertThrows(IllegalArgumentException.class,()->Money.amount(BigDecimal.valueOf(-1),2));
        assertThrows(IllegalArgumentException.class,()->Money.amount(new BigDecimal("1e999"),2));assertThrows(IllegalArgumentException.class,()->Money.amount(new BigDecimal("9007199254740993"),0));
    }
    @Test void explicitProviderNeverFallsBack() {
        var available=new Economy();var requested=new ArrayList<String>();
        var selection=EconomySelection.select("vault",List.of("coinsengine","vault"),"coins",true,name->{requested.add(name);return name.equals("coinsengine")?available:null;});
        assertNull(selection.provider());assertFalse(selection.shopEnabled());assertEquals(List.of("vault"),requested);
        assertEquals("coinsengine",EconomySelection.select("auto",List.of("coinsengine","vault"),"coins",true,name->available).active());
    }
    @Test void leaderboardCacheCoalescesAndSeparatesPagesPeriodsMetrics() {
        AtomicLong now=new AtomicLong();var cache=new LeaderboardCache(30,now::get);var future=new CompletableFuture<List<LeaderboardRepository.Row>>();var calls=new AtomicInteger();
        var key=new LeaderboardRepository.Query(LeaderboardRepository.Scope.DAY,"2026-09-22",LeaderboardRepository.Metric.RATING,0,10);
        var first=cache.get(key,()->{calls.incrementAndGet();return future;});assertSame(first,cache.get(key,()->{throw new AssertionError();}));future.complete(List.of());assertEquals(1,calls.get());
        now.set(31);cache.get(key,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(List.of());}).join();assertEquals(2,calls.get());
        for(var other:List.of(new LeaderboardRepository.Query(key.scope(),key.period(),key.metric(),1,10),new LeaderboardRepository.Query(key.scope(),"2026-09-21",key.metric(),0,10),new LeaderboardRepository.Query(key.scope(),key.period(),LeaderboardRepository.Metric.KILLS,0,10)))cache.get(other,()->{calls.incrementAndGet();return CompletableFuture.completedFuture(List.of());}).join();
        assertEquals(5,calls.get());
    }
    @Test void v1MigrationPreservesRecoveryAndRestorationRecords()throws Exception {
        try(var c=provider.connect();var s=c.createStatement()) {
            for(String table:List.of("player_profiles","match_results","player_match_results","player_period_stats","cosmetic_unlocks","cosmetic_equipped","cosmetic_purchase_ledger"))s.executeUpdate("DROP TABLE "+table);
            s.executeUpdate("UPDATE battleroyale_schema SET version=1");
            s.executeUpdate("INSERT INTO recovery_sessions VALUES('session','room','map','world','RUNNING',9,1,'payload','hash',10,'ACTIVE','owner',11)");
            s.executeUpdate("INSERT INTO pending_player_restores VALUES('player','session','generation',1,'original','hash','PENDING',10)");
            assertEquals(2,SchemaMigrations.migrate(c,"sqlite"));
            try(var row=s.executeQuery("SELECT payload,revision FROM recovery_sessions WHERE session_id='session'")){assertTrue(row.next());assertEquals("payload",row.getString(1));assertEquals(9,row.getInt(2));}
            try(var row=s.executeQuery("SELECT payload FROM pending_player_restores WHERE player_uuid='player'")){assertTrue(row.next());assertEquals("original",row.getString(1));}
        }
    }
    @Test void leaderboardQueriesUsePeriodDeltasAndDatabasePagination()throws Exception {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();profiles.load(first,"Alpha",1);profiles.load(second,"Beta",1);
        try(var c=provider.connect();var s=c.createStatement()) {
            s.executeUpdate("UPDATE player_profiles SET rating=2000 WHERE player_uuid='"+first+"'");
            s.executeUpdate("INSERT INTO player_period_stats(period_type,period_key,player_uuid,rating_delta) VALUES('DAY','2026-09-22','"+first+"',5),('DAY','2026-09-22','"+second+"',40),('DAY','2026-09-21','"+first+"',99)");
        }
        var repo=new LeaderboardRepository(provider);
        assertEquals(first,repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.LIFETIME,"",LeaderboardRepository.Metric.RATING,0,1)).getFirst().player());
        assertEquals(second,repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.DAY,"2026-09-22",LeaderboardRepository.Metric.RATING,0,1)).getFirst().player());
        assertEquals(first,repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.DAY,"2026-09-22",LeaderboardRepository.Metric.RATING,1,1)).getFirst().player());
        assertTrue(repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.DAY,"2026-09-22",LeaderboardRepository.Metric.RATING,2,1)).isEmpty());
        assertEquals(99,repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.DAY,"2026-09-21",LeaderboardRepository.Metric.RATING,0,1)).getFirst().value());
        assertEquals(first,repo.query(new LeaderboardRepository.Query(LeaderboardRepository.Scope.LIFETIME,"",LeaderboardRepository.Metric.WINS,0,1)).getFirst().player());
    }
    @Test void optionalLinkageFailureDoesNotBreakCoreOrExplicitFallbackPolicy() {
        var available=new Economy();
        assertEquals("vault",EconomySelection.select("auto",List.of("coinsengine","vault"),"coins",true,name->{if(name.equals("coinsengine"))throw new NoClassDefFoundError("optional");return available;}).active());
        assertNull(EconomySelection.select("coinsengine",List.of("coinsengine","vault"),"coins",true,name->{throw new NoClassDefFoundError("optional");}).provider());
    }
    @Test void incompatibleModernApiFallsBackAndDiagnosticsAreSafe() {
        var available=new Economy();
        assertEquals("vault",EconomySelection.select("auto",List.of("excellenteconomy","vault"),"coins",true,name->{if(name.equals("excellenteconomy"))throw new UnsupportedClassVersionError("Java 25 API on Java 21");return available;}).active());
        assertFalse(EconomySelection.select("auto",List.of("excellenteconomy","vault"),"coins",true,name->null).shopEnabled());
    }
    @Test void pendingPurchaseRejectsConcurrentRequestBeforeAnyWithdrawal()throws Exception {
        var queued=new ArrayList<Runnable>();var service=new PurchaseService(cosmetics,new PurchaseService.AsyncDatabase(){public <T> CompletableFuture<T> call(Callable<T> work){var result=new CompletableFuture<T>();queued.add(()->{try{result.complete(work.call());}catch(Exception e){result.completeExceptionally(e);}});return result;}});
        UUID player=UUID.randomUUID();var economy=new Economy();var definition=definition("effect",CosmeticCategory.KILL_EFFECT,10);
        var first=service.buy(player,definition,economy,"test","coins");assertThrows(CompletionException.class,()->service.buy(player,definition,economy,"test","coins").join());
        while(!queued.isEmpty())queued.removeFirst().run();assertEquals("Purchased",first.join());assertEquals(1,economy.withdrawals);
    }

}
