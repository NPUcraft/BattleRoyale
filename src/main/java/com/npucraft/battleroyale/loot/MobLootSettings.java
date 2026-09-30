package com.npucraft.battleroyale.loot;

import java.util.Map;

/** Small supplementary drops, bounded per player and per match even in a natural-mob farm. */
public record MobLootSettings(boolean enabled,String table,double chance,int minRolls,int maxRolls,int maxDropsPerSession,int perPlayerCooldownSeconds) {
    public static final MobLootSettings DEFAULT=new MobLootSettings(true,"basic",.35,1,1,64,10);
    public MobLootSettings {
        if(table==null||!table.matches("[a-z0-9_.-]+")||!Double.isFinite(chance)||chance<0||chance>1
                ||minRolls<1||maxRolls<minRolls||maxRolls>3||maxDropsPerSession<1||maxDropsPerSession>256
                ||perPlayerCooldownSeconds<1||perPlayerCooldownSeconds>300)
            throw new IllegalArgumentException("Invalid mob loot settings (chance 0..1, rolls 1..3, session cap 1..256, cooldown 1..300s)");
    }
    public LootTable resolvedTable(Map<String,LootTable> tables){
        var source=tables.get(table);if(source==null)throw new IllegalArgumentException("Unknown mob loot table: "+table);
        // Mob drops clamp each roll to eight items, regardless of ground/container stack sizes.
        return new LootTable(source.id(),minRolls,maxRolls,source.entries().stream()
                .map(entry->new LootTable.Entry(entry.item(),entry.weight(),Math.min(8,entry.minAmount()),Math.min(8,entry.maxAmount()))).toList());
    }
}
