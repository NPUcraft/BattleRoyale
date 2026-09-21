package com.lastsector.combat;

import com.lastsector.config.CombatSettings;
import com.lastsector.zone.GameClock;
import java.util.*;
import java.util.function.Predicate;

/** One session's bounded history. No per-tick scanning; inclusive monotonic expiry. */
public final class CombatTracker {
    private static final int MAX_RECORDS_PER_VICTIM=4096;
    private final Set<UUID> participants;
    private final CombatSettings settings;
    private final GameClock clock;
    private final Map<UUID,ArrayDeque<DamageRecord>> history=new HashMap<>();
    public CombatTracker(Set<UUID> participants,CombatSettings settings,GameClock clock) {
        this.participants=Set.copyOf(participants); this.settings=settings; this.clock=clock;
    }
    public void record(UUID victim,UUID attacker,double amount,DamageOrigin origin,boolean direct) {
        if(!valid(victim,attacker) || !Double.isFinite(amount) || amount<=0) return;
        var records=history.computeIfAbsent(victim,ignored->new ArrayDeque<>()); expire(records);
        if(records.size()==MAX_RECORDS_PER_VICTIM) records.removeFirst();
        records.addLast(new DamageRecord(victim,attacker,amount,origin,clock.nanoTime(),direct));
    }
    public DeathReason resolve(UUID victim,UUID directAttacker,DamageOrigin cause,Predicate<UUID> stillParticipant) {
        var records=history.getOrDefault(victim,new ArrayDeque<>()); expire(records);
        Map<UUID,Double> damage=new LinkedHashMap<>(); UUID last=null;
        for(var record:records) if(valid(victim,record.attacker()) && stillParticipant.test(record.attacker())) {
            damage.merge(record.attacker(),record.amount(),Double::sum); last=record.attacker();
        }
        boolean direct=valid(victim,directAttacker) && stillParticipant.test(directAttacker);
        UUID killer=direct?directAttacker:last;
        double total=damage.values().stream().mapToDouble(Double::doubleValue).sum(); Set<UUID> assists=new HashSet<>();
        damage.forEach((id,amount)-> {
            if(!id.equals(killer) && (amount>=settings.assistMinDamage() || total>0 && amount/total>=settings.assistMinShare())) assists.add(id);
        });
        return new DeathReason(cause,Optional.ofNullable(killer),assists,direct);
    }
    private boolean valid(UUID victim,UUID attacker) {
        return attacker!=null && !attacker.equals(victim) && participants.contains(attacker) && participants.contains(victim);
    }
    private void expire(ArrayDeque<DamageRecord> records) {
        long now=clock.nanoTime(),window=settings.attributionWindow().toNanos();
        while(!records.isEmpty() && now-records.getFirst().nanoTime()>window) records.removeFirst();
    }
    public void forget(UUID victim) { history.remove(victim); }
    public void clear() { history.clear(); }
    public int size(UUID victim) { return history.getOrDefault(victim,new ArrayDeque<>()).size(); }
    public static double effective(double finalDamage,double health,double absorbed) {
        if(!Double.isFinite(finalDamage) || !Double.isFinite(health) || !Double.isFinite(absorbed)) return 0;
        return Math.max(0,Math.min(finalDamage,Math.max(0,health)))+Math.max(0,absorbed);
    }
}
