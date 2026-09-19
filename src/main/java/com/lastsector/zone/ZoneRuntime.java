package com.lastsector.zone;
import java.util.random.RandomGenerator;
/** Session-owned deterministic timeline. Initial zone never changes. */
public final class ZoneRuntime {
    private final Zone initial;
    private final ZoneProfile profile;
    private final RandomGenerator random;
    private final long started;
    private long phaseStart;
    private int stageIndex;
    private ZonePhase phase = ZonePhase.WAITING;
    private Zone from, current, next;
    private double remainingSeconds, progress;
    public ZoneRuntime(Zone initial, ZoneProfile profile, RandomGenerator random, long started) {
        this.initial=initial; this.current=initial; this.from=initial; this.profile=profile;
        this.random=random; this.started=started; this.phaseStart=started;
        next=ZoneGeometry.next(initial, stage().targetHalfSize(), random);
        update(started);
    }
    public void update(long now) {
        if (now-started < 0) throw new IllegalArgumentException("Clock moved backwards");
        while (phase != ZonePhase.FINAL) {
            long duration = (phase == ZonePhase.WAITING ? stage().waitDuration() : stage().shrinkDuration()).toNanos();
            long elapsed = now-phaseStart;
            if (elapsed < duration) {
                remainingSeconds=(duration-elapsed)/1e9;
                progress=duration == 0 ? 0 : (double)(duration-elapsed)/duration;
                current=phase==ZonePhase.WAITING ? from : ZoneGeometry.interpolate(from,next,(double)elapsed/duration);
                return;
            }
            phaseStart += duration;
            if (phase == ZonePhase.WAITING) phase=ZonePhase.SHRINKING;
            else {
                current=next; from=next;
                if (stageIndex+1 == profile.stages().size()) { phase=ZonePhase.FINAL; next=null; }
                else { stageIndex++; next=ZoneGeometry.next(current,stage().targetHalfSize(),random); phase=ZonePhase.WAITING; }
            }
        }
        remainingSeconds=0; progress=1;
    }
    public Zone initial() { return initial; }
    public Zone current() { return current; }
    public Zone next() { return next; }
    public int stageIndex() { return stageIndex; }
    public ZonePhase phase() { return phase; }
    public ZoneProfile.Stage stage() { return profile.stages().get(stageIndex); }
    public double remainingSeconds() { return remainingSeconds; }
    public double progress() { return Math.clamp(progress,0,1); }
}

