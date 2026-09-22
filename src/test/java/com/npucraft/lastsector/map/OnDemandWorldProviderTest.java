package com.npucraft.lastsector.map;
import com.npucraft.lastsector.TestSupport;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class OnDemandWorldProviderTest {
    @TempDir Path root;
    TestSupport.Scheduler clock;
    TestSupport.Worker worker;
    OnDemandWorldProvider provider;
    WorldFiles files;
    MapTemplate map;
    Gateway gateway;
    List<Throwable> errors;
    @BeforeEach void setup() throws Exception {
        Path data = Files.createDirectories(root.resolve("plugins/LastSector"));
        Path template = Files.createDirectories(data.resolve("maps/city"));
        TestSupport.level(template.resolve("level.dat"), 42);
        map = TestSupport.map(template);
        files = new WorldFiles(data, data.resolve("runtime"), root, List.of(template), List.of());
        clock = new TestSupport.Scheduler(); worker = new TestSupport.Worker(); gateway = new Gateway(); errors = new ArrayList<>();
        provider = new OnDemandWorldProvider(files, gateway, clock, worker, (message, error) -> errors.add(error));
    }
    @AfterEach void close() { provider.close(); worker.runAll(); }
    void settle() { worker.runAll(); clock.ticks(1); worker.runAll(); clock.ticks(1); }
    @Test void copyWaitsForServerCompletionAndReleaseUnloadsBeforeDeleting() {
        var result = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture();
        assertTrue(provider.busy()); worker.runAll(); assertEquals(0, gateway.loads); assertFalse(result.isDone());
        clock.ticks(1); var world = result.join(); assertEquals(1, gateway.loads); assertTrue(Files.exists(world.runtimePath()));
        var release = provider.release(world).toCompletableFuture();
        assertEquals(1, gateway.unloads); assertTrue(Files.exists(world.runtimePath()));
        settle(); release.join(); assertFalse(Files.exists(world.runtimePath())); assertFalse(provider.busy());
    }
    @Test void cancelledCopyNeverLoadsAndIsDeleted() {
        var valid = new AtomicBoolean(true); UUID id = UUID.randomUUID();
        var result = provider.prepare(id, "a", map, valid::get).toCompletableFuture();
        valid.set(false); settle();
        assertTrue(result.isCompletedExceptionally()); assertEquals(0, gateway.loads);
        assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath())); assertFalse(provider.busy());
    }
    @Test void disableWhileCopyingDiscardsWithoutMainThreadCallback() {
        UUID id = UUID.randomUUID();
        var result = provider.prepare(id, "a", map, () -> true).toCompletableFuture();
        provider.close(); worker.runAll();
        assertTrue(result.isCompletedExceptionally()); assertEquals(0, gateway.loads);
        assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath())); assertTrue(worker.isTerminated());
    }
    @Test void disableWithAlreadyQueuedCompletionStillDiscards() {
        UUID id = UUID.randomUUID();
        provider.prepare(id, "a", map, () -> true); worker.runAll(); provider.close(); worker.runAll();
        assertEquals(0, gateway.loads); assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath()));
    }
    @Test void loadFailureConfirmsUnloadThenDeletesAndCompletesFailure() {
        gateway.failLoad = true; UUID id = UUID.randomUUID();
        var result = provider.prepare(id, "a", map, () -> true).toCompletableFuture(); settle();
        assertTrue(result.isCompletedExceptionally()); assertEquals(1, gateway.unloads);
        assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath())); assertFalse(provider.busy());
    }
    @Test void unloadFailureNeverDeletesLoadedDirectory() {
        var result = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture(); settle();
        GameWorld world = result.join(); gateway.failUnload = true;
        assertTrue(provider.release(world).toCompletableFuture().isCompletedExceptionally()); settle();
        assertTrue(Files.exists(world.runtimePath().resolve(WorldFiles.MARKER)));
        assertTrue(provider.busy()); assertFalse(errors.isEmpty());
        gateway.failUnload = false;
    }
    @Test void copyFailureDoesNotLoadOrLeakPendingOperation() throws Exception {
        Files.writeString(map.templatePath().resolve("level.dat"), "broken");
        var result = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture(); settle();
        assertTrue(result.isCompletedExceptionally()); assertEquals(0, gateway.loads); assertFalse(provider.busy());
    }
    @Test void markerFailureRetainsDirectoryAfterUnload() throws Exception {
        var result = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture(); settle();
        var world = result.join(); Files.writeString(world.runtimePath().resolve(WorldFiles.MARKER), "sessionId=wrong");
        var release = provider.release(world).toCompletableFuture(); settle();
        assertTrue(release.isCompletedExceptionally()); assertTrue(Files.exists(world.runtimePath()));
        assertFalse(provider.busy()); assertFalse(errors.isEmpty());
    }
    @Test void twoWorldsRemainIndependent() {
        var first = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture();
        var second = provider.prepare(UUID.randomUUID(), "b", map, () -> true).toCompletableFuture(); settle();
        assertNotEquals(first.join().worldName(), second.join().worldName());
        provider.release(first.join()); settle();
        assertFalse(Files.exists(first.join().runtimePath())); assertTrue(Files.exists(second.join().runtimePath()));
    }
    @Test void reentrantDisableDuringWorldLoadUnloadsAndCleansTheJustLoadedResource() {
        UUID id = UUID.randomUUID();
        gateway.onLoad = provider::close;
        var result = provider.prepare(id, "a", map, () -> true).toCompletableFuture();
        worker.runAll(); clock.ticks(1); worker.runAll();
        assertTrue(result.isCompletedExceptionally()); assertEquals(1, gateway.unloads);
        assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath()));
        assertTrue(worker.isTerminated()); assertFalse(provider.busy());
    }
    @Test void reentrantCancelDuringLoadAlsoUnloadsBeforeDeleting() {
        var valid = new AtomicBoolean(true); UUID id = UUID.randomUUID();
        gateway.onLoad = () -> valid.set(false);
        var result = provider.prepare(id, "a", map, valid::get).toCompletableFuture(); settle();
        assertTrue(result.isCompletedExceptionally()); assertEquals(1, gateway.unloads);
        assertFalse(Files.exists(files.descriptor(id, "a", map).runtimePath()));
    }
    @Test void unknownResourceReleaseCannotUnloadOrDeleteAnything() {
        provider.release(files.descriptor(UUID.randomUUID(), "forged", map)).toCompletableFuture().join();
        assertEquals(0, gateway.unloads);
    }
    @Test void reentrantDisableDuringUnloadDoesNotDoubleUnloadOrRejectCleanup() {
        var prepared = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture(); settle();
        GameWorld world = prepared.join(); gateway.onUnload = provider::close;
        var released = provider.release(world).toCompletableFuture(); worker.runAll();
        released.join(); assertEquals(1, gateway.unloads);
        assertFalse(Files.exists(world.runtimePath())); assertTrue(worker.isTerminated());
    }
    @Test void disableCleansAllLoadedWorldsBeforeShuttingDownWorker() {
        var first = provider.prepare(UUID.randomUUID(), "a", map, () -> true).toCompletableFuture();
        var second = provider.prepare(UUID.randomUUID(), "b", map, () -> true).toCompletableFuture(); settle();
        provider.close(); worker.runAll();
        assertFalse(Files.exists(first.join().runtimePath())); assertFalse(Files.exists(second.join().runtimePath()));
        assertEquals(2, gateway.unloads); assertTrue(errors.isEmpty()); assertFalse(provider.busy());
    }
    @Test void shutdownAwaitReportsPendingFilesUntilDrained() throws Exception {
        provider.prepare(UUID.randomUUID(),"a",map,()->true);
        provider.close(); assertFalse(provider.awaitFileShutdown(1,TimeUnit.MILLISECONDS));
        worker.runAll(); assertTrue(provider.awaitFileShutdown(1,TimeUnit.MILLISECONDS)); assertFalse(provider.busy());
    }
    static final class Gateway implements WorldGateway {
        int loads, unloads; boolean failLoad, failUnload;
        Runnable onLoad = () -> {};
        Runnable onUnload = () -> {};
        @Override public void load(GameWorld world) { loads++; onLoad.run(); if (failLoad) throw new IllegalStateException("load failed"); }
        @Override public void unload(GameWorld world) { unloads++; onUnload.run(); if (failUnload) throw new IllegalStateException("unload failed"); }
    }
}

