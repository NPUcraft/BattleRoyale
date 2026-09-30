package com.npucraft.battleroyale.paper;

import java.util.concurrent.CompletionStage;
import java.util.function.*;

/** Owner-thread throttle with one in-flight request; completion is dispatched before any UI mutation. */
public final class LobbyBoardRefresh<T> implements AutoCloseable {
    private final long interval;
    private final LongSupplier clock;
    private final Supplier<? extends CompletionStage<T>> source;
    private final Consumer<Runnable> dispatch;
    private final Consumer<T> accept;
    private final Consumer<Throwable> errors;
    private long lastRequest;private boolean requested,inFlight;private volatile boolean closed;
    public LobbyBoardRefresh(long interval,LongSupplier clock,Supplier<? extends CompletionStage<T>> source,Consumer<Runnable> dispatch,Consumer<T> accept,Consumer<Throwable> errors){
        if(interval<=0)throw new IllegalArgumentException("Refresh interval must be positive");this.interval=interval;this.clock=clock;this.source=source;this.dispatch=dispatch;this.accept=accept;this.errors=errors;
    }
    public void tick(boolean available){
        if(closed||!available||inFlight)return;long now=clock.getAsLong();if(requested&&now-lastRequest<interval)return;
        requested=true;lastRequest=now;inFlight=true;
        try{source.get().whenComplete((value,error)->dispatch.accept(()->{
            if(closed)return;inFlight=false;
            if(error!=null){errors.accept(error);return;}
            try{accept.accept(value);}catch(RuntimeException presentation){errors.accept(presentation);}
        }));}
        catch(RuntimeException error){inFlight=false;errors.accept(error);}
    }
    public boolean inFlight(){return inFlight;}
    @Override public void close(){closed=true;}
}
