package com.npucraft.battleroyale.loot;

import java.util.Set;

/** Explicit storage-block list. Ores and ancient debris are deliberately outside this policy. */
public final class ValuableBlockPolicy {
    private ValuableBlockPolicy() {}
    public static final Set<String> STORAGE=Set.of("IRON_BLOCK","GOLD_BLOCK","DIAMOND_BLOCK","EMERALD_BLOCK","NETHERITE_BLOCK",
            "RAW_IRON_BLOCK","RAW_GOLD_BLOCK","RAW_COPPER_BLOCK","COAL_BLOCK","LAPIS_BLOCK","REDSTONE_BLOCK",
            "COPPER_BLOCK","EXPOSED_COPPER","WEATHERED_COPPER","OXIDIZED_COPPER",
            "WAXED_COPPER_BLOCK","WAXED_EXPOSED_COPPER","WAXED_WEATHERED_COPPER","WAXED_OXIDIZED_COPPER");
    public static boolean replaces(String type){return STORAGE.contains(type);}
    public static String replacement(int y){return y<0?"DEEPSLATE":"STONE";}
}
