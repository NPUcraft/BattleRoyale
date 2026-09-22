package com.npucraft.lastsector.config;
import com.npucraft.lastsector.progression.*;
import com.npucraft.lastsector.cosmetic.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
public record ProgressionConfig(RankingSettings ranking,Map<String,CosmeticDefinition> cosmetics,
                                Map<String,MenuItem> menu,Map<String,String> titles,List<String> economyPriority,
                                String currency,boolean shopEnabled) {
    public record MenuItem(int slot,Material material,String name,List<String> lore) {public MenuItem {lore=List.copyOf(lore);}}
    public ProgressionConfig {cosmetics=Map.copyOf(cosmetics);menu=Map.copyOf(menu);titles=Map.copyOf(titles);economyPriority=List.copyOf(economyPriority);}
    public static ProgressionConfig load(JavaPlugin plugin) {return load(plugin,true);}
    public static ProgressionConfig load(JavaPlugin plugin,boolean createDefaults) {
        for(String file:List.of("ranking.yml","lobby.yml","cosmetics.yml"))if(createDefaults && !new java.io.File(plugin.getDataFolder(),file).exists())plugin.saveResource(file,false);
        var ranking=read(plugin,"ranking.yml");var lobby=read(plugin,"lobby.yml");var cosmetics=read(plugin,"cosmetics.yml");var config=read(plugin,"config.yml");
        var bands=new ArrayList<RankingSettings.Band>();
        for(var band:ranking.getMapList("ranking.rating.placement-bands"))bands.add(new RankingSettings.Band(((Number)band.get("max-percentile")).doubleValue(),integer(band.get("delta"),"rating band delta")));
        var settings=new RankingSettings(integer(ranking.get("ranking.rating.initial"),"ranking.rating.initial"),integer(ranking.get("ranking.rating.minimum"),"ranking.rating.minimum"),bands,
                integer(ranking.get("ranking.kill-score.points-per-kill"),"ranking.kill-score.points-per-kill"),integer(ranking.get("ranking.kill-score.points-per-assist"),"ranking.kill-score.points-per-assist"),ZoneId.of(Objects.requireNonNull(ranking.getString("statistics.period-time-zone"))),integer(ranking.get("leaderboards.cache-seconds"),"leaderboards.cache-seconds"));
        var definitions=new LinkedHashMap<String,CosmeticDefinition>();
        var section=Objects.requireNonNull(cosmetics.getConfigurationSection("cosmetics"));
        for(String id:section.getKeys(false)) {
            var def=Objects.requireNonNull(section.getConfigurationSection(id));var effects=new HashMap<String,String>();
            var effectConfig=def.getConfigurationSection("effect-config");if(effectConfig!=null)effectConfig.getKeys(false).forEach(key->effects.put(key,effectConfig.getString(key)));
            var item=new CosmeticDefinition(id,CosmeticCategory.valueOf(Objects.requireNonNull(def.getString("category"))),Objects.requireNonNull(def.getString("display-name")),def.getStringList("description"),Objects.requireNonNull(def.getString("icon")),new BigDecimal(Objects.requireNonNull(def.get("price")).toString()),Objects.requireNonNull(def.getString("effect-type")),effects);
            if(!Material.valueOf(item.icon()).isItem() || Material.valueOf(item.icon()).isAir())throw new IllegalArgumentException("Cosmetic icon must be an item");
            if(effects.containsKey("color") && !effects.get("color").matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Cosmetic color must be six RGB hex digits");
            if(effects.containsKey("particle")) {var particle=Particle.valueOf(effects.get("particle"));if(particle.getDataType()!=Void.class)throw new IllegalArgumentException("Cosmetic particle requires unsupported data");}
            if(effects.containsKey("sound") && Registry.SOUNDS.get(NamespacedKey.minecraft(effects.get("sound")))==null)throw new IllegalArgumentException("Unknown cosmetic sound");
            if(effects.containsKey("material") && !Material.valueOf(effects.get("material")).isBlock())throw new IllegalArgumentException("DeathBox material must be a block");
            if(item.category()==CosmeticCategory.DEATHBOX_SKIN && !effects.containsKey("material"))throw new IllegalArgumentException("Missing skin material");
            definitions.put(id,item);
        }
        var menu=new LinkedHashMap<String,MenuItem>();var slots=new HashSet<Integer>();
        var items=Objects.requireNonNull(lobby.getConfigurationSection("items"));
        for(String action:items.getKeys(false)) {
            if(!Set.of("rooms","autojoin","profile","leaderboard","shop","cosmetics").contains(action))throw new IllegalArgumentException("Invalid lobby action");
            var item=Objects.requireNonNull(items.getConfigurationSection(action));int slot=integer(item.get("slot"),"lobby slot");
            if(slot<0 || slot>35 || !slots.add(slot))throw new IllegalArgumentException("Conflicting or invalid lobby slot");
            if(!Material.valueOf(Objects.requireNonNull(item.getString("material"))).isItem() || Material.valueOf(item.getString("material")).isAir())throw new IllegalArgumentException("Lobby icon must be an item");
            menu.put(action,new MenuItem(slot,Material.valueOf(Objects.requireNonNull(item.getString("material"))),Objects.requireNonNull(item.getString("name")),item.getStringList("lore")));
        }
        var titles=new HashMap<String,String>();for(String name:List.of("rooms","profile","leaderboard","shop","cosmetics","confirmation"))titles.put(name,Objects.requireNonNull(lobby.getString("titles."+name)));
        var priority=config.getStringList("economy.auto-priority");if(priority.isEmpty())priority=List.of("coinsengine","excellenteconomy","vault");
        if(new HashSet<>(priority).size()!=priority.size() || !Set.of("coinsengine","excellenteconomy","vault").containsAll(priority))throw new IllegalArgumentException("Invalid economy priority");
        return new ProgressionConfig(settings,definitions,menu,titles,priority,config.getString("economy.currency","coins"),config.getBoolean("economy.shop-enabled",true));
    }
    private static int integer(Object value,String path) {
        if(!(value instanceof Number))throw new IllegalArgumentException(path+" must be an integer");
        try{return new BigDecimal(value.toString()).intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException(path+" must be a 32-bit integer",error);}
    }
    private static YamlConfiguration read(JavaPlugin plugin,String name) {
        var yaml=new YamlConfiguration();try{String text=java.nio.file.Files.readString(plugin.getDataFolder().toPath().resolve(name));var options=new org.yaml.snakeyaml.LoaderOptions();options.setAllowDuplicateKeys(false);new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(text);yaml.loadFromString(text);ConfigMigrationService.version(yaml);return yaml;}catch(Exception error){throw new IllegalArgumentException("Cannot load "+name,error);}
    }
}
