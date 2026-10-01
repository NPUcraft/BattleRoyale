package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.zone.Zone;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Real chunk/PDC save, native inventories and production scheduler; no connected player is fabricated. */
public final class Rc10QualityProbe {
    private final JavaPlugin plugin;private boolean busy;private World world;private NamespacedKey key;
    private UUID session;private PaperLootRegionQuality quality;private WorldSanitizer sanitizer;private PaperAutoContainerLoot containers;
    private final YamlConfiguration report=new YamlConfiguration();
    public Rc10QualityProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc10"),"Explicit isolated rc10 flag");
        require(!busy&&plugin.getServer().getOnlinePlayers().isEmpty(),"No current probe or connected players");
        require(args.length==2&&args[1].equals("all"),"p26rc10quality all");busy=true;CompletableFuture<Void> work;
        try{setup();work=until(()->filled(4)&&filled(5),200,"Production automatic containers receive regional contents")
                .thenRun(this::checkContainers).thenCompose(unused->repeatContainerPass()).thenRun(this::saveReload);}
        catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->{
            Throwable failure=error;
            try{cleanup();}catch(Throwable clean){if(failure==null)failure=clean;else failure.addSuppressed(clean);}
            try{
                report.set("status",failure==null?"passed":"failed");report.set("limits",List.of("Dedicated generated local world; no remote or pre-existing map is changed.","Actual Paper chunks, block material reads, automatic inventory generation, chunk PDC and world save/unload/reload are tested.","Surface alterations stand for later player construction; no player client interaction is claimed."));
                if(failure!=null)report.set("failure",failure.toString());
                var path=plugin.getDataFolder().toPath().resolve("rc10/quality-report.yml");AtomicFiles.write(path,report.saveToString().getBytes(StandardCharsets.UTF_8));
                if(failure!=null)throw new CompletionException(failure);
                sender.sendMessage("RC10 QUALITY SUCCESS sampled=bounded cached=stable containers=basic recovery=durable path="+path.toAbsolutePath());
            }catch(Throwable failed){sender.sendMessage("RC10 QUALITY FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC10 QUALITY FAILED",failed);}finally{busy=false;}
        });
    }
    private void setup(){
        session=UUID.randomUUID();key=new NamespacedKey("battleroyale_probe","rc10_quality_"+session.toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        for(int x=4;x<=6;x++){Chunk chunk=world.getChunkAt(x,4);chunk.addPluginChunkTicket(plugin);surface(chunk,x==4?Material.STONE_BRICKS:Material.GRASS_BLOCK);}
        chest(4).getBlock().setType(Material.CHEST,false);chest(5).getBlock().setType(Material.CHEST,false);
        var stale=world.getChunkAt(5,4).getPersistentDataContainer();stale.set(new NamespacedKey(plugin,"loot_quality_session"),PersistentDataType.STRING,UUID.randomUUID().toString());
        stale.set(new NamespacedKey(plugin,"loot_quality_sample"),PersistentDataType.INTEGER_ARRAY,new LootRegionSample(LootRegionQuality.BUILT,16,16).encode());
        int loaded=world.getLoadedChunks().length;
        quality=new PaperLootRegionQuality(plugin,world,session,LootRegionQualitySettings.DEFAULT,false);
        require(world.getLoadedChunks().length==loaded,"Quality construction never loads neighbouring chunks");
        require(quality.quality(world.getChunkAt(4,4))==LootRegionQuality.BUILT,"Authored brick surface scores built");
        require(quality.quality(world.getChunkAt(5,4))==LootRegionQuality.NATURAL,"Natural terrain ignores stale prior-session quality");
        long reads=quality.blockReads(),samples=quality.sampledChunks();require(reads<=samples*48,"At most sixteen surface columns and forty-eight block reads per chunk");
        surface(world.getChunkAt(5,4),Material.OAK_PLANKS);
        for(int n=0;n<100;n++)require(quality.quality(world.getChunkAt(5,4))==LootRegionQuality.NATURAL,"Later construction cannot farm improved quality");
        require(quality.blockReads()==reads&&quality.sampledChunks()==samples,"Repeated quality selection performs zero extra terrain reads");
        report.set("sample.initial-count",samples);report.set("sample.block-reads",reads);report.set("sample.no-additional-chunks",true);report.set("sample.later-construction-cached",true);
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot_session"));sanitizer.register(world,session,error->{throw new CompletionException(error);});
        startContainers();
    }
    private Chest chest(int chunkX){
        var block=world.getBlockAt(chunkX*16+8,91,72);if(block.getType()!=Material.CHEST)block.setType(Material.CHEST,false);return (Chest)block.getState();
    }
    private boolean filled(int x){return !chest(x).getBlockInventory().isEmpty();}
    private Map<String,LootTable> tables(){return Map.of(
            "basic",new LootTable("basic",1,1,List.of(new LootTable.Entry("minecraft:bread",1,1,1))),
            "region-natural",new LootTable("region-natural",8,8,List.of(new LootTable.Entry("minecraft:stone_sword",1,1,1))),
            "region-built",new LootTable("region-built",12,12,List.of(new LootTable.Entry("minecraft:iron_sword",1,1,1))));}
    private void startContainers(){containers=new PaperAutoContainerLoot(plugin,world,session,new Zone(88,72,64),sanitizer,
            new AutoContainerLootSettings(true,"basic",1,1,1,16),tables(),List.of(),new NativeLootItems(),new Random(10),new PaperScheduler(plugin),quality);}
    private void checkContainers(){
        var built=Arrays.stream(chest(4).getBlockInventory().getContents()).filter(Objects::nonNull).toList();
        var natural=Arrays.stream(chest(5).getBlockInventory().getContents()).filter(Objects::nonNull).toList();
        require(built.size()==1&&built.getFirst().getType()==Material.BREAD,"Built map-native container retains its independent basic table");
        require(natural.size()==1&&natural.getFirst().getType()==Material.BREAD,"Natural map-native container uses the same independent basic table");
        for(var item:List.of(built.getFirst(),natural.getFirst()))require(item.getEnchantments().isEmpty(),"Map-native containers add no regional or random enchantments");
        report.set("containers.built",built.getFirst().getType().name());report.set("containers.natural",natural.getFirst().getType().name());report.set("containers.exactly-one-stack",true);
    }
    private CompletableFuture<Void> repeatContainerPass(){
        containers.close();chest(4).getBlockInventory().clear();chest(5).getBlockInventory().clear();startContainers();
        var done=new CompletableFuture<Void>();plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            try{require(!filled(4)&&!filled(5),"Regional tables do not bypass durable once-per-container markers");report.set("containers.no-refill",true);done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}
        },20);return done;
    }
    private void saveReload(){
        containers.close();containers=null;sanitizer.close();sanitizer=null;quality.close();quality=null;
        // Simulate a recovered, previously visited legacy chunk with no quality metadata.
        var unknown=world.getChunkAt(6,4);surface(unknown,Material.OAK_PLANKS);
        unknown.getPersistentDataContainer().remove(new NamespacedKey(plugin,"loot_quality_session"));unknown.getPersistentDataContainer().remove(new NamespacedKey(plugin,"loot_quality_sample"));
        UUID worldId=world.getUID();world.save();releaseTickets();require(plugin.getServer().unloadWorld(world,true),"Fixture saves and unloads normally");
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key)));require(world.getUID().equals(worldId),"Actual reload retains the world UUID");
        quality=new PaperLootRegionQuality(plugin,world,session,LootRegionQualitySettings.DEFAULT,true);
        require(quality.quality(world.getChunkAt(4,4))==LootRegionQuality.BUILT,"Built sample survives actual save and reload");
        require(quality.quality(world.getChunkAt(5,4))==LootRegionQuality.NATURAL,"Cached natural sample survives reload despite changed surface");
        require(quality.quality(world.getChunkAt(6,4))==LootRegionQuality.NATURAL,"Legacy recovered chunk cannot gain quality from later construction");
        require(quality.sampledChunks()==0&&quality.blockReads()==0,"Recovery reuses PDC or conservative defaults without terrain rescans");
        quality.close();quality=new PaperLootRegionQuality(plugin,world,UUID.randomUUID(),LootRegionQualitySettings.DEFAULT,false);
        require(quality.quality(world.getChunkAt(5,4))==LootRegionQuality.BUILT,"Fresh session discards the old map-copy quality identity");
        report.set("recovery.durable-built-and-natural",true);report.set("recovery.legacy-conservative",true);report.set("recovery.fresh-session-resamples",true);
    }
    private void surface(Chunk chunk,Material type){for(int x=0;x<16;x++)for(int z=0;z<16;z++)chunk.getBlock(x,90,z).setType(type,false);}
    private void releaseTickets(){for(var chunk:List.copyOf(world.getPluginChunkTickets().getOrDefault(plugin,List.of())))chunk.removePluginChunkTicket(plugin);}
    private void cleanup(){if(containers!=null)containers.close();if(sanitizer!=null)sanitizer.close();if(quality!=null)quality.close();if(world!=null){releaseTickets();require(plugin.getServer().unloadWorld(world,true),"Fixture cleanup unload");}}
    private CompletableFuture<Void> until(BooleanSupplier condition,int max,String description){var result=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();result.complete(null);}else if(++ticks>=max)throw new IllegalStateException(description);}catch(Throwable error){cancel();result.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return result;}
    private static void require(boolean test,String message){if(!test)throw new IllegalStateException("RC10 quality assertion: "+message);}
}
