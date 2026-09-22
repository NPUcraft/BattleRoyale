package com.npucraft.lastsector.death;
import com.npucraft.lastsector.combat.DamageOrigin;
import com.npucraft.lastsector.loadout.StoredItem;
import java.util.*;
public record EliminationRequest(UUID victim,String name,DeathPosition location,DamageOrigin cause,UUID directAttacker,
        List<StoredItem> contents,int totalExperience,long nanoTime,long tick) {
    public EliminationRequest { contents=List.copyOf(contents); if(totalExperience<0) throw new IllegalArgumentException("Negative XP"); }
}
