package com.lastsector.progression;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/** Coalesces in-flight identical queries and caches successful pages for a bounded TTL. */
public final class LeaderboardCache {
    private record Entry(long expires,CompletableFuture<List<LeaderboardRepository.Row>> future) {}
    private final Map<LeaderboardRepository.Query,Entry> entries=new LinkedHashMap<>();
    private final long ttl;
    private final LongSupplier now;
    public LeaderboardCache(long ttlMillis,LongSupplier now) {if(ttlMillis<=0)throw new IllegalArgumentException("Invalid TTL");ttl=ttlMillis;this.now=now;}
    public synchronized CompletableFuture<List<LeaderboardRepository.Row>> get(LeaderboardRepository.Query key,Supplier<CompletableFuture<List<LeaderboardRepository.Row>>> loader) {
        var old=entries.get(key);long time=now.getAsLong();
        if(old!=null && (!old.future().isDone() || old.expires()>time))return old.future();
        var future=new CompletableFuture<List<LeaderboardRepository.Row>>();entries.put(key,new Entry(Long.MAX_VALUE,future));
        while(entries.size()>512) {var evict=entries.entrySet().stream().filter(e->e.getValue().future().isDone()).findFirst();if(evict.isEmpty())break;entries.remove(evict.get().getKey());}
        try {loader.get().whenComplete((rows,error)->{
            synchronized(this) {
                if(entries.get(key)!=null && entries.get(key).future()==future) {
                    if(error!=null)entries.remove(key);else entries.put(key,new Entry(now.getAsLong()+ttl,future));
                }
            }
            if(error!=null)future.completeExceptionally(error);else future.complete(List.copyOf(rows));
        });}catch(Throwable error){entries.remove(key);future.completeExceptionally(error);}
        return future;
    }
    public synchronized void invalidate() {entries.clear();}
}
