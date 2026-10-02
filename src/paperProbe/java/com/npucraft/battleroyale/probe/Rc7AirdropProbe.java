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
import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import com.destroystokyo.paper.event.block.BeaconEffectEvent;
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
public final class Rc7AirdropProbe {
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
    private PaperAirdropBeacons helper;
    public Rc7AirdropProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc7"),"Explicit isolated rc7 flag required");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in isolated fixture");
        require(!busy,"Probe not already running");
        if(args.length!=2||!args[1].equals("all"))throw new IllegalArgumentException("p26rc7airdrop all");
        busy=true;sessionId=UUID.randomUUID();io=Executors.newSingleThreadExecutor();
        CompletableFuture<Void> work;
        try{setup();capacity();work=firstDrop().thenCompose(unused->blockedLocation()).thenCompose(unused->stopAndRecoverWarning()).thenCompose(unused->beaconRecovery());}
        catch(Throwable error){work=CompletableFuture.failedFuture(error);}
        work.whenComplete((unused,error)->main(()->{
            var stopped=drops==null?CompletableFuture.<Void>completedFuture(null):drops.stop();
            stopped.whenComplete((ignored,stopError)->main(()->{
                Throwable failure=error==null?stopError:error;
                try{
                    if(helper!=null)helper.close();
                    io.shutdown();
                    if(world!=null){require(tickets()==0,"No residual airdrop chunk tickets");if(sanitizer!=null)sanitizer.remove(world.getUID());require(plugin.getServer().unloadWorld(world,true),"Unload isolated fixture");}
                    report.set("status",failure==null?"passed":"failed");
                    report.set("limits",List.of("Dedicated local flat fixture only; no production server or map modified.","Real Paper tier-one BEACON, yellow glass, scoped event guards, chunk tickets and persistent block ownership.","Session time is advanced explicitly; no 60-second wall-clock wait, player chat receipt or client beam rendering is claimed; native tier-one activation and clear-sky glass column are inspected on the server."));
                    Path file=plugin.getDataFolder().toPath().resolve("rc7/airdrop-report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
                    if(failure!=null)throw new CompletionException(failure);
                    sender.sendMessage("RC7 AIRDROP SUCCESS warning=60s nativeBeam=OK buffs=speed-I navigation=OK guarantees=OK cancellation=OK recovery=OK path="+file.toAbsolutePath());
                }catch(Throwable failed){sender.sendMessage("RC7 AIRDROP FAILED "+failed);plugin.getLogger().log(Level.SEVERE,"RC7 AIRDROP FAILED",failed);}
                finally{busy=false;}
            }));
        }));
    }
    private void setup(){
        NamespacedKey key=new NamespacedKey("battleroyale_probe","rc7_airdrop_"+UUID.randomUUID().toString().replace("-",""));
        world=Objects.requireNonNull(plugin.getServer().createWorld(WorldCreator.ofKey(key).type(WorldType.FLAT).generateStructures(false)));
        world.setDifficulty(Difficulty.PEACEFUL);world.setGameRule(GameRules.SPAWN_MOBS,false);
        for(int x=48;x<=96;x++)for(int z=48;z<=96;z++)world.getBlockAt(x,80,z).setType(Material.STONE,false);
        sanitizer=new WorldSanitizer(new NamespacedKey(plugin,"ground_loot"));sanitizer.register(world,sessionId,error->{throw new CompletionException(error);});
        var room=new RoomDefinition("rc7","RC7空投测试",1,2,1,Duration.ZERO,List.of("fixture"),"starter","probe",true,Duration.ofSeconds(1));
        var map=new MapTemplate("fixture","RC7 Flat Fixture",world.getWorldPath(),new PlayableArea(-1000,1000,-1000,1000));
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
            var plan=drops.nativeBeacon(0).orElseThrow();
            require(plan.cells().stream().allMatch(cell->world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString().equals(cell.placed())),"All eleven vanilla beacon blocks are present");
            return until(()->((org.bukkit.block.Beacon)world.getBlockAt(plan.x(),plan.y(),plan.z()).getState()).getTier()==1,240,"Vanilla beacon tier computed by real server ticks");
        }).thenCompose(unused->{
            var nativePlan=drops.nativeBeacon(0).orElseThrow();
            var nativeBeacon=(org.bukkit.block.Beacon)world.getBlockAt(nativePlan.x(),nativePlan.y(),nativePlan.z()).getState();
            require(nativeBeacon.getTier()==1&&nativeBeacon.getPrimaryEffect()!=null&&nativeBeacon.getPrimaryEffect().getType().equals(PotionEffectType.SPEED)&&nativeBeacon.getPrimaryEffect().getAmplifier()==0&&nativeBeacon.getSecondaryEffect()==null&&nativeBeacon.getEffectRange()==24,"A real tier-one beacon provides only level-I speed in the configured range");
            require(world.getHighestBlockYAt(nativePlan.x(),nativePlan.z(),HeightMap.WORLD_SURFACE)==nativePlan.y()+1,"Only the yellow glass cap is above the live beacon, with clear sky");
            report.set("native-beacon.tier",nativeBeacon.getTier());report.set("native-beacon.color-block","YELLOW_STAINED_GLASS");report.set("native-beacon.single-speed-effect",true);report.set("native-beacon.effect-radius",24);report.set("native-beacon.effect-duration-ticks",100);
            var at=drops.announcement().orElseThrow();
            require(drops.navigationTarget(0,0).orElseThrow().equals(new ZoneNavigation.Point(at.x()+.5,at.z()+.5)),"Announcement exposes its fixed crate location for navigation");
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
            require(amount(crate.getInventory(),Material.TOTEM_OF_UNDYING)==0&&amount(crate.getInventory(),Material.BREAD)==6,"Totems are no longer guaranteed; random supplies still land");
            require(Arrays.stream(crate.getInventory().getContents()).filter(Objects::nonNull).anyMatch(Rc7AirdropProbe::guaranteedEquipment),"Round-one crate contains enchanted iron equipment or an enchanted bow");
            require(tickets()==1&&visuals()==0&&drops.announcement().isEmpty(),"Landing retains only the native beacon ticket while supplies remain");
            require(drops.navigationTarget(at.x(),at.z()).orElseThrow().equals(new ZoneNavigation.Point(at.x()+.5,at.z()+.5)),"Landed nonempty crate remains a navigation target");
            crate.getInventory().clear();tick(69_000_000_000L);
            require(drops.nativeBeacon(0).isEmpty()&&drops.navigationTarget(0,0).isEmpty()&&tickets()==0,"Taking all contents removes the real beam and navigation target");
            for(var cell:nativePlan.cells())require(world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString().equals(cell.original()),"Empty-crate cleanup restores original block data");
            report.set("native-beacon.empty-crate-removes-signal",true);
            report.set("warning.seconds",60);report.set("warning.location",List.of(at.x(),at.y(),at.z()));report.set("warning.same-xyz-on-landing",true);report.set("guarantees.landed-totem",0);report.set("guarantees.landed-equipment",true);
            return drops.stop();
        }).thenCompose(unused->{
            drops=new PaperAirdrops(plugin,session,sanitizer,content,io,true);
            return until(()->{tick(69_000_000_000L);return drops.diagnostics().contains("beaconRecovery=true");},240,"Physical beacon recovery completes before checking round deduplication").thenRun(()->{
                for(int i=0;i<20;i++)tick(69_000_000_000L);
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
    private CompletableFuture<Void> beaconRecovery(){
        return drops.stop().thenCompose(unused->{
            helper=new PaperAirdropBeacons(plugin,world,sessionId,io);
            return until(helper::recover,240,"Standalone beacon recovery initialization");
        }).thenCompose(unused->{
            var plan=helper.plan(100,88,81,88).orElseThrow();
            return helper.persist(plan).thenCompose(saved->{
                var result=new CompletableFuture<Void>();main(()->{try{
                    require(helper.install(saved),"Persisted physical beacon installs");
                    testProtection(saved);
                    helper.close();require(tickets()==0,"Explicit stop releases the physical beacon ticket");
                    for(var cell:saved.cells())require(world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString().equals(cell.original()),"Stop restores the exact saved block state");
                    // A crash fixture restores the real persisted intention + chunk ownership, without
                    // an active controller; this tests recovery rather than calling ordinary remove().
                    for(var cell:saved.cells())world.getBlockAt(cell.x(),cell.y(),cell.z()).setBlockData(Bukkit.createBlockData(cell.placed()),false);
                    var chunk=world.getChunkAt(saved.x()>>4,saved.z()>>4);
                    var owner=new NamespacedKey(plugin,"airdrop_beacon_"+saved.stage());chunk.getPersistentDataContainer().set(owner,PersistentDataType.STRING,saved.identity());
                    var edited=saved.cells().getFirst();world.getBlockAt(edited.x(),edited.y(),edited.z()).setType(Material.DIAMOND_BLOCK,false);
                    world.save();
                    helper=new PaperAirdropBeacons(plugin,world,sessionId,io);
                    until(helper::recover,240,"Recover saved physical beam after interrupted cleanup").whenComplete((v,error)->{
                        if(error!=null){result.completeExceptionally(error);return;}
                        try{
                            for(var cell:saved.cells())require(world.getBlockAt(cell.x(),cell.y(),cell.z()).getBlockData().getAsString().equals(cell==edited?"minecraft:diamond_block":cell.original()),"Recovery restores only unchanged owned cells, preserving the external edit");
                            require(!chunk.getPersistentDataContainer().has(owner),"Recovery removes chunk ownership with restored block data");
                            helper.close();
                            var later=saved.cells().get(1);world.getBlockAt(later.x(),later.y(),later.z()).setType(Material.IRON_BLOCK,false);
                            helper=new PaperAirdropBeacons(plugin,world,sessionId,io);
                            until(helper::recover,240,"Read stale intentions after ownership was cleared").whenComplete((ignored,retryError)->{
                                if(retryError!=null){result.completeExceptionally(retryError);return;}
                                try{
                                    require(world.getBlockAt(later.x(),later.y(),later.z()).getType()==Material.IRON_BLOCK,"A stale ledger cannot overwrite a later player block without chunk ownership");
                                    require(helper.size()==0&&tickets()==0,"Recovery never reinstalls or pins an old beacon");
                                    report.set("native-beacon.scoped-protection",true);report.set("native-beacon.crash-recovery-preserves-external-edit",true);report.set("native-beacon.stale-ledger-cannot-overwrite-new-block",true);
                                    result.complete(null);
                                }catch(Throwable failure){result.completeExceptionally(failure);}
                            });
                        }catch(Throwable failure){result.completeExceptionally(failure);}
                    });
                }catch(Throwable failure){result.completeExceptionally(failure);}});return result;
            });
        });
    }
    private void testProtection(AirdropBeaconLedger.Plan plan){
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            if(method.getName().equals("getUniqueId"))return UUID.fromString("11111111-1111-1111-1111-111111111117");
            if(method.getName().equals("getWorld"))return world;
            if(method.getName().equals("toString"))return "Rc7EventRecorder";
            if(method.getReturnType()==boolean.class)return false;if(method.getReturnType()==int.class)return 0;return null;
        });
        var base=plan.cells().getFirst();Block block=world.getBlockAt(base.x(),base.y(),base.z());
        var breakEvent=new BlockBreakEvent(block,player);helper.breakBlock(breakEvent);require(breakEvent.isCancelled()&&!breakEvent.isDropItems(),"Owned iron cannot be mined for item drops");
        Block beacon=world.getBlockAt(plan.x(),plan.y(),plan.z());
        var effect=new BeaconEffectEvent(beacon,new PotionEffect(PotionEffectType.SPEED,100,0),player,true);helper.effect(effect);require(effect.isCancelled(),"Owned beacon effect event is cancelled defensively");
        var interact=new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,new ItemStack(Material.NETHER_STAR),beacon,BlockFace.UP,EquipmentSlot.HAND);helper.interact(interact);require(interact.isCancelled(),"Owned beacon UI cannot select a buff");
        var outsider=new BlockBreakEvent(world.getBlockAt(plan.x()+8,plan.y()-1,plan.z()),player);helper.breakBlock(outsider);require(!outsider.isCancelled(),"Unrelated map blocks remain breakable");
        Block beamTarget=world.getBlockAt(plan.x(),plan.y()+4,plan.z());
        Block outsideTarget=world.getBlockAt(plan.x()+8,plan.y()+4,plan.z());
        for(Material bucket:List.of(Material.POWDER_SNOW_BUCKET,Material.WATER_BUCKET,Material.LAVA_BUCKET)){
            var blocked=new PlayerBucketEmptyEvent(player,beamTarget,beamTarget.getRelative(BlockFace.WEST),BlockFace.EAST,bucket,new ItemStack(Material.BUCKET),EquipmentSlot.OFF_HAND);
            helper.bucketEmpty(blocked);require(blocked.isCancelled(),"Bucket cannot occupy native beam column: "+bucket);
            var allowed=new PlayerBucketEmptyEvent(player,outsideTarget,outsideTarget.getRelative(BlockFace.WEST),BlockFace.EAST,bucket,new ItemStack(Material.BUCKET),EquipmentSlot.HAND);
            helper.bucketEmpty(allowed);require(!allowed.isCancelled(),"Bucket remains usable on unrelated terrain: "+bucket);
        }
        report.set("native-beacon.bucket-placement-guard",true);
    }
    private void capacity(){
        var crowded=new LootTable("crowded",27,27,List.of(new LootTable.Entry("minecraft:stone_sword",1,1,1)));
        for(int seed=0;seed<20;seed++){
            var items=PaperAirdrops.contents(crowded,new Random(seed));require(items.size()==27,"Container cap remains 27 stacks");
            require(diamondGuaranteedEquipment(items.getFirst())&&items.getFirst().getAmount()==1,"Guaranteed enchanted equipment takes capacity before random rolls");
            require(items.stream().noneMatch(item->item.getType()==Material.TOTEM_OF_UNDYING),"Totems are ordinary table rolls, not a guaranteed slot");
            require(items.stream().noneMatch(item->item.getType()==Material.ELYTRA),"No elytra in supply crate");
        }
        report.set("guarantees.overflow-protected",true);report.set("guarantees.capacity",27);
    }
    /** The landed first-round crate guarantees an enchanted iron piece or bow; the capacity probe uses the tier-2 diamond pool. */
    private static boolean guaranteedEquipment(ItemStack item){return (item.getType().name().startsWith("IRON_")||item.getType()==Material.BOW)&&!item.getEnchantments().isEmpty();}
    private static boolean diamondGuaranteedEquipment(ItemStack item){return (item.getType().name().startsWith("DIAMOND_")||item.getType()==Material.BOW)&&!item.getEnchantments().isEmpty();}
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
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("RC7 airdrop assertion: "+message);}
}
