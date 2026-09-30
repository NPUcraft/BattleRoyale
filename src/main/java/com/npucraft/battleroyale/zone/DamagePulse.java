package com.npucraft.battleroyale.zone;
/** Real-time pulse gate; a delayed server tick never replays missed damage. */
public final class DamagePulse {
    private long next;
    public DamagePulse(long started) { next=started+1_000_000_000L; }
    public boolean due(long now) {
        if(now-next<0) return false;
        next=now+1_000_000_000L;
        return true;
    }
}

