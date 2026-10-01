package com.npucraft.battleroyale.loot;

/** Automatic storage-container generation; work and item rolls remain bounded. */
public record AutoContainerLootSettings(boolean enabled,String table,double chance,int minRolls,int maxRolls,int maxContainersPerTick) {
    public static final AutoContainerLootSettings DEFAULT=new AutoContainerLootSettings(true,"basic",.4,1,3,16);
    public AutoContainerLootSettings {
        if(table==null||!table.matches("[a-z0-9_.-]+"))throw new IllegalArgumentException("Invalid automatic container loot table");
        if(!Double.isFinite(chance)||chance<0||chance>1)throw new IllegalArgumentException("Automatic container chance must be in [0,1]");
        if(minRolls<1||maxRolls<minRolls||maxRolls>128)throw new IllegalArgumentException("Automatic container rolls must be 1..128");
        if(maxContainersPerTick<1||maxContainersPerTick>256)throw new IllegalArgumentException("Automatic container tick budget must be 1..256");
    }
    public LootTable resolvedTable(java.util.Map<String,LootTable> tables){
        var source=tables.get(table);if(source==null)throw new IllegalArgumentException("Unknown automatic container loot table: "+table);
        return new LootTable(source.id(),minRolls,maxRolls,source.entries());
    }
    public boolean activates(java.util.random.RandomGenerator random){return chance>=1||chance>0&&random.nextDouble()<chance;}
}
