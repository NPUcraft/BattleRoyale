package com.npucraft.lastsector.storage;
import java.nio.file.Path;import java.util.*;import java.util.concurrent.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RecoveryStorageTest {
 @TempDir Path root;
 void drain(RecoveryStorage storage,java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+10_000_000_000L;while(!done.getAsBoolean()){storage.pump();if(System.nanoTime()>deadline)fail("storage timeout");Thread.sleep(2);}storage.pump();}
 @Test void ioAndCompletionsRunOnTheirOwningThreadsAndCoalesce()throws Exception{
  var provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));try(var storage=new RecoveryStorage(provider,s->{})){
   var init=storage.initialize();drain(storage,init::isDone);init.get();assertTrue(storage.healthy());var thread=storage.call(()->Thread.currentThread().getName());String caller=Thread.currentThread().getName();var completion=thread.thenApply(name->Thread.currentThread().getName());drain(storage,completion::isDone);assertEquals("LastSector-database",thread.get());assertEquals(caller,completion.get());
   UUID id=UUID.randomUUID();for(long revision=1;revision<=100;revision++)storage.checkpoint(new RecoveryRepository.Row(id,"solo","city","world","RUNNING",revision,1,"{}","hash",0,"ACTIVE"));drain(storage,()->storage.written(id)==100);assertEquals(100,storage.repository().sessions().getFirst().revision());var retired=storage.retire(id,"COMPLETED");drain(storage,retired::isDone);retired.get();storage.checkpoint(new RecoveryRepository.Row(id,"solo","city","world","RUNNING",101,1,"{}","hash",0,"ACTIVE"));assertTrue(storage.repository().sessions().isEmpty());
  }
 }
 @Test void initialConnectionFailureNeverCreatesSchemaOrCallsSuccess()throws Exception{var provider=new StorageProvider(){public String type(){return "sqlite";}public java.sql.Connection connect()throws java.sql.SQLException{throw new java.sql.SQLException("sensitive password");}};var logs=new ArrayList<String>();try(var storage=new RecoveryStorage(provider,logs::add)){var init=storage.initialize();drain(storage,init::isDone);assertTrue(init.isCompletedExceptionally());assertFalse(storage.healthy());assertEquals(0,storage.schema());assertFalse(storage.diagnostics().contains("sensitive"));assertFalse(String.join("",logs).contains("sensitive"));}}
}
