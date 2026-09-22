package com.lastsector.service;
import com.lastsector.session.GameSession;
import java.util.UUID;
import java.util.function.Consumer;
/** All callbacks occur on the server thread. stop drains pending start work before releasing the world. */
public interface MatchLifecycle extends AutoCloseable {
    default void abortReason(GameSession session,boolean admin) {}
    default void onFinished(Consumer<GameSession> finished) {}
    default void checkJoin(UUID player) {}
    default void checkStart() {}
    default void restore(GameSession session) {}
    default java.util.concurrent.CompletionStage<Void> prepareDurably(com.lastsector.session.GameSession session){preparing(session);return java.util.concurrent.CompletableFuture.completedFuture(null);}
    default void preparing(GameSession session) {}
    void start(GameSession session, Runnable ready, Consumer<Throwable> failed);
    void running(GameSession session, Consumer<Throwable> failed);
    void stop(GameSession session, Runnable drained);
    void disconnected(UUID player);
    @Override void close();
}

