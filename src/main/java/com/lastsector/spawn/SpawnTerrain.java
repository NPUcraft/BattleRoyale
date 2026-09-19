package com.lastsector.spawn;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
/** Server-thread boundary. prepare may use a supported async chunk API; all other calls stay synchronous. */
public interface SpawnTerrain {
    CompletableFuture<?> prepare(SpawnPlanner.Column column);
    Double safeFeet(SpawnPlanner.Column column);
    void resolved(SpawnPlanner.Column column,boolean accepted);
    default CompletableFuture<?> beforeLanding() { return CompletableFuture.completedFuture(null); }
    void teleport(List<UUID> starters,List<SpawnPlanner.Position> plan,BooleanSupplier current);
    void release();
}

