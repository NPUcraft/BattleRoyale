package com.lastsector.death;
import com.lastsector.combat.DeathReason;
import com.lastsector.loadout.StoredItem;
import java.util.*;
/** Immutable creation payload. The Paper shared Inventory is authoritative after materialization. */
public record DeathBox(UUID id,UUID sessionId,UUID deceased,String deceasedName,DeathPosition location,
        long elapsedNanos,long eliminationTick,DeathReason reason,List<StoredItem> contents,int storedXp) {
    public DeathBox { contents=List.copyOf(contents); if(contents.size()>53 || storedXp<0) throw new IllegalArgumentException("Invalid deathbox payload"); }
}
