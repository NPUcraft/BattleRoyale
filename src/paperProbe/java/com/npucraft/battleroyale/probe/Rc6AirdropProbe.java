package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.combat.ProtectionWindow;
import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.map.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.room.RoomDefinition;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Isolated real-Paper airdrop lifecycle checks with an explicitly advanced session clock. No real client. */
public final class Rc6AirdropProbe {
    private final JavaPlugin plugin;
    private boolean busy;
    private World world;
    private WorldSanitizer sanitizer;
    private PaperAirdrops drops;
    private ExecutorService io;
    private GameSession session;
    private ZoneRuntime zone;
    private MatchContent content;
    private UUID sessionId;
    private final YamlConfiguration report=new YamlConfiguration();
    public Rc6AirdropProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc6"),"Explicit isolated rc6 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in isolated fixture");
        require(!busy,"Probe not already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc6airdrop all");
        busy=true;sessionId=UUID.randomUUID();io=Executors.newSingleThreadExecutor();
        CompletableFuture<Void> work;
        try{setup();capacity();work=firstDrop().thenCompose(unused->blockedLocation()).thenCompose(unused->stopAndRecoverWarning());}
        catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            var stopped=drops==null?CompletableFuture.<Void>completedFuture(null):drops.stop();
            stopped.whenComplete((ignored,stopError)->main(()->{
                Throwable failure=error==null?stopError:error;
                try{
                    io.shutdown();
                    if(world!=null){require(tickets()==0,"No residual airdrop chunk tickets");if(sanitizer!=null)sanitizer.remove(world.getUID());require(plugin.getServer().unloadWorld(world,true),"Unload isolated fixture");}
                    report.set("status",failure==null?"passed":"failed");
                    report.set("limits",List.of("Dedicated local flat fixture only; no production server or map modified.","Real Paper chunk tickets, barrel inventory, BlockDisplay and native enchantment APIs.","Session time is advanced explicitly; no 60-second wall-clock wait, player chat receipt or client beam rendering is claimed."));
                    Path file=plugin.getDataFolder().toPath().resolve("rc6/airdrop-report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                    if(failure!=null)throw new CompletionException(failure);
                    sender.sendMessage("RC6 AIRDROP SUCCESS warning=60s same-location=OK guarantees=OK cancellation=OK recovery=OK path="+file.toAbsolutePath());
                }catch(Throwable failed){sender.sendMessage("RC6 AIRDROP FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC6 AIRDROP FAILED",failed);}
                finally{busy=false;}
            }));
        }));
    }
    private void setup(){
        NamespacedKey key=new NamespacedKey("battleroyale_probe","rc6_airdrop_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        for(int x=48;x<=96;x++)for(int z=48;z<=96;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot"));sanitizer.register(world,sessionId,error->{throw new CompletionException(error);});
        var room=new RoomDefinition("rc6","RC6空投测试",1,2,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","RC6 Flat Fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
        session=GameSession.waiting(sessionId,room,Instant.now());session.join(UUID.randomUUID());session.prepare(map,new Random(3));
        Zone initial=new Zone(72,72,24);session.initialZone(initial);session.starting(new GameWorld(sessionId,room.id(),world.getName(),world.getWorldPath(),map));session.transition(GameState.RUNNING);
        var profile=new ZoneProfile("probe",List.of(new ZoneProfile.InitialSize(2,500)),List.of(
            new ZoneProfile.Stage(Duration.ofSeconds(1),Duration.ofSeconds(600),12,0,0,0),
            new ZoneProfile.Stage(Duration.ofSeconds(1),Duration.ofSeconds(600),6,0,0,0),
            new ZoneProfile.Stage(Duration.ofSeconds(120),Duration.ofSeconds(600),3,0,0,0)));
        zone=new ZoneRuntime(initial,profile,new Random(3),0);session.runningZone(zone,new ProtectionWindow(0,Duration.ZERO));
        var tables=Map.of("basic",new LootTable("basic",2,2,List.of(new LootTable.Entry("minecraft:bread",1,3,3))));
        content=new MatchContent(Map.of(),tables,Map.of(),Map.of(),new AutoContainerLootSettings(false,"basic",1,2,2,16),new AirdropSettings(true,"basic",2,2,8,24,120));
        drops=new PaperAirdrops(plugin,session,sanitizer,content,io,false);
        report.set("platform",plugin.getServer().getVersion());report.set("world",world.getName());
    }
    private CompletableFuture<Void> firstDrop(){
        return warning(0).thenCompose(unused->{
            var at=drops.announcement().orElseThrow();
            require(at.startedNanos()==0&&at.remainingSeconds(0)==60,"Short one-second WAIT still starts a full 60-second announcement");
            require(tickets()==1&&visuals()==0&&crates().isEmpty(),"Announcement pins one chunk without a premature crate/display");
            require(PaperAirdrops.announcementText(at).contains(at.coordinates())&&!PaperAirdrops.announcementText(at).contains("图腾")&&!PaperAirdrops.announcementText(at).contains("钻石"),"Announcement reveals location but no rewards");
            tick(59_999_999_999L);require(visuals()==0&&crates().isEmpty(),"Not one nanosecond before the complete minute");
            require(drops.announcement().orElseThrow().equals(at),"Warning coordinates unchanged while circle shrinks");
            tick(60_000_000_000L);require(visuals()==1&&tickets()==1,"Descent starts exactly after the warning interval");
            var display=world.getEntitiesByClass(BlockDisplay.class).stream().filter(entity->entity.getPersistentDataContainer().has(new NamespacedKey(plugin,"airdrop"))).findFirst().orElseThrow();
            require(display.getLocation().getX()==at.x()+.5&&display.getLocation().getZ()==at.z()+.5,"Display descends over the announced X/Z");
            tick(67_999_999_999L);require(crates().isEmpty(),"No crate before eight-second descent completes");
            tick(68_000_000_000L);var crates=crates();require(crates.size()==1,"Exactly one landed supply crate");var crate=crates.getFirst();
            require(crate.getX()==at.x()&&crate.getY()==at.y()&&crate.getZ()==at.z(),"Actual barrel uses the exact announced XYZ");
            require(amount(crate.getInventory(),Material.TOTEM_OF_UNDYING)==1&&amount(crate.getInventory(),Material.BREAD)==6,"Guaranteed totem plus configured random supplies");
            require(Arrays.stream(crate.getInventory().getContents()).filter(Objects::nonNull).anyMatch(Rc6AirdropProbe::guaranteedEquipment),"Landed crate contains enchanted diamond equipment or enchanted bow");
            require(tickets()==1&&visuals()==0&&drops.announcement().isEmpty(),"Landing retains only the native beacon ticket while supplies remain");
            report.set("warning.seconds",60);report.set("warning.location",List.of(at.x(),at.y(),at.z()));report.set("warning.same-xyz-on-landing",true);report.set("guarantees.landed-totem",1);report.set("guarantees.landed-equipment",true);
            return drops.stop();
        }).thenCompose(unused->{
            drops=new PaperAirdrops(plugin,session,sanitizer,content,io,true);
            return until(()->{tick(68_000_000_000L);return drops.diagnostics().contains("beaconRecovery=true");},240,"Physical beacon recovery completes before checking round deduplication").thenRun(()->{
                for(int i=0;i<20;i++)tick(68_000_000_000L);
                require(crates().size()==1&&drops.announcement().isEmpty()&&tickets()==0,"Recovery during shrinking does not replay the landed round");
            });
        });
    }
    private CompletableFuture<Void> blockedLocation(){
        return warning(601_000_000_000L).thenCompose(unused->{
            var at=drops.announcement().orElseThrow();require(at.stage()==1&&tickets()==1,"Second round warning owns its original point");
            world.getBlockAt(at.x(),at.y(),at.z()).setType(Material.STONE,false);
            tick(661_000_000_000L);
            require(drops.announcement().isEmpty()&&tickets()==0&&visuals()==0,"A blocked announced point cancels and releases resources");
            for(int i=0;i<20;i++)tick(661_000_000_000L);
            require(crates().size()==1&&drops.announcement().isEmpty(),"Cancellation never silently moves or duplicates the round");
            require(world.getBlockAt(at.x(),at.y(),at.z()).getType()==Material.STONE,"Cancellation preserves the new occupant");
            world.getBlockAt(at.x(),at.y(),at.z()).setType(Material.AIR,false);
            report.set("warning.occupied-point-cancels-without-relocation",true);return drops.stop();
        });
    }
    private CompletableFuture<Void> stopAndRecoverWarning(){
        drops=new PaperAirdrops(plugin,session,sanitizer,content,io,true);
        tick(1202_000_000_000L);require(drops.announcement().isEmpty()&&tickets()==0,"A long WAIT does not announce before its final minute");
        return warning(1262_000_000_000L).thenCompose(unused->{
            var at=drops.announcement().orElseThrow();require(at.stage()==2&&zone.phase()==ZonePhase.WAITING,"Third round announces during WAITING");
            require(tickets()==1,"Announced future crate keeps its chunk pinned");return drops.stop();
        }).thenCompose(unused->{
            require(tickets()==0&&visuals()==0&&drops.announcement().isEmpty(),"Stopping a warning removes its visual ownership and ticket");
            drops=new PaperAirdrops(plugin,session,sanitizer,content,io,true);
            return until(()->{tick(1262_000_000_000L);return drops.diagnostics().contains("beaconRecovery=true")&&drops.diagnostics().contains("stage=-1");},200,"Recovered warning ledger check");
        }).thenRun(()->{
            for(int i=0;i<20;i++)tick(1262_000_000_000L);
            int loadedBefore=world.getLoadedChunks().length;
            var observed=crates();var announced=drops.announcement();int pinned=tickets();long displays=visuals();
            report.set("warning.recovery-observed.loaded-chunks-before-inspection",loadedBefore);
            report.set("warning.recovery-observed.crates",observed.size());
            report.set("warning.recovery-observed.announcement",announced.map(Object::toString).orElse("none"));
            report.set("warning.recovery-observed.plugin-tickets",pinned);
            report.set("warning.recovery-observed.displays",displays);
            report.set("warning.recovery-observed.controller",drops.diagnostics());
            require(observed.size()==1,"Recovery retains exactly one landed crate after inspecting all fixture chunks; actual="+observed.size());
            require(announced.isEmpty(),"Recovery does not repeat the claimed WAITING announcement; actual="+announced);
            require(pinned==0,"Recovery leaves no chunk ticket; actual="+pinned);
            require(displays==0,"Recovery leaves no falling display; actual="+displays);
            try{require(!AirdropLedger.claim(world.getWorldPath(),sessionId,2),"Warning claim remains durable");}catch(Exception error){throw new CompletionException(error);}
            report.set("warning.stop-releases-ticket",true);report.set("warning.recovered-waiting-claim-not-replayed",true);
        });
    }
    private void capacity(){
        var crowded=new LootTable("crowded",27,27,List.of(new LootTable.Entry("minecraft:stone_sword",1,1,1)));
        for(int seed=0;seed<20;seed++){
            var items=PaperAirdrops.contents(crowded,new Random(seed));require(items.size()==27,"Container cap remains 27 stacks");
            require(guaranteedEquipment(items.getFirst())&&items.getFirst().getAmount()==1,"Guaranteed enchanted equipment takes capacity before random rolls");
            require(items.get(1).getType()==Material.TOTEM_OF_UNDYING&&items.get(1).getAmount()==1,"Guaranteed totem cannot be evicted by overflow");
            require(items.stream().noneMatch(item->item.getType()==Material.ELYTRA),"No elytra in supply crate");
        }
        report.set("guarantees.overflow-protected",true);report.set("guarantees.capacity",27);
    }
    private static boolean guaranteedEquipment(ItemStack item){return (item.getType().name().startsWith("DIAMOND_")||item.getType()==Material.BOW)&&!item.getEnchantments().isEmpty();}
    private CompletableFuture<Void> warning(long now){return until(()->{tick(now);return drops.announcement().isPresent();},240,"A fixed safe announcement location");}
    private void tick(long now){zone.update(now);drops.tick(zone,now);require(!drops.diagnostics().contains("FAILED"),"Airdrop controller healthy");}
    private List<Barrel> crates(){
        // The controller correctly releases its ticket after landing. With no players, Paper may
        // unload that chunk before the next async ledger check, so loaded-chunk-only scans lie.
        // Inspect the bounded 49x49 fixture, reloading saved chunks without creating plugin tickets.
        var result=new ArrayList<Barrel>();
        for(int cx=48>>4;cx<=96>>4;cx++)for(int cz=48>>4;cz<=96>>4;cz++)
            for(BlockState state:world.getChunkAt(cx,cz).getTileEntities(false))
                if(state instanceof Barrel barrel&&Optional.ofNullable(barrel.getPersistentDataContainer().get(new NamespacedKey(plugin,"airdrop"),PersistentDataType.STRING)).orElse("").startsWith(sessionId+":"))result.add(barrel);
        return result;
    }
    private long visuals(){return world.getEntitiesByClass(BlockDisplay.class).stream().filter(display->display.getPersistentDataContainer().has(new NamespacedKey(plugin,"airdrop"))).count();}
    private int tickets(){return world.getPluginChunkTickets().getOrDefault(plugin,List.of()).size();}
    private static int amount(Inventory inventory,Material type){return Arrays.stream(inventory.getContents()).filter(Objects::nonNull).filter(item->item.getType()==type).mapToInt(ItemStack::getAmount).sum();}
    private CompletableFuture<Void> until(BooleanSupplier condition,int limit,String description){
        var result=new CompletableFuture<Void>();new BukkitRunnable(){int ticks;public void run(){try{if(condition.getAsBoolean()){cancel();result.complete(null);}else if(++ticks>=limit)throw new IllegalStateException("Timed out: "+description);}catch(Throwable error){cancel();result.completeExceptionally(error);}}}.runTaskTimer(plugin,1,1);return result;
    }
    private void main(Runnable action){if(Bukkit.isPrimaryThread())action.run();else plugin.getServer().getScheduler().runTask(plugin,action);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("RC6 airdrop assertion: "+message);}
}
