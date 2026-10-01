package com.npucraft.battleroyale.loot;

/** Failed terrain candidates consume the same budget as successful ones. No second refill on recovery. */
public final class GroundLootBudget {
    public static final int MAX_ATTEMPTS=800;
    public static final long MAX_NANOS=45_000_000_000L;
    public static final int MAX_IN_FLIGHT=8, MAX_REQUESTS_PER_TICK=8, MAX_INSPECTIONS_PER_TICK=8;
    public static final long MAX_TICK_NANOS=2_000_000L;
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
        if(attempts>=MAX_ATTEMPTS||now-started>=MAX_NANOS)return false;
        attempts++;return true;
    }
    public int attempts(){return attempts;}
}
