package com.npucraft.battleroyale.loot;

/** Conservative origin allowlist: player-created, converted and multiplied mobs never earn extras. */
public final class MobLootPolicy {
    private MobLootPolicy(){}
    public static boolean natural(String reason){return "NATURAL".equals(reason)||"CHUNK_GEN".equals(reason);}
    public static boolean canAttempt(MobLootSettings settings,int rewarded,long now,long lastAttempt){
        if(!settings.enabled()||rewarded<0||rewarded>=settings.maxDropsPerSession())return false;
        if(lastAttempt==Long.MIN_VALUE)return true;
        return now>=lastAttempt&&now-lastAttempt>=settings.perPlayerCooldownSeconds()*1_000L;
    }
}
