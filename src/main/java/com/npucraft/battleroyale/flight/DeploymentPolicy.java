package com.npucraft.battleroyale.flight;

/** Pure aircraft deployment decisions, independent of Bukkit and of the client on-ground flag. */
public final class DeploymentPolicy {
    private DeploymentPolicy(){}
    public enum Outcome { DESCEND,LAND,FALLBACK }
    /** Only a passenger still standing on the fuselage is carried along with a platform move. */
    public static boolean carried(boolean online,boolean onPlatform){return online&&onPlatform;}
    /**
     * Decision for one descending player. The initial-square containment deliberately plays no part:
     * a glider may touch down wherever it steered, and only hazard, void or the bounded timeout
     * pull it back to the prepared fallback. {@code landed} already folds in water contact.
     */
    public static Outcome descending(double y,double minHeight,double ceiling,boolean inLava,boolean landed,long sinceDepartureNanos,long timeoutNanos){
        if(inLava||y<minHeight+4)return Outcome.FALLBACK;
        if(y<ceiling-2&&landed)return Outcome.LAND;
        if(sinceDepartureNanos>timeoutNanos)return Outcome.FALLBACK;
        return Outcome.DESCEND;
    }
}
