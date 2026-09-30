package com.npucraft.battleroyale.spawn;
import com.npucraft.battleroyale.room.SpawnSettings;
import com.npucraft.battleroyale.zone.Zone;
import java.util.*;
import java.util.random.RandomGenerator;
/** Incremental, bounded planner: adapter resolves one candidate at a time, no partial plan is published. */
public final class SpawnPlanner {
    public record Column(int x, int z) { public double centerX() { return x+.5; } public double centerZ() { return z+.5; } }
    public record Position(double x, double y, double z) {}
    private final Zone zone;
    private final SpawnSettings settings;
    private final RandomGenerator random;
    private final int count;
    private int attempts;
    private final List<Position> accepted = new ArrayList<>();
    private Column pending;
    public SpawnPlanner(Zone zone, SpawnSettings settings, int count, RandomGenerator random) {
        if (count < 1) throw new IllegalArgumentException("No starters");
        this.zone=zone; this.settings=settings; this.count=count; this.random=random;
    }
    public Column candidate() {
        if (done() || pending != null) throw new IllegalStateException("Candidate is already pending or plan complete");
        if (++attempts > settings.maxAttemptsPerPlayer()) throw new IllegalStateException("Safe spawn attempts exhausted for player " + accepted.size());
        int minX=(int)Math.ceil(zone.minX()-.5), maxX=(int)Math.floor(zone.maxX()-.5);
        int minZ=(int)Math.ceil(zone.minZ()-.5), maxZ=(int)Math.floor(zone.maxZ()-.5);
        pending=new Column(random.nextInt(minX,maxX+1),random.nextInt(minZ,maxZ+1));
        return pending;
    }
    public boolean spaced(Column column) {
        return accepted.stream().allMatch(p -> Math.hypot(p.x()-column.centerX(),p.z()-column.centerZ()) >= settings.minDistance()
                && (p.x()!=column.centerX() || p.z()!=column.centerZ()));
    }
    public void resolve(Double safeFeetY) {
        if (pending == null) throw new IllegalStateException("No pending candidate");
        if (safeFeetY != null && Double.isFinite(safeFeetY) && spaced(pending)) {
            accepted.add(new Position(pending.centerX(),safeFeetY,pending.centerZ())); attempts=0;
        }
        pending=null;
    }
    public boolean done() { return accepted.size()==count; }
    public List<Position> plan() {
        if (!done()) throw new IllegalStateException("Incomplete spawn plan");
        return List.copyOf(accepted);
    }
}

