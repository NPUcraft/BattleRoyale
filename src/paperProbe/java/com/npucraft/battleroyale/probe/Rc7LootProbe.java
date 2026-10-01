package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.paper.NativeLootItems;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Real native enchantment compatibility and deployed material catalog checks. */
public final class Rc7LootProbe {
    private final JavaPlugin plugin;
    public Rc7LootProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args)throws Exception{
        require(Boolean.getBoolean("battleroyale.probe.rc7")&&plugin.getServer().getOnlinePlayers().isEmpty(),"Isolated rc7 only");
        require(args.length==2&&args[1].equals("all"),"p26rc7loot all");
        var factory=new NativeLootItems();var random=new Random(27);int maximum=0,stacked=0;
        for(int i=0;i<2000;i++){
            var batch=factory.airdropGuarantees(random);require(batch.size()==2,"Two guarantee stacks");
            var gear=batch.getFirst();legal(gear);require(gear.getAmount()==1&&!gear.getEnchantments().isEmpty(),"Guaranteed enchanted equipment");
            require(batch.getLast().getType()==Material.TOTEM_OF_UNDYING&&batch.getLast().getAmount()==1,"One totem");
            if(gear.getEnchantments().size()>1)stacked++;
            if(gear.getEnchantments().entrySet().stream().anyMatch(e->e.getKey().getMaxLevel()>=4&&e.getValue()==e.getKey().getMaxLevel()))maximum++;
            if(i%20==0)require(ItemStack.deserializeBytes(gear.serializeAsBytes()).isSimilar(gear),"Native byte round trip");
        }
        require(maximum>100&&stacked>350,"High tiers and native maximum rolls reachable");
        int enchanted=0;
        for(int i=0;i<1000;i++){var item=factory.rollAirdrop("minecraft:iron_sword",random);legal(item);if(!item.getEnchantments().isEmpty())enchanted++;}
        require(enchanted>720&&enchanted<880,"Random supply equipment approximately 80 percent enchanted");
        for(String key:List.of("minecraft:bread","minecraft:diamond","minecraft:gold_ingot","minecraft:oak_log","battleroyale:healing_potion"))
            for(int i=0;i<30;i++)require(factory.rollAirdrop(key,random).getEnchantments().isEmpty(),"Resources do not receive invalid enchantments");
        var main=Objects.requireNonNull(plugin.getServer().getPluginManager().getPlugin("BattleRoyale"));
        var catalog=YamlConfiguration.loadConfiguration(main.getDataFolder().toPath().resolve("loot-tables.yml").toFile());
        int nativeBasicEntries=0;
        for(String table:catalog.getConfigurationSection("loot-tables").getKeys(false)){
            var entries=catalog.getMapList("loot-tables."+table+".entries");var present=new HashSet<String>();
            for(var entry:entries){String key=entry.get("item").toString();present.add(key);require(!key.equals("minecraft:elytra"),"No elytra");
                if(key.equals("minecraft:diamond"))require(((Number)entry.get("weight")).intValue()==1&&((Number)entry.get("max-amount")).intValue()==1,"Diamonds are rare single-item rolls");}
            if(table.equals("native-basic"))nativeBasicEntries=nativeBasic(catalog,factory,entries,present);
            else if(table.equals(catalog.getString("region-quality.natural-table")))
                require(present.containsAll(List.of("minecraft:oak_log","minecraft:cobblestone","minecraft:iron_ingot","minecraft:coal","minecraft:stone_sword","minecraft:bow")),"Natural area retains basic combat and resource supplies");
            else if(table.equals(catalog.getString("region-quality.built-table")))
                require(present.containsAll(List.of("minecraft:iron_chestplate","minecraft:iron_sword","minecraft:diamond","minecraft:iron_ingot","minecraft:coal")),"Built area improves equipment while retaining resources");
            else require(present.containsAll(List.of("minecraft:oak_log","minecraft:cobblestone","minecraft:stone","minecraft:iron_ingot","minecraft:gold_ingot","minecraft:coal","minecraft:diamond")),"Requested materials in "+table);
        }
        require(nativeBasicEntries>0,"rc11 native-basic table must be present and independently verified");
        var report=new YamlConfiguration();report.set("status","passed");report.set("guarantee-samples",2000);report.set("native-max-samples",maximum);report.set("multi-enchanted-samples",stacked);report.set("random-airdrop-enchanted",enchanted);
        report.set("native-basic.entries",nativeBasicEntries);report.set("native-basic.basic-only",true);report.set("native-basic.chance",.15);
        report.set("native-basic.rolls",List.of(1,2));report.set("native-basic.unenchanted-native-roundtrip",true);
        report.set("limits","Native item metadata and production YAML, not an actual player loot pickup.");
        AtomicFiles.write(plugin.getDataFolder().toPath().resolve("rc7/loot-report.yml"),report.saveToString().getBytes(StandardCharsets.UTF_8));
        sender.sendMessage("RC7 LOOT SUCCESS max-levels=OK compatible=OK materials=OK");
    }
    /** This deployed table is intentionally poorer than field and airdrop catalogs; never skip its checks. */
    private static int nativeBasic(YamlConfiguration catalog,NativeLootItems factory,List<Map<?,?>> entries,Set<String> present){
        var allowed=Set.of("minecraft:bread","minecraft:apple","minecraft:cooked_chicken","minecraft:stick",
                "minecraft:oak_log","minecraft:cobblestone","minecraft:coal","minecraft:torch","minecraft:string",
                "minecraft:flint","minecraft:arrow","minecraft:stone_sword","minecraft:stone_pickaxe","minecraft:stone_axe",
                "minecraft:leather_helmet","minecraft:leather_boots");
        require(present.equals(allowed)&&entries.size()==allowed.size(),"Native basic table contains exactly the survival whitelist without duplicates or advanced supplies: "+present);
        require(catalog.getBoolean("auto-containers.enabled")&&"native-basic".equals(catalog.getString("auto-containers.loot-table")),"Automatic map containers select native-basic");
        require(Math.abs(catalog.getDouble("auto-containers.chance")-.15)<1e-9,"Native basic containers retain 15 percent generation chance");
        for(String path:List.of("auto-containers","loot-tables.native-basic"))
            require(catalog.getInt(path+".min-rolls")==1&&catalog.getInt(path+".max-rolls")==2,"Native basic 1-2 rolls: "+path);
        for(var entry:entries){
            String key=entry.get("item").toString();factory.validate(key);var item=factory.resolve(key);legal(item);
            int weight=((Number)entry.get("weight")).intValue(),min=((Number)entry.get("min-amount")).intValue(),max=((Number)entry.get("max-amount")).intValue();
            require(weight>0&&min>=1&&max>=min&&max<=Math.min(8,item.getMaxStackSize()),"Small legal native basic quantities: "+key);
            require(item.getEnchantments().isEmpty(),"Native basic equipment resolves without enchantments: "+key);
            item.setAmount(max);var restored=ItemStack.deserializeBytes(item.serializeAsBytes());
            require(restored.equals(item)&&restored.getEnchantments().isEmpty(),"Basic material, quantity and unenchanted metadata survive native serialization: "+key);
        }
        return entries.size();
    }
    private static void legal(ItemStack item){
        require(item.getType()!=Material.ELYTRA,"No elytra");
        for(var entry:item.getEnchantments().entrySet()){
            var enchant=entry.getKey();require(enchant!=Enchantment.BINDING_CURSE&&enchant!=Enchantment.VANISHING_CURSE,"No curses");
            require(enchant.canEnchantItem(item)&&entry.getValue()>0&&entry.getValue()<=enchant.getMaxLevel(),"Applicable native levels only");
            for(var other:item.getEnchantments().keySet())if(other!=enchant)require(!enchant.conflictsWith(other)&&!other.conflictsWith(enchant),"No incompatible pair");
        }
    }
    private static void require(boolean test,String message){if(!test)throw new IllegalStateException("RC7 loot assertion: "+message);}
}
