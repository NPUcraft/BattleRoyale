package com.npucraft.battleroyale.loot;

import java.util.Set;

/** Explicit surface heuristic. Ores, raw logs, leaves and resource storage blocks never score. */
public final class LootRegionQualityPolicy {
    public static final int COLUMNS=16, DEPTH=3, MAX_BLOCK_READS=COLUMNS*DEPTH;
    private static final Set<String> BUILT=Set.of("CRAFTING_TABLE","FURNACE","BLAST_FURNACE","SMOKER","STONECUTTER",
            "CARTOGRAPHY_TABLE","FLETCHING_TABLE","SMITHING_TABLE","LOOM","GRINDSTONE","ENCHANTING_TABLE",
            "ANVIL","CHIPPED_ANVIL","DAMAGED_ANVIL","BREWING_STAND","CAULDRON","WATER_CAULDRON","LAVA_CAULDRON",
            "CHEST","TRAPPED_CHEST","BARREL","HOPPER","DISPENSER","DROPPER","LECTERN","LANTERN","SOUL_LANTERN",
            "BRICKS","STONE_BRICKS","MOSSY_STONE_BRICKS","CRACKED_STONE_BRICKS","CHISELED_STONE_BRICKS",
            "DEEPSLATE_BRICKS","DEEPSLATE_TILES","CRACKED_DEEPSLATE_BRICKS","CRACKED_DEEPSLATE_TILES",
            "POLISHED_DEEPSLATE","POLISHED_ANDESITE","POLISHED_DIORITE","POLISHED_GRANITE","POLISHED_BLACKSTONE",
            "POLISHED_BLACKSTONE_BRICKS","CHISELED_POLISHED_BLACKSTONE","QUARTZ_BLOCK","SMOOTH_QUARTZ","QUARTZ_PILLAR",
            "QUARTZ_BRICKS","CHISELED_QUARTZ_BLOCK","GLASS","GLASS_PANE","IRON_BARS","FARMLAND","DIRT_PATH",
            "REDSTONE_LAMP","RAIL","POWERED_RAIL","DETECTOR_RAIL","ACTIVATOR_RAIL","LADDER","SCAFFOLDING");
    private LootRegionQualityPolicy() {}
    public static boolean built(String material){
        if(material==null||material.endsWith("_ORE")||material.equals("ANCIENT_DEBRIS")||ValuableBlockPolicy.replaces(material))return false;
        return BUILT.contains(material)||material.endsWith("_PLANKS")||material.endsWith("_STAIRS")||material.endsWith("_SLAB")
                ||material.endsWith("_WALL")||material.endsWith("_FENCE")||material.endsWith("_FENCE_GATE")
                ||material.endsWith("_DOOR")||material.endsWith("_TRAPDOOR")||material.endsWith("_CONCRETE")
                ||material.endsWith("_GLAZED_TERRACOTTA")||material.endsWith("_STAINED_GLASS")||material.endsWith("_STAINED_GLASS_PANE")
                ||material.endsWith("_BED")||material.endsWith("_CARPET");
    }
    public static LootRegionQuality classify(int artificialColumns,int sampledColumns,double threshold){
        if(sampledColumns<0||sampledColumns>COLUMNS||artificialColumns<0||artificialColumns>sampledColumns
                ||!Double.isFinite(threshold)||threshold<=0||threshold>1)throw new IllegalArgumentException("Invalid region surface sample");
        return sampledColumns>0&&artificialColumns/(double)sampledColumns>=threshold?LootRegionQuality.BUILT:LootRegionQuality.NATURAL;
    }
    public static int x(int sample){check(sample);return 2+(sample%4)*4;}
    public static int z(int sample){check(sample);return 2+(sample/4)*4;}
    private static void check(int sample){if(sample<0||sample>=COLUMNS)throw new IllegalArgumentException("Region sample index");}
}
