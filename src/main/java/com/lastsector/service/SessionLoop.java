package com.lastsector.service;
import java.util.function.Consumer;
/** A session owns exactly one loop. A failed tick cancels its task before invoking rollback. */
public final class SessionLoop implements AutoCloseable {
    private final GameScheduler.Task task;
    private boolean closed;
    public SessionLoop(GameScheduler scheduler,Runnable tick,Consumer<Throwable> failure) {
        task=scheduler.repeat(1,()-> {
            if(closed) return;
            try { tick.run(); }
            catch(Throwable error) { close(); failure.accept(error); }
        });
    }
    @Override public void close() { if(!closed) { closed=true; task.cancel(); } }
}

