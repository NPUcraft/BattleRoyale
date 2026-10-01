package com.npucraft.battleroyale.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyEnvironmentSettingsTest {
    @Test void missingSectionKeepsBrightDryDefaults(){
        var settings=LobbyEnvironmentSettings.load(new YamlConfiguration());
        assertTrue(settings.enabled());
        assertEquals(6000,settings.fixedTime());
        assertTrue(settings.clearWeather());
    }
    @Test void explicitValuesAreRead(){
        var yaml=new YamlConfiguration();yaml.set("environment.enabled",false);yaml.set("environment.fixed-time",1000);yaml.set("environment.clear-weather",false);
        var settings=LobbyEnvironmentSettings.load(yaml);
        assertFalse(settings.enabled());assertEquals(1000,settings.fixedTime());assertFalse(settings.clearWeather());
    }
    @Test void invalidValuesAreRejected(){
        var flag=new YamlConfiguration();flag.set("environment.enabled","maybe");assertThrows(IllegalArgumentException.class,()->LobbyEnvironmentSettings.load(flag));
        var weather=new YamlConfiguration();weather.set("environment.clear-weather",1);assertThrows(IllegalArgumentException.class,()->LobbyEnvironmentSettings.load(weather));
        var text=new YamlConfiguration();text.set("environment.fixed-time","noon");assertThrows(IllegalArgumentException.class,()->LobbyEnvironmentSettings.load(text));
        var range=new YamlConfiguration();range.set("environment.fixed-time",24001);assertThrows(IllegalArgumentException.class,()->LobbyEnvironmentSettings.load(range));
    }
}
