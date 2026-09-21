package com.lastsector.config;
import java.time.Duration;
public record DisconnectSettings(Duration reconnectWindow,boolean mobAggro,double mobRadius,int mobInterval) {
    public static final DisconnectSettings DEFAULT=new DisconnectSettings(Duration.ofSeconds(120),true,24,20);
    public DisconnectSettings {
        if(reconnectWindow.isNegative() || reconnectWindow.compareTo(Duration.ofHours(1))>0 || !Double.isFinite(mobRadius) || mobRadius<0 || mobRadius>64 || mobInterval<1 || mobInterval>1200)throw new IllegalArgumentException("Invalid disconnect settings");
    }
}
