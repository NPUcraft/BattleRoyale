package com.lastsector.map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
/** Server-thread entry points; active completions return on the server thread. */
public interface WorldProvider extends AutoCloseable {
    /** Copies asynchronously then revalidates stillCurrent on the server thread before loading. */
    CompletionStage<GameWorld> prepare(UUID sessionId, String roomId, MapTemplate template, BooleanSupplier stillCurrent);
    /** Unloads on server thread before authorizing async deletion. Never deletes a loaded world. */
    CompletionStage<Void> release(GameWorld world);
    /** Includes queued copies/deletes and loaded resources retained after unload failures. */
    boolean busy();
    /** Invalidates preparations and unloads; outstanding IO finishes without further world loads. */
    @Override void close();
}

