package com.npucraft.battleroyale.loot;

/** Failed terrain candidates consume the same budget as successful ones. No second refill on recovery.
 *  Water-heavy regions burn attempts fast: the ceiling must absorb ~10 retries per planned point
 *  (200 points × worst-case terrain) or the opening loots starve far below the configured count. */
public final class GroundLootBudget {
    public static final int BASE_MAX_ATTEMPTS=2400;
    /** Twelve attempts per planned point: the configured 10 terrain retries plus headroom. */
    private static final int ATTEMPTS_PER_POINT=12;
    public static final long MAX_NANOS=90_000_000_000L;
    public static final int MAX_IN_FLIGHT=8, MAX_REQUESTS_PER_TICK=8, MAX_INSPECTIONS_PER_TICK=8;
    public static final long MAX_TICK_NANOS=2_000_000L;
    private int maxAttempts=BASE_MAX_ATTEMPTS;
    /** Scales the attempt ceiling with the planned point count so player-count scaling cannot starve the opening loot. */
    public void plan(int plannedPoints){maxAttempts=Math.max(BASE_MAX_ATTEMPTS,plannedPoints*ATTEMPTS_PER_POINT);}
    /** Wall-time and operation ceilings both apply. One expensive native operation cannot be preempted. */
    public static final class Tick {
        private final long started;private int requests,inspections;
        public Tick(long now){started=now;}
        public boolean request(long now){if(requests>=MAX_REQUESTS_PER_TICK||now-started>=MAX_TICK_NANOS)return false;requests++;return true;}
        public boolean inspect(long now){if(inspections>=MAX_INSPECTIONS_PER_TICK||now-started>=MAX_TICK_NANOS)return false;inspections++;return true;}
    }
    private int attempts;private long started;private boolean active;
    public boolean request(long now){
        if(!active){started=now;active=true;}
        if(attempts>=maxAttempts||now-started>=MAX_NANOS)return false;
        attempts++;return true;
    }
    public int attempts(){return attempts;}
}
