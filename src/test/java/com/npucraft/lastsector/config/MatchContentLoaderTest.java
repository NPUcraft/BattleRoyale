package com.npucraft.lastsector.config;
import com.npucraft.lastsector.paper.NativeLootItems;
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
    MatchContent load() { return new MatchContentLoader(directory,new com.npucraft.lastsector.loot.LootItemResolver<String>() {
        public void validate(String key) { if(!java.util.Set.of("minecraft:bread","minecraft:arrow").contains(key)) throw new IllegalArgumentException("Unknown test item: " + key); }
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
        "loot-tables.yml|min-rolls: 1|min-rolls: -1",
        "loot-tables.yml|max-rolls: 3|max-rolls: 0",
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
}
