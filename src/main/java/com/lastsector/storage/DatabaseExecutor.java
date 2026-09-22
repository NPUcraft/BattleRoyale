package com.lastsector.storage;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** One bounded worker provides ordering; rejection never executes IO on the submitting server thread. */
public final class DatabaseExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;private final java.util.concurrent.atomic.AtomicInteger maximumDepth=new java.util.concurrent.atomic.AtomicInteger();
    public int maximumDepth(){return maximumDepth.get();}
    public DatabaseExecutor(int capacity,Consumer<Throwable> errors){executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{var t=new Thread(r,"LastSector-database");t.setUncaughtExceptionHandler((thread,error)->errors.accept(error));return t;},new ThreadPoolExecutor.AbortPolicy());}
    public <T> CompletableFuture<T> submit(Callable<T> work){var result=new CompletableFuture<T>();try{executor.execute(()->{try{result.complete(work.call());}catch(Throwable error){result.completeExceptionally(error);}});}catch(RejectedExecutionException error){result.completeExceptionally(error);}maximumDepth.accumulateAndGet(depth(),Math::max);return result;}
    public int depth(){return executor.getQueue().size();}
    public void close(){executor.shutdown();}
    public boolean await(long seconds) throws InterruptedException{return executor.awaitTermination(seconds,TimeUnit.SECONDS);}
}
