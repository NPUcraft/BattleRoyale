package com.npucraft.battleroyale.loot;

/** One short level-I effect, scoped to live participants and a true 24-block sphere. */
public final class BeaconBuffPolicy {
    public static final int RADIUS=24,DURATION_TICKS=100,AMPLIFIER=0;
    private BeaconBuffPolicy(){}
    public static boolean allows(boolean active,boolean primary,boolean sameWorld,boolean aliveParticipant,
            boolean online,boolean dead,boolean spectator,double distanceSquared,int existingAmplifier,int existingTicks){
        if(!active||!primary||!sameWorld||!aliveParticipant||!online||dead||spectator
                ||!Double.isFinite(distanceSquared)||distanceSquared<0||distanceSquared>RADIUS*RADIUS)return false;
        if(existingAmplifier>AMPLIFIER)return false;
        return existingAmplifier<0||(existingAmplifier==AMPLIFIER&&existingTicks>=0&&existingTicks<DURATION_TICKS);
    }
}
