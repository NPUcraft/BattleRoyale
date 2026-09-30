package com.npucraft.battleroyale.spawn;
import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.zone.GameClock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
/** One request/inspection per tick; cancellation drains the request before resource release. */
public final class SpawnPreparation {
    private final SpawnTerrain terrain;
    private final SpawnPlanner planner;
    private final List<UUID> starters;
    private final BooleanSupplier current;
    private final GameClock clock;
    private final Runnable ready;
    private final Consumer<Throwable> failed;
    private final long begun;
    private final GameScheduler.Task task;
    private CompletableFuture<?> pending;
    private SpawnPlanner.Column column;
    private Runnable drained;
    private boolean cancelled,finished,failureReported,landing;
    public SpawnPreparation(SpawnTerrain terrain,SpawnPlanner planner,List<UUID> starters,BooleanSupplier current,
            GameScheduler scheduler,GameClock clock,Runnable ready,Consumer<Throwable> failed) {
        this.terrain=terrain; this.planner=planner; this.starters=List.copyOf(starters); this.current=current;
        this.clock=clock; this.ready=ready; this.failed=failed; begun=clock.nanoTime();
        task=scheduler.repeat(1,this::tick);
    }
    private void tick() {
        if(finished) return;
        try {
            if(cancelled) { if(pending==null || pending.isDone()) finish(); return; }
            if(clock.nanoTime()-begun>120_000_000_000L) throw new IllegalStateException("Spawn preparation exceeded 120 seconds");
            if(!current.getAsBoolean()) throw new IllegalStateException("Stale spawn preparation");
            if(pending!=null) {
                if(!pending.isDone()) return;
                pending.join(); pending=null;
                if(landing) { land(); return; }
                Double feet=terrain.safeFeet(column);
                planner.resolve(feet);
                terrain.resolved(column,feet!=null && Double.isFinite(feet));
                if(planner.done()) {
                    landing=true; pending=terrain.beforeLanding();
                    if(pending.isDone()) { pending.join(); pending=null; land(); }
                }
                return;
            }
            column=planner.candidate();
            if(!planner.spaced(column)) { planner.resolve(null); return; }
            pending=terrain.prepare(column);
        } catch(Throwable error) {
            if(!failureReported) { failureReported=true; failed.accept(error); }
        }
    }
    private void land() {
        terrain.teleport(starters,planner.plan(),()-> !cancelled && current.getAsBoolean());
        if(cancelled || !current.getAsBoolean()) return;
        finish(); ready.run();
    }
    private void finish() {
        if(finished) return;
        finished=true; task.cancel(); terrain.release();
        if(drained!=null) drained.run();
    }
    public void stop(Runnable drained) {
        if(finished) { drained.run(); return; }
        this.drained=drained; cancelled=true;
        if(pending==null || pending.isDone()) finish();
    }
    /** No plugin continuation may run after disable. Paper owns pending chunk generation during unload. */
    public void close() {
        cancelled=true; drained=null;
        if(pending!=null) pending.cancel(false);
        finish();
    }
}

