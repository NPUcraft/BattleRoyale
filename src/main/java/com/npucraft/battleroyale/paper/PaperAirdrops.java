package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Session-owned drops: claim, pin a safe spaced location, announce for the configured countdown, descend, then fill once. */
public final class PaperAirdrops implements AutoCloseable {
    public record Announcement(int stage,int x,int y,int z,long startedNanos,int seconds){
        public Announcement(int stage,int x,int y,int z,long startedNanos){this(stage,x,y,z,startedNanos,AirdropSettings.ANNOUNCEMENT_SECONDS);}
        public boolean ready(long now){return now-startedNanos>=seconds*1_000_000_000L;}
        public long remainingSeconds(long now){return Math.max(0,(long)Math.ceil(seconds-(now-startedNanos)/1e9));}
        public String coordinates(){return "X="+x+" Y="+y+" Z="+z;}
    }
    private final JavaPlugin plugin;
    private final GameSession session;
    private final WorldSanitizer sanitizer;
    private final AirdropSettings settings;
    private final LootTable table;
    private final Executor io;
    private final UUID worldId;
    private final Path worldPath;
    private final NamespacedKey marker;
    private final AirdropRounds rounds;
    private final List<Beacon> beacons=new ArrayList<>();
    private final List<AirdropPlacement.Point> usedSites=new ArrayList<>();
    private final PaperAirdropBeacons signals;
    private final List<LootTable> roundTables;
    private CompletableFuture<com.npucraft.battleroyale.loot.AirdropBeaconLedger.Plan> pendingSignal;
    private CompletableFuture<Boolean> claim;
    private CompletableFuture<Chunk> pending;
    private BlockDisplay falling;
    private Chunk ticket;
    private Announcement announcement;
    private Random random;
    private int stage=-1,attempts,x,y,z,landed;
    private long fallStarted,lastMarkerSecond=Long.MIN_VALUE;
    private double startY;
    private Zone destination;
    private boolean claimed,closed,failed;
    private static final double SPACING_FACTOR=.5;
    private static final int SPACING_ATTEMPTS=24;
    /** Signal-gun drops bypass the round pipeline with this pseudo-stage; land() maps it back to the current round table. */
    private static final int SIGNAL_STAGE=1_000;
    /** Signal-gun drop: skip the round pipeline and descend straight onto the requester's position,
     *  using the loot of the current zone stage. Consumes no scheduled round and shares the single
     *  descent slot, so it is refused while another drop is announced, falling, or being placed. */
    public boolean summonSignal(org.bukkit.entity.Player requester,ZoneRuntime zone,long now){
        if(closed||failed||table==null||!settings.enabled())return false;
        if(stage>=0||announcement!=null||falling!=null||pending!=null||claim!=null||pendingSignal!=null)return false;
        var loc=requester.getLocation();
        if(!zone.current().contains(loc.getX(),loc.getZ()))return false;
        destination=zone.current();x=loc.getBlockX();z=loc.getBlockZ();
        y=world().getHighestBlockYAt(x,z,org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        if(!valid(zone))return false;
        random=new Random();
        stage=SIGNAL_STAGE;
        usedSites.add(new AirdropPlacement.Point(x,z));
        startY=Math.min(world().getMaxHeight()-1,y+32);
        falling=world().spawn(new Location(world(),x+.5,startY,z+.5),BlockDisplay.class,display->{
            display.setBlock(Material.BARREL.createBlockData());display.setPersistent(false);display.setTeleportDuration(1);
            display.setGlowing(true);display.getPersistentDataContainer().set(marker,PersistentDataType.STRING,identity());
        });
        fallStarted=now;
        announce("信号枪空投正在降落！ %s，约 %s 秒后可拾取。","Signal-gun supply drop is descending! %s; ready in about %s seconds.",coordinates(),settings.fallSeconds());
        plugin.getLogger().info("AIRDROP_SIGNAL room="+session.room().id()+" x="+x+" y="+y+" z="+z);
        return true;
    }
    private record Beacon(Location location,long expires,String identity,int stage){}

    public PaperAirdrops(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,MatchContent content,Executor io,boolean recovered){
        this.plugin=plugin;this.session=session;this.sanitizer=sanitizer;this.io=io;settings=content.airdrops();
        var source=content.tables().get(settings.table());
        table=!settings.enabled()||source==null?null:new LootTable("airdrop",settings.minRolls(),settings.maxRolls(),source.entries());
        var roundTables=new ArrayList<LootTable>();
        if(table!=null)for(String id:settings.roundTables()){
            var roundSource=content.tables().get(id);
            if(roundSource==null)throw new IllegalArgumentException("Unknown airdrop round loot table: "+id);
            roundTables.add(new LootTable(id,settings.minRolls(),settings.maxRolls(),roundSource.entries()));
        }
        this.roundTables=List.copyOf(roundTables);
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()));worldId=world.getUID();worldPath=world.getWorldPath();
        signals=new PaperAirdropBeacons(plugin,world,session.sessionId(),io,player->session.state()==GameState.RUNNING&&Optional.ofNullable(session.players().get(player.getUniqueId())).map(value->value.state()==com.npucraft.battleroyale.player.PlayerState.ALIVE).orElse(false));
        marker=new NamespacedKey(plugin,"airdrop");var zone=session.zone().orElseThrow();
        rounds=recovered?new AirdropRounds(zone.stageIndex(),zone.phase(),settings.announcementSeconds()):new AirdropRounds(settings.announcementSeconds());
    }
    public void tick(ZoneRuntime zone,long now){
        if(closed||failed||session.state()!=GameState.RUNNING)return;
        try{
            if(!signals.recover())return;
            if(!settings.enabled()||table==null)return;
            if(now/1_000_000_000L!=lastMarkerSecond){
                lastMarkerSecond=now/1_000_000_000L;
                for(var beacon:List.copyOf(beacons))if(now>=beacon.expires()||!hasSupplies(beacon)){signals.remove(beacon.stage());beacons.remove(beacon);}
            }
            if(falling!=null){
                double progress=Math.clamp((double)(now-fallStarted)/(settings.fallSeconds()*1_000_000_000L),0,1);
                falling.teleport(new Location(world(),x+.5,startY+(y-startY)*progress,z+.5));
                if(progress>=1){falling.remove();falling=null;land(zone,now);}
                return;
            }
            if(announcement!=null){
                if(!announcement.ready(now))return;
                if(!valid(zone)){cancelRound("预告落点已被占用、变得不安全或离开安全区");return;}
                startY=Math.min(world().getMaxHeight()-1,y+32);
                falling=world().spawn(new Location(world(),x+.5,startY,z+.5),BlockDisplay.class,display->{
                    display.setBlock(Material.BARREL.createBlockData());display.setPersistent(false);display.setTeleportDuration(1);
                    display.setGlowing(true);display.getPersistentDataContainer().set(marker,PersistentDataType.STRING,identity());
                });
                fallStarted=now;
                announce("第 %s 轮补给空投正在降落！ %s，约 %s 秒后可拾取。","Supply drop %s is descending! %s; ready in about %s seconds.",stage+1,coordinates(),settings.fallSeconds());
                return;
            }
            if(pendingSignal!=null){
                if(!pendingSignal.isDone())return;
                var plan=pendingSignal.join();pendingSignal=null;
                if(valid(zone)&&signals.install(plan)){
                    // Both the fixed location and its real beacon exist before the public countdown.
                    announcement=new Announcement(stage,x,y,z,now,settings.announcementSeconds());
                    usedSites.add(new AirdropPlacement.Point(x,z));
                    announce("第 %s 轮空投预告：%s，%s 秒后开始降落。","Supply drop %s announced: %s. Descent begins in %s seconds.",announcement.stage()+1,announcement.coordinates(),settings.announcementSeconds());
                    plugin.getLogger().info("AIRDROP_ANNOUNCED room="+session.room().id()+" stage="+stage+" x="+x+" y="+y+" z="+z+" warning-seconds="+settings.announcementSeconds()+" native-beacon=true");
                    return;
                }
                release();
            }
            if(claim!=null){
                if(!claim.isDone())return;
                claimed=claim.join();claim=null;
                if(!claimed){stage=-1;return;}
            }
            if(pending!=null){
                if(!pending.isDone())return;
                Chunk chunk=pending.join();pending=null;
                sanitizer.ensure(chunk);
                int floor=world().getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);y=floor+1;
                if(valid(zone)){
                    var plan=signals.plan(stage,x,y,z);
                    if(plan.isPresent()){pendingSignal=signals.persist(plan.get());return;}
                }
                release();
            }
            if(stage<0){
                var due=rounds.poll(zone.stageIndex(),zone.phase(),zone.remainingSeconds());if(due.isEmpty())return;
                stage=due.getAsInt();attempts=0;claimed=false;
                random=new Random(session.sessionId().getMostSignificantBits()^session.sessionId().getLeastSignificantBits()^(stage*0x9e3779b97f4a7c15L));
                int currentStage=stage;
                claim=CompletableFuture.supplyAsync(()->{try{return AirdropLedger.claim(worldPath,session.sessionId(),currentStage);}catch(java.io.IOException error){throw new CompletionException(error);}},io);
                return;
            }
            if(claimed){
                if(attempts++>=settings.maxAttempts()){cancelRound("未找到安全落点");return;}
                destination=zone.next()==null?zone.current():zone.next();
                double radius=Math.max(.01,destination.halfSize()-Math.min(8,destination.halfSize()*.15));
                int minX=(int)Math.ceil(destination.centerX()-radius-.5),maxX=(int)Math.floor(destination.centerX()+radius-.5);
                int minZ=(int)Math.ceil(destination.centerZ()-radius-.5),maxZ=(int)Math.floor(destination.centerZ()+radius-.5);
                if(minX>maxX||minZ>maxZ){attempts=settings.maxAttempts();return;}
                // Spread rounds apart: keep a real separation from every earlier site while staying in-bounds.
                double spacing=Math.max(settings.minDistance(),radius*SPACING_FACTOR);
                var site=AirdropPlacement.choose(minX,maxX,minZ,maxZ,spacing,usedSites,random,SPACING_ATTEMPTS);
                x=site.x();z=site.z();
                pending=world().getChunkAtAsync(x>>4,z>>4,true).thenApply(chunk->{
                    if(closed||failed||session.state()!=GameState.RUNNING||!plugin.isEnabled())throw new CancellationException("Airdrop stopped");
                    ticket=chunk;PaperChunkTickets.acquire(plugin,chunk.getWorld(),chunk.getX(),chunk.getZ());return chunk;
                });
            }
        }catch(RuntimeException error){
            failed=true;announcement=null;beacons.clear();removeVisual();release();
            try{signals.close();}catch(RuntimeException cleanup){error.addSuppressed(cleanup);}
            plugin.getLogger().log(java.util.logging.Level.WARNING,"空投已停止，比赛可继续："+session.room().id(),error);
        }
    }
    public Optional<Announcement> announcement(){return Optional.ofNullable(announcement);}
    public static String announcementText(Announcement at){return "第 "+(at.stage()+1)+" 轮空投预告："+at.coordinates()+"，"+at.seconds()+" 秒后开始降落。";}
    private World world(){return Objects.requireNonNull(plugin.getServer().getWorld(worldId),"Airdrop world unloaded");}
    private String identity(){return session.sessionId()+":"+stage;}
    private String coordinates(){return "X="+x+" Y="+y+" Z="+z;}
    private boolean valid(ZoneRuntime zone){
        World world=world();return y>world.getMinHeight()&&y+2<world.getMaxHeight()
                &&destination.contains(x+.5,z+.5)&&zone.current().contains(x+.5,z+.5)
                &&world.getBlockAt(x,y,z).getType().isAir()&&world.getBlockAt(x,y+1,z).getType().isAir()
                &&PaperSpawnTerrain.safeItemGround(world.getBlockAt(x,y-1,z),world.getBlockAt(x,y,z));
    }
    /** Rounds beyond the per-round ladder fall back to the shared table. */
    private LootTable tableFor(int round){return round>=0&&round<roundTables.size()?roundTables.get(round):table;}
    /** Round 1 keeps pace with mid-tier ground, round 2 stretches above it, later rounds own the diamond pool. */
    private static int guaranteeTier(int round){return round<=0?0:round==1?1:2;}
    /** Guarantees occupy slots first; random overflow cannot evict them. */
    public static List<ItemStack> contents(LootTable table,RandomGenerator random){return contents(table,2,random);}
    public static List<ItemStack> contents(LootTable table,int guaranteeTier,RandomGenerator random){
        var items=new NativeLootItems();List<ItemStack> result=new ArrayList<>(27);
        var guaranteed=items.airdropGuarantees(random,guaranteeTier);
        if(guaranteed.isEmpty()||guaranteed.size()>27)throw new IllegalStateException("Invalid airdrop guarantee batch");
        for(var item:guaranteed)result.add(item.clone());
        for(var roll:table.roll(random)){
            ItemStack prototype=items.rollAirdrop(roll.item(),random);
            for(int amount:LootTable.split(roll.amount(),prototype.getMaxStackSize())){
                if(result.size()>=27)break;
                ItemStack stack=prototype.clone();stack.setAmount(amount);result.add(stack);
            }
        }
        return List.copyOf(result);
    }
    private void land(ZoneRuntime zone,long now){
        if(!valid(zone)){cancelRound("预告落点已被占用、变得不安全或离开安全区");return;}
        boolean signal=stage>=SIGNAL_STAGE;
        int round=signal?Math.min(roundTables.size()-1,Math.max(0,zone.stageIndex())):stage;
        List<ItemStack> contents=contents(tableFor(round),guaranteeTier(round),random);
        var block=world().getBlockAt(x,y,z);block.setType(Material.BARREL,false);
        var barrel=(Barrel)block.getState();
        barrel.customName(signal?I18n.shared("airdrop.signal","信号枪空投","Signal-gun supply drop").color(UiText.BRAND)
                :I18n.shared("airdrop.container","第 {0} 轮补给空投","Supply drop {0}",UiText.value(Integer.toString(stage+1))).color(UiText.BRAND));
        barrel.getPersistentDataContainer().set(marker,PersistentDataType.STRING,identity());barrel.update(true,false);
        List<Integer> slots=new ArrayList<>();for(int i=0;i<27;i++)slots.add(i);Collections.shuffle(slots,random);
        for(int i=0;i<contents.size();i++)barrel.getInventory().setItem(slots.get(i),contents.get(i));
        landed++;var at=new Location(world(),x+.5,y+1,z+.5);
        if(settings.markerSeconds()>0){
            if(beacons.size()>=8)signals.remove(beacons.removeFirst().stage());
            beacons.add(new Beacon(at,now+settings.markerSeconds()*1_000_000_000L,identity(),stage));
        }else signals.remove(stage);
        if(signal)announce("信号枪空投已落地！ %s，右键木桶领取。","Signal-gun supply drop landed! %s. Right-click the barrel to collect supplies.",coordinates());
        else announce("第 %s 轮补给空投已落地！ %s，右键木桶领取。","Supply drop %s landed! %s. Right-click the barrel to collect supplies.",stage+1,coordinates());
        plugin.getLogger().info("AIRDROP_LANDED room="+session.room().id()+" stage="+stage+" x="+x+" y="+y+" z="+z);
        release();announcement=null;stage=-1;claimed=false;
    }
    private void cancelRound(String reason){
        String english=switch(reason){case "未找到安全落点"->"No safe landing site was found";case "预告落点已被占用、变得不安全或离开安全区"->"The announced site is occupied, unsafe, or outside the safe zone";default->reason;};
        String label=stage>=SIGNAL_STAGE?"信号枪":"第 "+(stage+1)+" 轮";
        for(var player:world().getPlayers())player.sendMessage(UiText.message("["+LobbyText.defaultLabel(player,session.room().displayName())+"] "+I18n.text(player,"%s空投已取消：%s%s。","The %s supply drop was cancelled: %s%s.",label,I18n.text(player,reason,english),announcement==null?"":" ("+coordinates()+")")));
        plugin.getLogger().warning("AIRDROP_CANCELLED room="+session.room().id()+" stage="+stage+" reason="+reason);
        removeVisual();release();signals.remove(stage);announcement=null;stage=-1;claimed=false;
    }
    private void announce(String zh,String en,Object... args){for(var player:world().getPlayers())player.sendMessage(UiText.message("["+LobbyText.defaultLabel(player,session.room().displayName())+"] "+I18n.text(player,zh,en,args)));}
    private boolean hasSupplies(Beacon beacon){
        var at=beacon.location();
        return world().getBlockAt(at.getBlockX(),at.getBlockY()-1,at.getBlockZ()).getState() instanceof Barrel barrel
                &&beacon.identity().equals(barrel.getPersistentDataContainer().get(marker,PersistentDataType.STRING))&&!barrel.getInventory().isEmpty();
    }
    /** Nearest live announcement or unexpired, nonempty owned crate; no inventory slot is used. */
    public Optional<ZoneNavigation.Point> navigationTarget(double playerX,double playerZ){
        if(closed||failed)return Optional.empty();
        var points=new ArrayList<ZoneNavigation.Point>();
        if(announcement!=null)points.add(new ZoneNavigation.Point(announcement.x()+.5,announcement.z()+.5));
        for(var beacon:beacons)if(hasSupplies(beacon))points.add(new ZoneNavigation.Point(beacon.location().getX(),beacon.location().getZ()));
        return points.stream().min(Comparator.comparingDouble(point->Math.pow(point.x()-playerX,2)+Math.pow(point.z()-playerZ,2)));
    }
    /** Explicit inspection hook for the isolated real-Paper lifecycle probe. */
    public Optional<com.npucraft.battleroyale.loot.AirdropBeaconLedger.Plan> nativeBeacon(int round){return signals.active(round);}
    private void release(){if(ticket!=null){PaperChunkTickets.release(plugin,ticket.getWorld(),ticket.getX(),ticket.getZ());ticket=null;}}
    private void removeVisual(){if(falling!=null){falling.remove();falling=null;}}
    public CompletableFuture<Void> stop(){
        closed=true;removeVisual();release();announcement=null;beacons.clear();
        var cleanup=signals.stop();
        return CompletableFuture.allOf(cleanup,claim==null?CompletableFuture.completedFuture(null):claim,
                pending==null?CompletableFuture.completedFuture(null):pending,pendingSignal==null?CompletableFuture.completedFuture(null):pendingSignal).handle((v,error)->null);
    }
    public String diagnostics(){return "airdrops="+(closed?"CLOSED":failed?"FAILED":settings.enabled()?"ACTIVE":"DISABLED")+" landed="+landed+" markers="+beacons.size()+" nativeBeacons="+signals.size()+" beaconRecovery="+signals.ready()+" announced="+(announcement!=null)+" stage="+stage;}
    @Override public void close(){stop();}
}
