package com.npucraft.battleroyale.loot;

/** Small strict PDC payload. A recovered unsampled chunk deliberately remains natural. */
public record LootRegionSample(LootRegionQuality quality,int sampledColumns,int artificialColumns) {
    public static final LootRegionSample RECOVERED_UNKNOWN=new LootRegionSample(LootRegionQuality.NATURAL,0,0);
    public LootRegionSample {
        if(quality==null||sampledColumns<0||sampledColumns>LootRegionQualityPolicy.COLUMNS||artificialColumns<0||artificialColumns>sampledColumns||(quality==LootRegionQuality.BUILT&&artificialColumns==0))
            throw new IllegalArgumentException("Invalid durable region quality sample");
    }
    public int[] encode(){return new int[]{1,quality.ordinal(),sampledColumns,artificialColumns};}
    public static LootRegionSample decode(int[] data){
        if(data==null||data.length!=4||data[0]!=1||data[1]<0||data[1]>=LootRegionQuality.values().length)throw new IllegalArgumentException("Invalid durable region quality payload");
        return new LootRegionSample(LootRegionQuality.values()[data[1]],data[2],data[3]);
    }
}
