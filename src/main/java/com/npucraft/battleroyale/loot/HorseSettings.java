package com.npucraft.battleroyale.loot;

/** Total creation cap, not a replenishable count of currently living horses. */
public record HorseSettings(boolean enabled,int maxPerSession,int intervalSeconds,int maxAttempts,double minDistance) {
    public static final int MAX_PER_SESSION=64;
    public static final HorseSettings DEFAULT=new HorseSettings(true,16,15,24,64);
    public HorseSettings {
        if(maxPerSession<1||maxPerSession>MAX_PER_SESSION||intervalSeconds<5||intervalSeconds>600||maxAttempts<1||maxAttempts>128
                ||!Double.isFinite(minDistance)||minDistance<8||minDistance>256)
            throw new IllegalArgumentException("Invalid horse settings (cap 1..64, interval 5..600s, attempts 1..128, distance 8..256)");
    }
}
