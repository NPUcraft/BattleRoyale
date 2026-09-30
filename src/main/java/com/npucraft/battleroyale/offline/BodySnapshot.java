package com.npucraft.battleroyale.offline;
import com.npucraft.battleroyale.loadout.StoredItem;
import java.util.*;
/** Carried match state only. Never contains the pre-match lobby inventory or ender chest. */
public record BodySnapshot(BodyPosition position,double health,double maxHealth,double absorption,int food,float saturation,
        int fireTicks,int air,float fallDistance,List<Effect> effects,Map<Integer,StoredItem> inventory,StoredItem cursor,
        int selected,int level,float progress,int totalXp) {
    public BodySnapshot {effects=List.copyOf(effects);inventory=Map.copyOf(inventory);if(health<0 || maxHealth<=0 || !Double.isFinite(health) || !Double.isFinite(maxHealth) || totalXp<0)throw new IllegalArgumentException("Invalid body state");}
    public record Effect(String key,int duration,int amplifier,boolean ambient,boolean particles,boolean icon) {}
    public BodySnapshot at(BodyPosition at){return new BodySnapshot(at,health,maxHealth,absorption,food,saturation,fireTicks,air,fallDistance,effects,inventory,cursor,selected,level,progress,totalXp);}
    public List<StoredItem> carriedItems(){var result=new ArrayList<>(inventory.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());if(cursor!=null)result.add(cursor);return List.copyOf(result);}
}
