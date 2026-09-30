package com.npucraft.battleroyale.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbySettingsTest {
    @Test void missingConstructionSettingsNeverAuthorizeAWorldEdit(){
        var settings=LobbySettings.load(new YamlConfiguration());
        assertFalse(settings.buildEnabled());assertFalse(settings.clearExisting());
        assertTrue(settings.returnOnJoin());assertTrue(settings.protection());assertFalse(settings.adminBuild());
    }
    @Test void malformedAuthorizationSwitchesAreRejected(){
        for(String key:new String[]{"structure.enabled","structure.clear-existing-blocks","protection.enabled","protection.allow-admin-build","login.return-to-lobby"}){
            var yaml=new YamlConfiguration();yaml.set(key,"yes please");assertThrows(IllegalArgumentException.class,()->LobbySettings.load(yaml),key);
        }
    }
    @Test void explicitConfigurationPreservesDedicatedWorldAndConstructionControls()throws Exception{
        var yaml=new YamlConfiguration();yaml.loadFromString("""
            structure:
              enabled: true
              world: battleroyale_lobby
              center: {x: 0, y: 200, z: 0}
              radius: 32
              blocks-per-tick: 1200
              clear-existing-blocks: true
            protection:
              enabled: true
              allow-admin-build: false
            login:
              return-to-lobby: true
            """);
        var settings=LobbySettings.load(yaml);assertTrue(settings.buildEnabled());assertEquals("battleroyale_lobby",settings.world());
        assertEquals(200,settings.y());assertTrue(settings.clearExisting());assertFalse(settings.adminBuild());
    }
    @Test void refusesFractionalCoordinatesOutOfBoundsAndPathLikeWorldNames(){
        for(Object value:new Object[]{200.5,301,-49}){var yaml=new YamlConfiguration();yaml.set("structure.center.y",value);assertThrows(IllegalArgumentException.class,()->LobbySettings.load(yaml));}
        var yaml=new YamlConfiguration();yaml.set("structure.world","../world");assertThrows(IllegalArgumentException.class,()->LobbySettings.load(yaml));
        var radius=new YamlConfiguration();radius.set("structure.radius",64);assertThrows(IllegalArgumentException.class,()->LobbySettings.load(radius));
    }
}
