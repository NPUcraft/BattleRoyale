package com.npucraft.battleroyale.loot;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Main-thread ownership with asynchronous completions; stopping never cancels a still-running request. */
public final class GroundLootPipeline<C,V> {
    public record Ready<C,V>(C candidate,CompletableFuture<V> future) {}
    private final int capacity;private final List<Ready<C,V>> requests=new ArrayList<>();
    private boolean stopped;private CompletableFuture<Void> drained;
    public GroundLootPipeline(int capacity){if(capacity<1||capacity>32)throw new IllegalArgumentException("Ground pipeline capacity 1..32");this.capacity=capacity;}
    public boolean available(){return !stopped&&requests.size()<capacity;}
    public int size(){return requests.size();}
    public void submit(C candidate,Supplier<CompletableFuture<V>> request){
        if(!available())throw new IllegalStateException("Ground request window full or stopped");
        requests.add(new Ready<>(Objects.requireNonNull(candidate),Objects.requireNonNull(request.get())));
    }
    /** A slow request cannot hold already completed independent candidates behind it. */
    public Optional<Ready<C,V>> takeReady(){
        if(stopped)return Optional.empty();
        for(var iterator=requests.iterator();iterator.hasNext();){var entry=iterator.next();if(entry.future().isDone()){iterator.remove();return Optional.of(entry);}}
        return Optional.empty();
    }
    /** Drains actual request futures, including exceptional completions, without invoking candidate work. */
    public CompletableFuture<Void> stop(){
        stopped=true;
        if(drained==null)drained=CompletableFuture.allOf(requests.stream().map(Ready::future).toArray(CompletableFuture[]::new)).handle((unused,error)->null);
        return drained;
    }
}
