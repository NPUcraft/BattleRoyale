package com.npucraft.battleroyale.loot;

/** Bounded, optional supply drops; missing legacy configuration uses the basic loot table. */
public record AirdropSettings(boolean enabled,String table,int minRolls,int maxRolls,int fallSeconds,int maxAttempts,int markerSeconds) {
    public static final int ANNOUNCEMENT_SECONDS=60;
    public int announcementSeconds(){return ANNOUNCEMENT_SECONDS;}
    public static final AirdropSettings DEFAULT=new AirdropSettings(true,"basic",8,12,8,24,120);
    public AirdropSettings {
        if(table==null||table.isBlank()||minRolls<1||maxRolls<minRolls||maxRolls>27||fallSeconds<1||fallSeconds>30||maxAttempts<1||maxAttempts>128||markerSeconds<0||markerSeconds>600)
            throw new IllegalArgumentException("Invalid airdrop settings (rolls 1..27, fall 1..30s, attempts 1..128, marker 0..600s)");
    }
}
