package com.npucraft.battleroyale.config;

import org.bukkit.configuration.ConfigurationSection;

/** Lobby-only environment. A missing section keeps the lobby bright and dry by default. */
public record LobbyEnvironmentSettings(boolean enabled,int fixedTime,boolean clearWeather) {
    /** 6000 is noon: the brightest daylight tick, so the lobby never stands in dusk or dawn gloom. */
    public static final LobbyEnvironmentSettings DEFAULT=new LobbyEnvironmentSettings(true,6000,true);
    public LobbyEnvironmentSettings {
        if(fixedTime<0||fixedTime>24000)throw new IllegalArgumentException("environment.fixed-time 必须为0至24000");
    }
    public static LobbyEnvironmentSettings load(ConfigurationSection yaml){
        if(yaml.contains("environment.enabled")&&!yaml.isBoolean("environment.enabled"))throw new IllegalArgumentException("environment.enabled 必须为 true 或 false");
        if(yaml.contains("environment.clear-weather")&&!yaml.isBoolean("environment.clear-weather"))throw new IllegalArgumentException("environment.clear-weather 必须为 true 或 false");
        if(yaml.contains("environment.fixed-time")&&!yaml.isInt("environment.fixed-time"))throw new IllegalArgumentException("environment.fixed-time 必须为整数");
        return new LobbyEnvironmentSettings(yaml.getBoolean("environment.enabled",true),yaml.getInt("environment.fixed-time",6000),yaml.getBoolean("environment.clear-weather",true));
    }
}
