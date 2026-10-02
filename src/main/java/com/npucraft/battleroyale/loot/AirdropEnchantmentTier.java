package com.npucraft.battleroyale.loot;

import java.util.random.RandomGenerator;

/** Supply gear is stronger than ordinary loot; the rare best tier stays within vanilla limits. */
public enum AirdropEnchantmentTier {
    ENHANCED, RARE, BEST;

    public static AirdropEnchantmentTier roll(RandomGenerator random) {
        int roll=random.nextInt(100);
        return roll<85?ENHANCED:roll<95?RARE:BEST;
    }
    public int level(int maximum,RandomGenerator random) {
        if(maximum<1)throw new IllegalArgumentException("Enchantment maximum must be positive");
        return switch(this) {
            case ENHANCED -> random.nextInt(1,Math.min(2,maximum)+1);
            case RARE -> random.nextInt(Math.max(1,maximum-1),maximum+1);
            case BEST -> maximum;
        };
    }
}
