package com.npucraft.battleroyale.loot;

import java.util.Map;

/** Optional table selection; ordinary rolls and enchantment limits remain unchanged. */
public record LootRegionQualitySettings(boolean enabled,double builtThreshold,String naturalTable,String builtTable) {
    public static final LootRegionQualitySettings DEFAULT=new LootRegionQualitySettings(true,.25,"region-natural","region-built");
    public static final LootRegionQualitySettings DISABLED=new LootRegionQualitySettings(false,.25,"region-natural","region-built");
    public LootRegionQualitySettings {
        if(!Double.isFinite(builtThreshold)||builtThreshold<=0||builtThreshold>1)throw new IllegalArgumentException("Region built threshold must be in (0,1]");
        if(naturalTable==null||!naturalTable.matches("[a-z0-9_.-]+")||builtTable==null||!builtTable.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid region quality loot table");
    }
    public void validate(Map<String,LootTable> tables){
        if(enabled&&(!tables.containsKey(naturalTable)||!tables.containsKey(builtTable)))throw new IllegalArgumentException("Region quality requires both natural-table and built-table references");
    }
    public String table(LootRegionQuality quality,String fallback){return enabled?(quality==LootRegionQuality.BUILT?builtTable:naturalTable):fallback;}
    /** Use regional entries while preserving the caller's ground/container quantity limits. */
    public LootTable resolve(LootRegionQuality quality,LootTable fallback,Map<String,LootTable> tables){
        if(!enabled)return fallback;
        String id=table(quality,fallback.id());LootTable source=tables.get(id);
        if(source==null)throw new IllegalArgumentException("Unknown region quality loot table: "+id);
        return new LootTable(id,fallback.minRolls(),fallback.maxRolls(),source.entries());
    }
}
