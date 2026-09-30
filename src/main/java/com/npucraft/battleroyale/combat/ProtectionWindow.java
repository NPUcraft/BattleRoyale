package com.npucraft.battleroyale.combat;
import java.time.Duration;
public record ProtectionWindow(long started, Duration duration) {
    public boolean active(long now) { return remaining(now)>0; }
    public double remaining(long now) { return Math.max(0,(duration.toNanos()-Math.max(0,now-started))/1e9); }
}

