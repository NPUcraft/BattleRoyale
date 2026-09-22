package com.npucraft.lastsector.storage;
import java.util.*;
public interface RecoveryRepository {
    record Row(UUID session,String room,String map,String world,String state,long revision,int version,String payload,String checksum,long updatedAt,String status) {}
    record Restore(UUID player,UUID session,UUID generation,int version,String payload,String checksum,String status,long createdAt) {}
    int migrate() throws Exception;
    List<Row> sessions() throws Exception;
    boolean save(Row row,UUID owner,long leaseUntil) throws Exception;
    boolean claim(UUID session,UUID owner,long now,long until) throws Exception;
    void retire(UUID session,UUID owner,String status) throws Exception;
    void originals(List<Restore> restores) throws Exception;
    List<Restore> restores() throws Exception;
    void pending(UUID session) throws Exception;
    void pendingPlayer(UUID player,UUID generation) throws Exception;
    boolean applied(UUID player,UUID generation) throws Exception;
    void deleteRestore(UUID player,UUID generation) throws Exception;
}
