package com.lastsector.storage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** Async IO, bounded/coalesced checkpoints, health and server-thread completion dispatch. */
public final class RecoveryStorage implements AutoCloseable {
    public static final long LEASE_MILLIS=30_000;
    private final DatabaseExecutor worker;private final RecoveryRepository repository;private final String provider;
    private final Queue<Runnable> completions=new ConcurrentLinkedQueue<>();private final Consumer<String> log;
    private final Map<UUID,RecoveryRepository.Row> latest=new HashMap<>();private final Set<UUID> writing=new HashSet<>(),retired=new HashSet<>();
    private final Map<UUID,Long> revisions=new HashMap<>();private final UUID owner=UUID.randomUUID();
    private volatile boolean connected,closed;private int schema;private volatile long lastWrite,lastErrorLog;private volatile String lastFailure="none";
    public RecoveryStorage(StorageProvider provider,Consumer<String> log){this.provider=provider.type();this.repository=new JdbcRecoveryRepository(provider);this.log=log;worker=new DatabaseExecutor(128,error->log.accept("Database worker failed: "+error.getClass().getSimpleName()));}
    public RecoveryRepository repository(){return repository;}public UUID owner(){return owner;}public boolean healthy(){return connected;}public int schema(){return schema;}
    public <T> CompletableFuture<T> call(Callable<T> work) {
        var result=new CompletableFuture<T>();if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Storage closed"));
        worker.submit(()->{long started=System.nanoTime();try{return work.call();}finally{com.lastsector.admin.PerformanceMetricsService.LIVE.record(com.lastsector.admin.PerformanceMetricsService.Timer.DB_OPERATION,System.nanoTime()-started);}}).whenComplete((value,error)->{if(error!=null)failure(error);else connected=true;completions.add(()->{if(error==null)result.complete(value);else result.completeExceptionally(new IllegalStateException(error instanceof com.lastsector.recovery.RecoveryPlan.Rejected ? error.getMessage() : "Recovery storage operation failed; see redacted storage diagnostics"));});});return result;
    }
    public CompletableFuture<Void> initialize(){return call(repository::migrate).thenAccept(version->schema=version);}
    public void pump(){for(int i=0;i<128;i++){var completion=completions.poll();if(completion==null)break;completion.run();}}
    private void failure(Throwable error){connected=false;lastFailure=error.getClass().getSimpleName();long now=System.currentTimeMillis();if(now-lastErrorLog>=30_000){lastErrorLog=now;log.accept("Recovery storage DEGRADED ("+lastFailure+"); writes will retry. Credentials/payload omitted.");}}
    public void checkpoint(RecoveryRepository.Row row){if(retired.contains(row.session()))return;latest.compute(row.session(),(id,previous)->previous==null || row.revision()>previous.revision()?row:previous);flush(row.session());}
    private void flush(UUID id) {
        var row=latest.get(id);if(row==null || retired.contains(id) || !writing.add(id))return;
        call(()->{long started=System.nanoTime();try{return repository.save(row,owner,System.currentTimeMillis()+LEASE_MILLIS);}finally{com.lastsector.admin.PerformanceMetricsService.LIVE.record(com.lastsector.admin.PerformanceMetricsService.Timer.CHECKPOINT,System.nanoTime()-started);com.lastsector.admin.PerformanceMetricsService.LIVE.record(com.lastsector.admin.PerformanceMetricsService.Timer.DB_WRITE,System.nanoTime()-started);}}).whenComplete((saved,error)->{
            writing.remove(id);if(error!=null)return;
            if(!saved){failure(new IllegalStateException("Revision/ownership rejected"));return;}
            lastWrite=System.currentTimeMillis();revisions.put(id,row.revision());latest.remove(id,row);if(latest.containsKey(id))flush(id);
        });
    }
    public void retry(){if(!connected && latest.isEmpty())call(repository::restores);for(UUID id:List.copyOf(latest.keySet()))flush(id);}
    public CompletableFuture<Void> retire(UUID id,String status){retired.add(id);latest.remove(id);return call(()->{repository.retire(id,owner,status);return null;});}
    public long written(UUID id){return revisions.getOrDefault(id,0L);}
    public String diagnostics(){return "provider="+provider+" connected="+connected+" schema="+schema+" queue="+worker.depth()+" maxQueue="+worker.maximumDepth()+" coalesced="+latest.size()+" lastSuccessfulWrite="+lastWrite+" lastFailure="+lastFailure;}
    public void close(){closed=true;worker.close();}
}
