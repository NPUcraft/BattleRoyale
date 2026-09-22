package com.lastsector.storage;
import java.nio.file.Path;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
/** Opt-in real MySQL contract; set LASTSECTOR_MYSQL_TEST_PORT for a disposable lastsector_test database. */
@org.junit.jupiter.api.Tag("mysql")
class MySqlRecoveryContractTest {
 @Test void realServerTransactionsMigrationsRevisionClaimsAndGenerations()throws Exception {
  String port=System.getenv("LASTSECTOR_MYSQL_TEST_PORT");assertNotNull(port,"Explicit MySQL test endpoint required");
  var provider=new JdbcStorageProvider(new StorageSettings("mysql",Path.of("unused"),"127.0.0.1",Integer.parseInt(port),"lastsector_test","lastsector_test","lastsector-isolated-test",5000));var repo=new JdbcRecoveryRepository(provider);
  assertEquals(SchemaMigrations.VERSION,repo.migrate());assertEquals(SchemaMigrations.VERSION,repo.migrate());UUID session=UUID.randomUUID(),owner=UUID.randomUUID(),next=UUID.randomUUID(),player=UUID.randomUUID(),generation=UUID.randomUUID();
  var row=new RecoveryRepository.Row(session,"contract","map","world","RUNNING",2,1,"{}","hash",0,"ACTIVE");assertTrue(repo.save(row,owner,100));assertFalse(repo.save(row,owner,100));assertFalse(repo.claim(session,next,99,200));assertTrue(repo.claim(session,next,100,200));
  var original=new RecoveryRepository.Restore(player,session,generation,1,"{}","hash","ORIGINAL",0);repo.originals(List.of(original));assertThrows(Exception.class,()->repo.originals(List.of(original)));assertThrows(Exception.class,()->repo.retire(session,owner,"COMPLETED"));repo.retire(session,next,"COMPLETED");assertEquals("PENDING",repo.restores().stream().filter(r->r.player().equals(player)).findFirst().orElseThrow().status());
  var permanent=new com.lastsector.progression.PermanentRepository(provider,com.lastsector.progression.RankingSettings.defaults());
  UUID team=UUID.randomUUID(),resultId=UUID.randomUUID();
  var resultPlayer=new com.lastsector.progression.MatchResult.PlayerResult(player,"Contract",team,1,true,false,2,0,1,10,12,1000,0,1000,0);
  var result=new com.lastsector.progression.MatchResult(resultId,"solo","map",1,0,1,1,1,com.lastsector.progression.MatchResult.CompletionReason.NORMAL,Map.of(team,1),List.of(resultPlayer),com.lastsector.progression.PeriodKeys.at(java.time.Instant.EPOCH,java.time.ZoneOffset.UTC));
  var saved=permanent.finalizeResult(result);assertEquals(saved,permanent.finalizeResult(result));assertEquals(1,permanent.load(player,"Contract",2).matches());assertEquals(23,permanent.load(player,"Contract",3).killScore());
  var cosmetics=new com.lastsector.cosmetic.CosmeticRepository(provider);cosmetics.grant(player,"effect",0);cosmetics.equip(player,com.lastsector.cosmetic.CosmeticCategory.KILL_EFFECT,"effect");cosmetics.revoke(player,"effect");assertTrue(permanent.load(player,"Contract",4).equipped().isEmpty());
  assertTrue(repo.applied(player,generation));repo.deleteRestore(player,UUID.randomUUID());assertTrue(repo.restores().stream().anyMatch(r->r.player().equals(player)));repo.deleteRestore(player,generation);assertFalse(repo.restores().stream().anyMatch(r->r.player().equals(player)));assertFalse(repo.save(row,next,200));
 }
}
