package com.npucraft.battleroyale.loot;

import java.util.List;
import java.util.Map;

/**
 * Staged ground supplies with authored-terrain tiers: the opening table is chosen per point
 * (natural ground uses {@code initialTable}, built ground uses {@code builtInitialTable} when
 * configured), and every refill may likewise pick a better table inside built terrain.
 * {@code tableTiers} only feeds the rarity particle colours; it never changes roll contents.
 */
public record GroundLootSettings(boolean enabled,String initialTable,String builtInitialTable,List<Refill> refills,Map<String,String> tableTiers) {
    /** afterStage is the one-based number of the shrinking stage that has just completed; the refill fires when the next stage starts. */
    public record Refill(int afterStage,String table,int points,String builtTable) {
        public Refill(int afterStage,String table,int points){this(afterStage,table,points,null);}
        public Refill {
            if(afterStage<1)throw new IllegalArgumentException("Ground refill stage must be >= 1");
            if(table==null||!table.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid ground refill loot table");
            if(points<1||points>64)throw new IllegalArgumentException("Ground refill points must be in 1..64");
            if(builtTable!=null&&!builtTable.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid ground refill built loot table");
        }
        /** The table to use when the landing ground classifies as built terrain, or the natural one when unset. */
        public String tableFor(LootRegionQuality quality){return quality==LootRegionQuality.BUILT&&builtTable!=null?builtTable:table;}
    }
    public static final GroundLootSettings DISABLED=new GroundLootSettings(false,"",null,List.of(),Map.of());
    public GroundLootSettings {
        refills=List.copyOf(refills);tableTiers=Map.copyOf(tableTiers);
        if(enabled&&(initialTable==null||!initialTable.matches("[a-z0-9_.-]+")))throw new IllegalArgumentException("Invalid ground initial loot table");
        if(builtInitialTable!=null&&!builtInitialTable.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid ground built initial loot table");
    }
    /** Legacy three-argument form before terrain tiers existed; every ground then shares the opening table. */
    public GroundLootSettings(boolean enabled,String initialTable,List<Refill> refills){this(enabled,initialTable,null,refills,Map.of());}
    public void validate(Map<String,LootTable> tables){
        if(!enabled)return;
        if(!tables.containsKey(initialTable))throw new IllegalArgumentException("Ground loot requires an existing initial-table");
        if(builtInitialTable!=null&&!tables.containsKey(builtInitialTable))throw new IllegalArgumentException("Ground loot requires an existing built-initial-table");
        for(var refill:refills){
            if(!tables.containsKey(refill.table()))throw new IllegalArgumentException("Ground loot requires existing refill tables");
            if(refill.builtTable()!=null&&!tables.containsKey(refill.builtTable()))throw new IllegalArgumentException("Ground loot requires an existing refill built-table");
        }
        for(var tier:tableTiers.entrySet()){
            if(!tier.getValue().matches("low|mid|high"))throw new IllegalArgumentException("Ground signal tier must be low, mid or high: "+tier.getValue());
            if(!tables.containsKey(tier.getKey()))throw new IllegalArgumentException("Ground signal tier references unknown table: "+tier.getKey());
        }
    }
    /** Rarity label for the supply particle colour, or null when the table has no authored tier. */
    public String tierOf(String tableId){return tableTiers.get(tableId);}
}
