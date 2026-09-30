package com.npucraft.battleroyale.map;

import com.npucraft.battleroyale.service.GameScheduler;
import com.npucraft.battleroyale.zone.Zone;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/**
 * Async file IO -> completion queue -> server-thread revalidation/load.
 * Shutdown atomically closes the queue; late copies clean themselves without any Bukkit calls.
 */
public final class OnDemandWorldProvider implements WorldProvider {
    private final WorldFiles files;
    private final WorldGateway gateway;
    private final ExecutorService worker;
    private final BiConsumer<String, Throwable> errors;
    private final GameScheduler.Task pump;
    private final Object gate = new Object();
    private final Queue<Runnable> completions = new ArrayDeque<>();
    private final Map<UUID, GameWorld> loaded = new LinkedHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> releasing = new HashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private boolean closed;
    private boolean closing;
    private int loading;
    private int unloading;

    public OnDemandWorldProvider(WorldFiles files, WorldGateway gateway, GameScheduler scheduler,
                                 ExecutorService worker, BiConsumer<String, Throwable> errors) {
        this.files = files; this.gateway = gateway; this.worker = worker; this.errors = errors;
        pump = scheduler.repeat(1, this::drain);
    }
    @Override public CompletionStage<GameWorld> prepare(UUID id, String room, MapTemplate template, BooleanSupplier current) {
        return prepare(id, room, template, Optional.empty(), current);
    }
    @Override public CompletionStage<GameWorld> prepare(UUID id, String room, MapTemplate template,
            Optional<Zone> initialZone, BooleanSupplier current) {
        Objects.requireNonNull(initialZone, "initialZone");
        var result = new CompletableFuture<GameWorld>();
        synchronized (gate) { if (closed) return CompletableFuture.failedFuture(new IllegalStateException("World provider closed")); }
        try { gateway.validateTemplate(template); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        pending.incrementAndGet();
        worker.execute(() -> {
            GameWorld copy;
            try { copy = initialZone.isPresent() ? files.copy(id, room, template, initialZone.get()) : files.copy(id, room, template); }
            catch (Exception error) {
                post(() -> { pending.decrementAndGet(); result.completeExceptionally(error); },
                        () -> { pending.decrementAndGet(); result.completeExceptionally(error); });
                return;
            }
            post(() -> {
                if (isClosed() || !current.getAsBoolean()) { discard(copy, result); return; }
                loading++;
                try {
                    files.validateLoad(copy);
                    gateway.load(copy);
                    loaded.put(id, copy);
                    // WorldLoadEvent may synchronously cancel/disable us while createWorld is on the stack.
                    if (isClosed() || !current.getAsBoolean()) {
                        gateway.unload(copy); loaded.remove(id); discard(copy, result); return;
                    }
                    pending.decrementAndGet();
                    result.complete(copy);
                } catch (Exception error) {
                    // createWorld can fail after registering a world. Always confirm unload before deletion.
                    try { gateway.unload(copy); }
                    catch (Exception unload) {
                        loaded.put(id, copy); error.addSuppressed(unload);
                        errors.accept("World load/unload failed; directory retained: " + copy.runtimePath(), error);
                        pending.decrementAndGet(); result.completeExceptionally(error); return;
                    }
                    loaded.remove(id);
                    deleteAsync(copy).whenComplete((ignored, deletion) -> {
                        if (deletion != null) error.addSuppressed(deletion);
                        pending.decrementAndGet(); result.completeExceptionally(error);
                    });
                } finally {
                    loading--;
                    stopWorkerWhenIdle();
                }
            }, () -> discardOnWorker(copy, result));
        });
        return result;
    }
    /** Caller already validated marker/tree off-thread. Recovery failure never deletes the candidate. */
    public void recover(GameWorld world)throws Exception {
        if(isClosed())throw new IllegalStateException("Provider closed");files.validateLoad(world);
        try{gateway.load(world);loaded.put(world.sessionId(),world);}catch(Exception failure){try{gateway.unload(world);}catch(Exception unload){failure.addSuppressed(unload);}throw failure;}
    }
    public void preserve(GameWorld world)throws Exception {gateway.unload(world);loaded.remove(world.sessionId());}
    private boolean isClosed() { synchronized (gate) { return closed; } }
    private void discard(GameWorld copy, CompletableFuture<GameWorld> result) {
        deleteAsync(copy).whenComplete((ignored, error) -> {
            pending.decrementAndGet();
            result.completeExceptionally(error != null ? error : new CancellationException("Preparation cancelled"));
        });
    }
    private void discardOnWorker(GameWorld copy, CompletableFuture<GameWorld> result) {
        try { files.delete(copy, true); result.completeExceptionally(new CancellationException("Shutdown cancelled preparation")); }
        catch (Exception error) { errors.accept("Late clone cleanup failed: " + copy.runtimePath(), error); result.completeExceptionally(error); }
        finally { pending.decrementAndGet(); }
    }
    @Override public CompletionStage<Void> release(GameWorld world) {
        GameWorld owned = loaded.get(world.sessionId());
        if (owned == null) return CompletableFuture.completedFuture(null);
        if (!owned.equals(world)) return CompletableFuture.failedFuture(new IllegalStateException("Runtime ownership mismatch"));
        if (releasing.containsKey(world.sessionId())) return releasing.get(world.sessionId());
        var result = new CompletableFuture<Void>();
        releasing.put(world.sessionId(), result);
        unloading++;
        try {
            gateway.unload(world);
            loaded.remove(world.sessionId());
            deleteAsync(world).whenComplete((ignored, error) -> {
                // Active completions are server-thread; shutdown completions must not touch this map.
                if (!isClosed()) releasing.remove(world.sessionId());
                if (error == null) result.complete(null); else result.completeExceptionally(error);
            });
        } catch (Exception error) {
            releasing.remove(world.sessionId());
            errors.accept("Unload refused; runtime directory retained: " + world.runtimePath(), error);
            result.completeExceptionally(error);
        } finally {
            unloading--;
            stopWorkerWhenIdle();
        }
        return result;
    }
    private CompletionStage<Void> deleteAsync(GameWorld world) {
        var result = new CompletableFuture<Void>();
        pending.incrementAndGet();
        worker.execute(() -> {
            Throwable failure = null;
            try { files.delete(world, true); }
            catch (Exception error) { failure = error; errors.accept("Runtime delete refused/failed: " + world.runtimePath(), error); }
            Throwable error = failure;
            Runnable finish = () -> {
                pending.decrementAndGet();
                if (error == null) result.complete(null); else result.completeExceptionally(error);
            };
            post(finish, finish);
        });
        return result;
    }
    private void post(Runnable live, Runnable stopped) {
        synchronized (gate) {
            if (!closed) { completions.add(live); return; }
        }
        stopped.run(); // Only file cleanup or future completion, never server APIs.
    }
    private void drain() {
        while (true) {
            Runnable completion;
            synchronized (gate) { completion = completions.poll(); }
            if (completion == null) return;
            completion.run();
        }
    }
    @Override public boolean busy() { return pending.get() > 0 || !loaded.isEmpty(); }
    /** Only used after normal plugin shutdown, when no queued operation needs the server thread. */
    public boolean awaitFileShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.isShutdown() && worker.awaitTermination(timeout, unit);
    }
    private void stopWorkerWhenIdle() {
        if (isClosed() && !closing && loading == 0 && unloading == 0) worker.shutdown();
    }
    @Override public void close() {
        synchronized (gate) { if (closed) return; closed = true; }
        closing = true;
        try {
            pump.cancel();
            drain(); // Queued copies see closed and are discarded before executor shutdown.
            for (GameWorld world : List.copyOf(loaded.values())) {
                try { release(world); } catch (Exception error) { errors.accept("Shutdown cleanup failed", error); }
            }
        } finally {
            closing = false;
            stopWorkerWhenIdle();
        }
    }
}

