package com.npucraft.battleroyale.loot;

/** Bounded, optional supply drops; missing legacy configuration uses the basic loot table. */
public record AirdropSettings(boolean enabled,String table,int minRolls,int maxRolls,int fallSeconds,int maxAttempts,int markerSeconds,int announcementSeconds,int minDistance) {
    public static final int ANNOUNCEMENT_SECONDS=60;
    public static final AirdropSettings DEFAULT=new AirdropSettings(true,"basic",8,12,8,24,120,ANNOUNCEMENT_SECONDS,0);
    /** Legacy seven-argument form; the public countdown and landing spacing keep their defaults. */
    public AirdropSettings(boolean enabled,String table,int minRolls,int maxRolls,int fallSeconds,int maxAttempts,int markerSeconds){
        this(enabled,table,minRolls,maxRolls,fallSeconds,maxAttempts,markerSeconds,ANNOUNCEMENT_SECONDS,0);
    }
    public AirdropSettings {
        if(table==null||table.isBlank()||minRolls<1||maxRolls<minRolls||maxRolls>27||fallSeconds<1||fallSeconds>30||maxAttempts<1||maxAttempts>128||markerSeconds<0||markerSeconds>600
                ||announcementSeconds<5||announcementSeconds>600||minDistance<0||minDistance>100_000)
            throw new IllegalArgumentException("Invalid airdrop settings (rolls 1..27, fall 1..30s, attempts 1..128, marker 0..600s, announcement 5..600s, distance 0..100000)");
    }
}
