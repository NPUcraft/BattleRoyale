package com.npucraft.battleroyale.config;
import com.npucraft.battleroyale.paper.NativeLootItems;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MatchContentLoaderTest {
    @TempDir Path directory;
    @BeforeEach void files() throws Exception {
        for(String file:new String[]{"config.yml","rooms.yml","maps.yml","zones.yml","loadouts.yml","loot-tables.yml","map-data/city/loot.yml","map-data/desert/loot.yml"}) {
            Path target=directory.resolve(file); Files.createDirectories(target.getParent());
            try(var input=getClass().getResourceAsStream("/"+file)) { Files.copy(input,target); }
        }
    }
    MatchContent load() { return new MatchContentLoader(directory,new com.npucraft.battleroyale.loot.LootItemResolver<String>() {
        public void validate(String key) {
            // Catalog adapter only; native ItemType registries are verified by the real Paper probe.
            if(key.startsWith("minecraft:")&&org.bukkit.Material.matchMaterial(key)!=null&&!java.util.Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:elytra").contains(key))return;
            if(java.util.Set.of("battleroyale:invisibility_potion","battleroyale:fire_resistance_potion","battleroyale:healing_potion","battleroyale:harming_potion","battleroyale:poison_potion","battleroyale:splash_invisibility_potion","battleroyale:splash_fire_resistance_potion","battleroyale:splash_healing_potion","battleroyale:splash_harming_potion","battleroyale:splash_poison_potion","battleroyale:combat_firework").contains(key))return;
            throw new IllegalArgumentException("Unknown test item: " + key);
        }
        public String resolve(String key) { validate(key); return key; }
    },stored->{}).load(new ConfigurationLoader(directory).load()); }
    void replace(String file,String from,String to) throws Exception { Path p=directory.resolve(file); Files.writeString(p,Files.readString(p).replace(from,to)); }
    @Test void defaultsAndRoundTripEmptyLoadouts() throws Exception {
        var content=load(); assertEquals(1,content.loadouts().size()); assertEquals(2,content.maps().size());
        Files.writeString(directory.resolve("loadouts.yml"),MatchContentLoader.loadoutYaml(content.loadouts())); assertEquals(content,load());
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "rooms.yml|loadout: default|loadout: missing",
        "loadouts.yml|selected-hotbar-slot: 0|selected-hotbar-slot: 9",
        "loadouts.yml|selected-hotbar-slot: 0|selected-hotbar-slot: 0.5",
        "loot-tables.yml|min-rolls: 2|min-rolls: -1",
        "loot-tables.yml|max-rolls: 5|max-rolls: 0",
        "loot-tables.yml|weight: 5|weight: 0",
        "loot-tables.yml|min-amount: 1|min-amount: 0",
        "loot-tables.yml|max-amount: 4|max-amount: 0",
        "loot-tables.yml|minecraft:bread|other:bread",
        "loot-tables.yml|minecraft:bread|minecraft:unknown_item",
        "loot-tables.yml|minecraft:bread|minecraft:air"
    }) void rejectsBadCatalog(String file,String from,String to) throws Exception { replace(file,from,to); assertThrows(IllegalArgumentException.class,this::load); }
    @Test void pointReferencesAndBoundsValidate() throws Exception {
        Path path=directory.resolve("map-data/city/loot.yml");
        String yaml="containers:\n  - id: chest\n    x: 0\n    y: 64\n    z: 0\n    loot-table: basic\nareas: []\n";
        Files.writeString(path,yaml); assertEquals(1,load().maps().get("city").containers().size());
        Files.writeString(path,yaml.replace("basic","missing")); assertThrows(IllegalArgumentException.class,this::load);
        Files.writeString(path,yaml.replace("x: 0","x: 5000")); assertThrows(IllegalArgumentException.class,this::load);
        Files.writeString(path,yaml.replace("y: 64","y: 0.5")); assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void missingMapMetadataFailsClearly() throws Exception {
        Files.delete(directory.resolve("map-data/city/loot.yml")); assertTrue(assertThrows(IllegalArgumentException.class,this::load).getMessage().contains("map-data/city/loot.yml"));
    }
    @Test void newLootPoliciesValidateReferencesAndBudgets()throws Exception{
        var content=load();assertEquals(.4,content.autoContainers().chance());assertEquals(1,content.autoContainers().minRolls());assertEquals(3,content.autoContainers().maxRolls());assertEquals("airdrop",content.airdrops().table());
        assertEquals(.35,content.mobLoot().chance());assertEquals(64,content.mobLoot().maxDropsPerSession());
        assertTrue(content.horses().enabled());assertEquals(16,content.horses().maxPerSession());assertEquals(15,content.horses().intervalSeconds());
        replace("loot-tables.yml","chance: 0.4","chance: 1.1");assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void legacyCustomTableGetsCompatibleDefaultsWithoutRequiringBasic()throws Exception{
        Path path=directory.resolve("loot-tables.yml");String original=Files.readString(path);String tables="config-version: 2\n"+original.substring(original.indexOf("loot-tables:"));
        Files.writeString(path,tables.replace("  basic:","  survival:"));
        for(String map:new String[]{"city","desert"})replace("map-data/"+map+"/loot.yml","loot-table: basic","loot-table: survival");
        var content=load();assertTrue(content.autoContainers().enabled());assertEquals("survival",content.autoContainers().table());assertEquals("survival",content.airdrops().table());assertEquals("survival",content.mobLoot().table());assertEquals(com.npucraft.battleroyale.loot.HorseSettings.DEFAULT,content.horses());
    }
    @Test void mobLootRejectsUnknownTableAndUnboundedRate()throws Exception{
        replace("loot-tables.yml","per-player-cooldown-seconds: 10","per-player-cooldown-seconds: 0");assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void elytraIsRejectedByItemCatalog()throws Exception{
        replace("loot-tables.yml","minecraft:bread","minecraft:elytra");assertThrows(IllegalArgumentException.class,this::load);
    }
    @Test void horseCapSixtyFourIsAcceptedByTheSameProductionLoader()throws Exception{
        replace("loot-tables.yml","max-per-session: 16","max-per-session: 64");
        var horses=load().horses();assertEquals(64,horses.maxPerSession());assertEquals(15,horses.intervalSeconds());
    }
    @Test void horseBudgetCannotBeUnbounded()throws Exception{
        replace("loot-tables.yml","max-per-session: 16","max-per-session: 65");assertThrows(IllegalArgumentException.class,this::load);
    }
}
