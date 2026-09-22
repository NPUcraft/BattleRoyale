package com.npucraft.lastsector;
import com.npucraft.lastsector.map.*;
import com.npucraft.lastsector.room.RoomDefinition;
import com.npucraft.lastsector.service.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/** Deterministic clocks, IO jobs and server boundaries for runtime tests. */
public final class TestSupport {
    private TestSupport() {}
    public static RoomDefinition room(String id, int min, int max) {
        return new RoomDefinition(id, id, min, max, 1, Duration.ZERO, List.of("city"), "default", "default", true, Duration.ofSeconds(3));
    }
    public static MapTemplate map(Path path) { return new MapTemplate("city", "City", path, new PlayableArea(-100, 100, -100, 100)); }
    public static void level(Path path, long seed) throws IOException {
        Files.createDirectories(path.getParent());
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(10); out.writeUTF("Data");
            out.writeByte(10); out.writeUTF("WorldGenSettings");
            out.writeByte(4); out.writeUTF("seed"); out.writeLong(seed);
            out.writeByte(10); out.writeUTF("dimensions");
            out.writeByte(10); out.writeUTF("minecraft:overworld");
            out.writeByte(8); out.writeUTF("type"); out.writeUTF("minecraft:overworld");
            out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(0);
        }
    }
    public static final class Scheduler implements GameScheduler {
        private final List<Job> jobs = new ArrayList<>();
        private int tick;
        @Override public Task repeat(int period, Runnable action) {
            Job job = new Job(period, action, tick + period); jobs.add(job); return () -> job.cancelled = true;
        }
        public void ticks(int count) {
            for (int i = 0; i < count; i++) {
                tick++;
                for (Job job : List.copyOf(jobs))
                    if (!job.cancelled && tick >= job.next) { job.next += job.period; job.action.run(); }
            }
        }
        public void seconds(int count) { ticks(count * 20); }
        public long active() { return jobs.stream().filter(job -> !job.cancelled).count(); }
        private static final class Job {
            final int period; final Runnable action; int next; boolean cancelled;
            Job(int period, Runnable action, int next) { this.period = period; this.action = action; this.next = next; }
        }
    }
    public static final class Players implements PlayerGateway {
        public final List<String> events = new ArrayList<>();
        public int staged, returned, errors;
        public boolean stageSuccess = true;
        @Override public boolean toLobby(Collection<UUID> ids) { returned += ids.size(); return true; }
        public MatchLifecycle matches() { return new MatchLifecycle() {
            public void start(com.npucraft.lastsector.session.GameSession session, Runnable ready, java.util.function.Consumer<Throwable> failed) {
                staged += session.players().size();
                if (stageSuccess) ready.run(); else failed.accept(new IllegalStateException("Teleport failed"));
            }
            public void running(com.npucraft.lastsector.session.GameSession session, java.util.function.Consumer<Throwable> failed) {}
            public void stop(com.npucraft.lastsector.session.GameSession session, Runnable drained) { drained.run(); }
            public void disconnected(UUID id) {}
            public void close() {}
        }; }
        @Override public void notify(Collection<UUID> ids, String event, Object... args) { events.add(event); }
        @Override public void error(String context, Throwable error) { errors++; }
    }
    public static final class Worlds implements WorldProvider {
        public final Map<UUID, CompletableFuture<GameWorld>> pending = new LinkedHashMap<>();
        public final Map<UUID, BooleanSupplier> guards = new HashMap<>();
        public final Map<UUID, GameWorld> descriptors = new HashMap<>();
        public final List<GameWorld> released = new ArrayList<>();
        public boolean releaseFails, closed, busy;
        @Override public CompletionStage<GameWorld> prepare(UUID id, String room, MapTemplate template, BooleanSupplier current) {
            var future = new CompletableFuture<GameWorld>();
            pending.put(id, future); guards.put(id, current);
            descriptors.put(id, new GameWorld(id, room, "ls_" + id, Path.of("runtime", "ls_" + id), template));
            return future;
        }
        public void succeed(UUID id) { pending.get(id).complete(descriptors.get(id)); }
        public void fail(UUID id) { pending.get(id).completeExceptionally(new IOException("copy/load failed")); }
        @Override public CompletionStage<Void> release(GameWorld world) {
            released.add(world);
            return releaseFails ? CompletableFuture.failedFuture(new IOException("cleanup failed")) : CompletableFuture.completedFuture(null);
        }
        @Override public boolean busy() { return busy; }
        @Override public void close() { closed = true; }
    }
    public static final class Worker extends AbstractExecutorService {
        private final Queue<Runnable> work = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void execute(Runnable job) { if (shutdown) throw new RejectedExecutionException(); work.add(job); }
        public void runAll() { while (!work.isEmpty()) work.remove().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; var copy = List.copyOf(work); work.clear(); return copy; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && work.isEmpty(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return isTerminated(); }
    }
}

