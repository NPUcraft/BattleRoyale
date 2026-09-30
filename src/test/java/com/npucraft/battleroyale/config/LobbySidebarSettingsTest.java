package com.npucraft.battleroyale.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbySidebarSettingsTest {
    @Test void oldLobbyFilesEnableSidebarWithoutEnablingConstruction(){
        var yaml=new YamlConfiguration();assertEquals(LobbySidebarSettings.DEFAULT,LobbySidebarSettings.load(yaml));assertFalse(LobbySettings.load(yaml).buildEnabled());
    }
    @Test void explicitDisableAndCustomTitleArePreserved(){
        var yaml=new YamlConfiguration();yaml.set("sidebar.enabled",false);yaml.set("sidebar.title","战区 · 房间");yaml.set("sidebar.page-seconds",15);
        assertEquals(new LobbySidebarSettings(false,"战区 · 房间",15),LobbySidebarSettings.load(yaml));
    }
    @Test void invalidIntervalsAndMultiLineTitlesAreRejected(){
        for(Object value:new Object[]{1,61,2.5,"8"}){var yaml=new YamlConfiguration();yaml.set("sidebar.page-seconds",value);assertThrows(IllegalArgumentException.class,()->LobbySidebarSettings.load(yaml));}
        for(String value:new String[]{"","标题\n第二行","字".repeat(33)}){var yaml=new YamlConfiguration();yaml.set("sidebar.title",value);assertThrows(IllegalArgumentException.class,()->LobbySidebarSettings.load(yaml));}
        var yaml=new YamlConfiguration();yaml.set("sidebar.enabled","yes");assertThrows(IllegalArgumentException.class,()->LobbySidebarSettings.load(yaml));
    }
}
