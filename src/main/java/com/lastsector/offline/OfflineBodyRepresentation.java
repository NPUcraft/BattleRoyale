package com.lastsector.offline;
import java.util.*;
/** Public-API adapter boundary. Core combat/outcome never know the carrier entity type. */
public interface OfflineBodyRepresentation {
    UUID spawn(OfflineBody body);
    BodySnapshot capture(OfflineBody body);
    boolean valid(OfflineBody body);
    void health(OfflineBody body,double value);
    void remove(OfflineBody body);
    Collection<UUID> entityIds(OfflineBody body);
}
