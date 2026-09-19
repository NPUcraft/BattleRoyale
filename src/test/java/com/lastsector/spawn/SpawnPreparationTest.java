package com.lastsector.spawn;
import com.lastsector.TestSupport;
import com.lastsector.room.SpawnSettings;
import com.lastsector.zone.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SpawnPreparationTest {
    static class Terrain implements SpawnTerrain {
        CompletableFuture<Void> pending;
        int requests,teleports,releases,inspections;
        boolean unsafe,teleportFailure;
        public CompletableFuture<?> prepare(SpawnPlanner.Column column) { requests++; return pending=new CompletableFuture<>(); }
        public Double safeFeet(SpawnPlanner.Column column) { inspections++; return unsafe?null:64.0; }
        public void resolved(SpawnPlanner.Column column,boolean accepted) {}
        public void teleport(List<UUID> ids,List<SpawnPlanner.Position> plan,BooleanSupplier current) {
            assertTrue(current.getAsBoolean()); teleports+=ids.size(); if(teleportFailure) throw new IllegalStateException("teleport");
        }
        public void release() { releases++; }
    }
    TestSupport.Scheduler scheduler=new TestSupport.Scheduler();
    Terrain terrain=new Terrain();
    int ready,failed,drained;
    SpawnPreparation preparation;
    void start(int count,int attempts) {
        var ids=new ArrayList<UUID>(); for(int i=0;i<count;i++) ids.add(UUID.randomUUID());
        preparation=new SpawnPreparation(terrain,new SpawnPlanner(new Zone(0,0,500),new SpawnSettings(64,attempts),count,new Random(4)),
                ids,()->true,scheduler,()->0,()->ready++,error->{failed++; preparation.stop(()->drained++);});
    }
    @Test void allChunksCompleteBeforeSingleTeleportBatch() {
        start(2,10); scheduler.ticks(1); assertEquals(1,terrain.requests);
        scheduler.ticks(100); assertEquals(1,terrain.requests); assertEquals(0,terrain.inspections);
        terrain.pending.complete(null); scheduler.ticks(1); assertEquals(0,terrain.teleports);
        scheduler.ticks(1); terrain.pending.complete(null); scheduler.ticks(1);
        assertEquals(2,terrain.teleports); assertEquals(1,ready); assertEquals(1,terrain.releases); assertEquals(0,scheduler.active());
    }
    @Test void cancellationDrainsBeforeWorldReleaseAndNeverTeleports() {
        start(1,10); scheduler.ticks(1); preparation.stop(()->drained++);
        scheduler.ticks(2); assertEquals(0,drained); assertEquals(0,terrain.releases);
        terrain.pending.complete(null); scheduler.ticks(1);
        assertEquals(1,drained); assertEquals(0,ready); assertEquals(0,terrain.teleports); assertEquals(0,scheduler.active());
    }
    @Test void failedChunkAbortsOnlyOnce() {
        start(1,10); scheduler.ticks(1); terrain.pending.completeExceptionally(new IllegalStateException("chunk"));
        scheduler.ticks(10); assertEquals(1,failed); assertEquals(1,drained); assertEquals(0,terrain.teleports); assertEquals(0,scheduler.active());
    }
    @Test void impossibleTerrainFailsBoundedlyWithoutAnyTeleport() {
        terrain.unsafe=true; start(1,2);
        for(int i=0;i<2;i++) { scheduler.ticks(1); terrain.pending.complete(null); scheduler.ticks(1); }
        scheduler.ticks(1); assertEquals(1,failed); assertEquals(0,terrain.teleports); assertEquals(2,terrain.inspections);
    }
    @Test void teleportFailureCannotReachRunning() {
        terrain.teleportFailure=true; start(1,2); scheduler.ticks(1); terrain.pending.complete(null); scheduler.ticks(1);
        assertEquals(0,ready); assertEquals(1,failed); assertEquals(1,terrain.releases);
    }
    @Test void disableCancelsTasksAndLateCompletionHasNoSideEffects() {
        start(1,2); scheduler.ticks(1); preparation.close(); terrain.pending.complete(null); scheduler.ticks(100);
        assertEquals(0,ready); assertEquals(0,terrain.inspections); assertEquals(0,scheduler.active()); assertEquals(1,terrain.releases);
    }
    @Test void safeSurfaceRejectsLiquidsHazardsLeavesAndObstructedHead() {
        var solid=new SafeSpawnPolicy.Cell(true,false,false,false,false);
        var air=new SafeSpawnPolicy.Cell(false,true,false,false,false);
        assertTrue(SafeSpawnPolicy.safe(solid,air,air));
        var hazards=List.of(new SafeSpawnPolicy.Cell(true,false,false,true,false),
                new SafeSpawnPolicy.Cell(true,false,false,false,true),
                new SafeSpawnPolicy.Cell(false,true,true,false,false),
                new SafeSpawnPolicy.Cell(false,true,false,true,false));
        for(var hazard:hazards) {
            assertFalse(SafeSpawnPolicy.safe(hazard,air,air));
            assertFalse(SafeSpawnPolicy.safe(solid,hazard,air));
            assertFalse(SafeSpawnPolicy.safe(solid,air,hazard));
        }
        assertFalse(SafeSpawnPolicy.safe(solid,air,solid));
    }
    @Test void timeoutFailsOnceThenDrainsOutstandingChunk() {
        long[] clock={0};
        preparation=new SpawnPreparation(terrain,new SpawnPlanner(new Zone(0,0,500),SpawnSettings.DEFAULT,1,new Random(1)),
                List.of(UUID.randomUUID()),()->true,scheduler,()->clock[0],()->ready++,error->{failed++; preparation.stop(()->drained++);});
        scheduler.ticks(1); clock[0]=121_000_000_000L; scheduler.ticks(2);
        assertEquals(1,failed); assertEquals(0,drained);
        terrain.pending.complete(null); scheduler.ticks(1); assertEquals(1,drained); assertEquals(0,ready);
    }
}

