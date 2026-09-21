package com.lastsector.config;
import java.time.Duration;
public record CombatSettings(Duration attributionWindow,double assistMinDamage,double assistMinShare,
        Duration showcaseDuration,double boxReach) {
    public static final CombatSettings DEFAULT=new CombatSettings(Duration.ofSeconds(15),4,.2,Duration.ofSeconds(60),6);
    public CombatSettings {
        if(attributionWindow.isNegative() || attributionWindow.isZero() || attributionWindow.compareTo(Duration.ofMinutes(10))>0
                || !Double.isFinite(assistMinDamage) || assistMinDamage<0 || !Double.isFinite(assistMinShare) || assistMinShare<0 || assistMinShare>1
                || showcaseDuration.isNegative() || showcaseDuration.compareTo(Duration.ofHours(1))>0
                || !Double.isFinite(boxReach) || boxReach<=0 || boxReach>16) throw new IllegalArgumentException("Invalid combat/showcase/deathbox settings");
    }
}
