package com.npucraft.lastsector.recovery;
import java.time.Duration;
public record RecoverySettings(boolean enabled,Duration checkpoint,Duration orphanAge) {
    public static final RecoverySettings DEFAULT=new RecoverySettings(true,Duration.ofSeconds(5),Duration.ofMinutes(60));
    public RecoverySettings{if(checkpoint.compareTo(Duration.ofSeconds(1))<0 || checkpoint.compareTo(Duration.ofMinutes(5))>0 || orphanAge.compareTo(Duration.ofMinutes(1))<0)throw new IllegalArgumentException("Invalid recovery intervals");}
}
