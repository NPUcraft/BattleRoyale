package com.lastsector.combat;
import java.util.UUID;
public record DamageRecord(UUID victim,UUID attacker,double amount,DamageOrigin origin,long nanoTime,boolean direct) {
    public DamageRecord {
        if(victim==null || origin==null || !Double.isFinite(amount) || amount<=0) throw new IllegalArgumentException("Invalid effective damage record");
    }
}
