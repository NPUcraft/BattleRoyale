package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.paper.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Native Paper metadata/serialization and entity-origin checks; no simulated client/player kill. */
public final class Rc6LootProbe {
    private final JavaPlugin plugin;
    private final NativeLootItems items=new NativeLootItems();
    private final YamlConfiguration report=new YamlConfiguration();
    private World world;
    private PaperMobLoot mobLoot;
    public Rc6LootProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc6"),"Explicit isolated rc6 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in isolated fixture");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc6loot all");
        Throwable failure=null;
        try{
            report.set("platform",plugin.getServer().getVersion());
            report.set("limits",List.of("Native ItemMeta serialization and dedicated generated world only.",
                    "No actual player kill, potion drinking/throwing, firework crossbow shot, client rendering or remote map tested."));
            potions();firework();enchantments();guarantees();mobRewards();origins();
        }catch(Throwable error){failure=error;}
        finally{
            if(mobLoot!=null){mobLoot.close();mobLoot=null;}
            if(world!=null){try{require(plugin.getServer().unloadWorld(world,true),"Dedicated fixture unloads normally");}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}world=null;}
        }
        try{
            report.set("status",failure==null?"passed":"failed");if(failure!=null)report.set("failure",failure.toString());
            Path file=plugin.getDataFolder().toPath().resolve("rc6-loot/report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
            if(failure==null)sender.sendMessage("RC6 LOOT SUCCESS potions=OK fireworks=OK enchantments=OK guarantees=OK mob-origin=OK path="+file.toAbsolutePath());
            else throw new IllegalStateException("RC6 LOOT FAILED",failure);
        }catch(Throwable error){sender.sendMessage("RC6 LOOT FAILED "+error);plugin.getLogger().log(Level.SEVERE,"RC6 LOOT FAILED",error);}
    }
    private void potions(){
        String[] names={"invisibility","fire_resistance","healing","harming","poison"};
        int[] durations={600,1200,1,1,160};
        PotionEffectType[] effects={PotionEffectType.INVISIBILITY,PotionEffectType.FIRE_RESISTANCE,PotionEffectType.INSTANT_HEALTH,PotionEffectType.INSTANT_DAMAGE,PotionEffectType.POISON};
        for(int i=0;i<names.length;i++)for(boolean splash:new boolean[]{false,true}){
            String key="battleroyale:"+(splash?"splash_":"")+names[i]+"_potion";items.validate(key);ItemStack item=items.resolve(key);
            require(item.getType()==(splash?Material.SPLASH_POTION:Material.POTION),"Native potion item type "+key);
            var meta=(PotionMeta)item.getItemMeta();require(meta.getBasePotionType()==PotionType.WATER,"No additional vanilla long effect "+key);
            require(meta.getCustomEffects().size()==1&&meta.getAllEffects().size()==1,"Exactly one effect "+key);
            var effect=meta.getCustomEffects().getFirst();require(effect.getType().equals(effects[i])&&effect.getAmplifier()==0&&effect.getDuration()==durations[i],"I-level duration "+key);
            require(Objects.requireNonNull(meta.displayName()) instanceof net.kyori.adventure.text.TranslatableComponent translated&&translated.key().equals("item.minecraft."+(splash?"splash_potion":"potion")+".effect."+names[i]),"Native client-localized descriptive potion name "+key);
            require(ItemStack.deserializeBytes(item.serializeAsBytes()).isSimilar(item),"Native potion metadata survives byte serialization "+key);
        }
        rejected("minecraft:elytra");rejected("battleroyale:strong_poison_potion");rejected("battleroyale:unknown_potion");
        report.set("potions.presets",10);report.set("potions.level",1);report.set("potions.ticks",List.of(600,1200,1,1,160));report.set("potions.native-byte-roundtrip",true);
    }
    private void firework(){
        items.validate("battleroyale:combat_firework");var item=items.resolve("battleroyale:combat_firework");
        require(item.getType()==Material.FIREWORK_ROCKET,"Native firework rocket");var meta=(FireworkMeta)item.getItemMeta();
        require(meta.hasPower()&&meta.getPower()==0&&meta.getEffectsSize()==1,"Short flight with exactly one explosion star");
        require(meta.getEffects().getFirst().getType()==FireworkEffect.Type.BALL,"Small ball effect");
        require(ItemStack.deserializeBytes(item.serializeAsBytes()).isSimilar(item),"Native firework byte roundtrip");
        report.set("firework.power",0);report.set("firework.explosion-stars",1);report.set("firework.native-byte-roundtrip",true);
    }
    private void enchantments(){
        var random=new Random(6);int enchanted=0;
        for(int i=0;i<1000;i++){var item=items.roll("minecraft:iron_sword",random);if(!item.getEnchantments().isEmpty()){enchanted++;safe(item);}}
        require(enchanted>=280&&enchanted<=420,"Ordinary equipment approximately 35 percent enchanted: "+enchanted);
        for(String key:List.of("minecraft:bow","minecraft:crossbow","minecraft:diamond_axe","minecraft:iron_helmet","minecraft:iron_chestplate",
                "minecraft:iron_leggings","minecraft:iron_boots","minecraft:trident","minecraft:mace","minecraft:stone_pickaxe","minecraft:shield"))
            for(int i=0;i<40;i++)safe(items.roll(key,random));
        for(String key:List.of("minecraft:bread","minecraft:arrow","battleroyale:poison_potion","battleroyale:combat_firework"))
            for(int i=0;i<40;i++)require(items.roll(key,random).getEnchantments().isEmpty(),"Non-equipment stays unenchanted "+key);
        report.set("enchantment.ordinary-sword-sample",1000);report.set("enchantment.enchanted",enchanted);report.set("enchantment.applicable-level-1-or-2-only",true);
    }
    private void guarantees(){
        Set<Material> allowed=Set.of(Material.DIAMOND_SWORD,Material.DIAMOND_AXE,Material.DIAMOND_HELMET,Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS,Material.BOW);
        var random=new Random(7);var seen=new HashSet<Material>();
        for(int i=0;i<200;i++){
            var guaranteed=items.airdropGuarantees(random);require(guaranteed.size()==2,"Exactly two guarantee stacks");
            var gear=guaranteed.getFirst();seen.add(gear.getType());require(allowed.contains(gear.getType())&&gear.getAmount()==1&&!gear.getEnchantments().isEmpty(),"Guaranteed enchanted diamond gear or bow");
            for(var enchant:gear.getEnchantments().entrySet())require(enchant.getKey().canEnchantItem(gear)&&enchant.getValue()>=1&&enchant.getValue()<=enchant.getKey().getMaxLevel(),"rc7 supply upgrade remains within native enchantment limits");
            require(guaranteed.getLast().getType()==Material.TOTEM_OF_UNDYING&&guaranteed.getLast().getAmount()==1,"Exactly one totem");
        }
        require(seen.equals(allowed),"All guaranteed equipment selections sampled");report.set("airdrop.guarantee-samples",200);report.set("airdrop.all-seven-equipment-types",true);
    }
    private void mobRewards(){
        var source=new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,4096,4096)));
        var settings=new MobLootSettings(true,"basic",1,3,3,64,10);var table=settings.resolvedTable(Map.of("basic",source));
        var rewards=PaperMobLoot.rewards(table,items,new Random(8));require(rewards.size()==3&&rewards.stream().allMatch(item->item.getType()==Material.BREAD&&item.getAmount()==8),"At most three stacks of eight");
        var gearTable=new LootTable("basic",3,3,List.of(new LootTable.Entry("minecraft:iron_sword",1,1,8)));
        require(PaperMobLoot.rewards(gearTable,items,new Random(9)).stream().allMatch(item->item.getAmount()==1),"Native equipment maximum stack size honored");
        require(!MobLootPolicy.canAttempt(settings,64,100_000,Long.MIN_VALUE)&&!MobLootPolicy.canAttempt(settings,0,10_999,1000),"Persisted cap/cooldown inputs remain enforced");
        report.set("mob-drops.maximum-stacks",3);report.set("mob-drops.maximum-items-per-stack",8);
    }
    private void origins(){
        NamespacedKey key=new NamespacedKey("battleroyale_probe","rc6_loot_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.NORMAL);world.setTime(18000);world.setGameRule(GameRules.SPAWN_MOBS,false);
        // Only the spawn handler is used; the constructor intentionally requires no initialized runtime.
        mobLoot=new PaperMobLoot(plugin,null);var marker=new NamespacedKey(plugin,"mob_loot_excluded");
        var expected=new LinkedHashMap<UUID,SpawnReason>();int x=0;
        for(var reason:List.of(SpawnReason.NATURAL,SpawnReason.CHUNK_GEN,SpawnReason.SPAWNER,SpawnReason.SPAWNER_EGG,SpawnReason.DISPENSE_EGG,SpawnReason.CUSTOM)){
            var mob=world.spawn(new Location(world,++x+.5,80,2.5),Zombie.class,reason,false,entity->{entity.setAI(false);entity.setPersistent(true);entity.setRemoveWhenFarAway(false);});
            require(mob.getEntitySpawnReason()==reason,"Paper records actual spawn origin "+reason);
            require(mob.getPersistentDataContainer().has(marker,PersistentDataType.BYTE)!=MobLootPolicy.natural(reason.name()),"Player/farm-created entity tagged "+reason);
            expected.put(mob.getUniqueId(),reason);
        }
        require(plugin.getServer().unloadWorld(world,true),"Entity fixture saved and unloaded");world=null;
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key)));world.getChunkAt(0,0).getEntities();
        for(var entry:expected.entrySet()){
            var entity=plugin.getServer().getEntity(entry.getKey());require(entity!=null,"Entity restored from native save "+entry.getValue());
            require(entity.getEntitySpawnReason()==entry.getValue(),"Native origin persists across world reload "+entry.getValue());
            require(entity.getPersistentDataContainer().has(marker,PersistentDataType.BYTE)!=MobLootPolicy.natural(entry.getValue().name()),"Origin exclusion survives world reload "+entry.getValue());
        }
        report.set("mob-origin.actual-spawn-reasons",expected.values().stream().map(Enum::name).toList());report.set("mob-origin.native-save-reload-exclusions",true);
    }
    private void rejected(String key){
        boolean validateRejected=false,resolveRejected=false;
        try{items.validate(key);}catch(IllegalArgumentException expected){validateRejected=true;}
        try{items.resolve(key);}catch(IllegalArgumentException expected){resolveRejected=true;}
        require(validateRejected&&resolveRejected,"Reject disallowed native key "+key);
    }
    private static void safe(ItemStack item){
        require(item.getType()!=Material.ELYTRA&&item.getEnchantments().size()<=1,"No elytra or enchantment stacks");
        Set<Enchantment> allowed=Set.of(Enchantment.SHARPNESS,Enchantment.PROTECTION,Enchantment.FEATHER_FALLING,Enchantment.POWER,
                Enchantment.QUICK_CHARGE,Enchantment.IMPALING,Enchantment.DENSITY,Enchantment.EFFICIENCY,Enchantment.UNBREAKING);
        for(var entry:item.getEnchantments().entrySet())require(allowed.contains(entry.getKey())&&entry.getKey().canEnchantItem(item)
                &&entry.getValue()>=1&&entry.getValue()<=2&&entry.getValue()<=entry.getKey().getMaxLevel(),"Only appropriate safe enchantments");
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
