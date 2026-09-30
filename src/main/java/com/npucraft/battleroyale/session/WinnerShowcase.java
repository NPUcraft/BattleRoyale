package com.npucraft.battleroyale.session;
import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.zone.GameClock;
import java.time.Duration;
/** One owned monotonic deadline, at most twelve celebration rounds, and idempotent cancellation. */
public final class WinnerShowcase implements AutoCloseable {
    private final GameClock clock;
    private final long began,duration;
    private final Runnable effect,complete;
    private final java.util.function.IntConsumer countdown;
    private final java.util.function.Consumer<Throwable> failed;
    private final GameScheduler.Task task;
    private long lastEffect;
    private int rounds=1;
    private int displayedSeconds=-1;
    private boolean closed;
    public WinnerShowcase(GameScheduler scheduler,GameClock clock,Duration duration,Runnable title,Runnable effect,Runnable complete,java.util.function.Consumer<Throwable> failed) {
        this(scheduler,clock,duration,title,effect,seconds->{},complete,failed);
    }
    public WinnerShowcase(GameScheduler scheduler,GameClock clock,Duration duration,Runnable title,Runnable effect,java.util.function.IntConsumer countdown,Runnable complete,java.util.function.Consumer<Throwable> failed) {
        if(duration.isNegative())throw new IllegalArgumentException("Showcase duration must not be negative");
        this.clock=clock;this.duration=duration.toNanos();this.effect=effect;this.countdown=countdown;this.complete=complete;this.failed=failed;began=clock.nanoTime();lastEffect=began;
        title.run();displayCountdown();effect.run();task=scheduler.repeat(1,this::tick);
    }
    private void tick() {
        if(closed) return;
        try {
        long now=clock.nanoTime();
        displayCountdown();
        if(now-began>=duration) {close();complete.run();return;}
        if(rounds<12 && now-lastEffect>=5_000_000_000L) {lastEffect=now;rounds++;effect.run();}
        } catch(RuntimeException error) {close();failed.accept(error);}
    }
    public long remainingNanos(){return Math.max(0,duration-(clock.nanoTime()-began));}
    public int remainingSeconds(){long nanos=remainingNanos();return (int)Math.min(Integer.MAX_VALUE,nanos/1_000_000_000L+(nanos%1_000_000_000L==0?0:1));}
    private void displayCountdown(){int seconds=remainingSeconds();if(seconds!=displayedSeconds){displayedSeconds=seconds;countdown.accept(seconds);}}
    @Override public void close() { if(!closed) {closed=true;task.cancel();} }
}
