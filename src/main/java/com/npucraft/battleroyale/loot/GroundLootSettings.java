package com.npucraft.battleroyale.loot;

import java.util.List;
import java.util.Map;

/** Staged ground supplies: one low-tier opening table plus higher-tier refills after shrinking stages. */
public record GroundLootSettings(boolean enabled,String initialTable,List<Refill> refills) {
    /** afterStage is the one-based number of the shrinking stage that has just completed; the refill fires when the next stage starts. */
    public record Refill(int afterStage,String table,int points) {
        public Refill {
            if(afterStage<1)throw new IllegalArgumentException("Ground refill stage must be >= 1");
            if(table==null||!table.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid ground refill loot table");
            if(points<1||points>64)throw new IllegalArgumentException("Ground refill points must be in 1..64");
        }
    }
    public static final GroundLootSettings DISABLED=new GroundLootSettings(false,"",List.of());
    public GroundLootSettings {
        refills=List.copyOf(refills);
        if(enabled&&(initialTable==null||!initialTable.matches("[a-z0-9_.-]+")))throw new IllegalArgumentException("Invalid ground initial loot table");
    }
    public void validate(Map<String,LootTable> tables){
        if(!enabled)return;
        if(!tables.containsKey(initialTable))throw new IllegalArgumentException("Ground loot requires an existing initial-table");
        for(var refill:refills)if(!tables.containsKey(refill.table()))throw new IllegalArgumentException("Ground loot requires existing refill tables");
    }
}
