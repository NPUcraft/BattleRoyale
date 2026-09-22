package com.npucraft.lastsector.service;
import com.npucraft.lastsector.map.GameWorld;
import java.util.Collection;
import java.util.UUID;
/** Bukkit player boundary; all methods run on the owning server thread. */
public interface PlayerGateway {
    /** False means at least one online player could not be moved. Offline UUIDs are skipped. */
    boolean toLobby(Collection<UUID> players);
    void notify(Collection<UUID> players, String event, Object... arguments);
    void error(String context, Throwable error);
}

