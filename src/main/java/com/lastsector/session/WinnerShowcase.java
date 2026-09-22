package com.lastsector.session;
import com.lastsector.service.GameScheduler;
import com.lastsector.zone.GameClock;
import java.time.Duration;
/** One owned monotonic deadline, at most twelve celebration rounds, and idempotent cancellation. */
public final class WinnerShowcase implements AutoCloseable {
    private final GameClock clock;
    private final long began,duration;
    private final Runnable effect,complete;
    private final java.util.function.Consumer<Throwable> failed;
    private final GameScheduler.Task task;
    private long lastEffect;
    private int rounds=1;
    private boolean closed;
    public WinnerShowcase(GameScheduler scheduler,GameClock clock,Duration duration,Runnable title,Runnable effect,Runnable complete,java.util.function.Consumer<Throwable> failed) {
        this.clock=clock;this.duration=duration.toNanos();this.effect=effect;this.complete=complete;this.failed=failed;began=clock.nanoTime();lastEffect=began;
        title.run();effect.run();task=scheduler.repeat(1,this::tick);
    }
    private void tick() {
        if(closed) return;
        try {
        long now=clock.nanoTime();
        if(now-began>=duration) {close();complete.run();return;}
        if(rounds<12 && now-lastEffect>=5_000_000_000L) {lastEffect=now;rounds++;effect.run();}
        } catch(RuntimeException error) {close();failed.accept(error);}
    }
    public long remainingNanos(){return Math.max(0,duration-(clock.nanoTime()-began));}
    @Override public void close() { if(!closed) {closed=true;task.cancel();} }
}
