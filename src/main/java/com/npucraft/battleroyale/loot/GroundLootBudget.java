package com.npucraft.battleroyale.loot;

/** Failed terrain candidates consume the same budget as successful ones. No second refill on recovery. */
public final class GroundLootBudget {
    public static final int MAX_ATTEMPTS=800;
    public static final long MAX_NANOS=45_000_000_000L;
    private int attempts;private long started;private boolean active;
    public boolean request(long now){
        if(!active){started=now;active=true;}
        if(attempts>=MAX_ATTEMPTS||now-started>=MAX_NANOS)return false;
        attempts++;return true;
    }
    public int attempts(){return attempts;}
}
