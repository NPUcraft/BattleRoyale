package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.session.*;
import com.npucraft.battleroyale.zone.ZoneRuntime;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** A few session-owned mounts, using one bounded async chunk request at a time. */
public final class PaperMatchHorses implements AutoCloseable {
    public static final String SESSION_KEY="match_horse_session",LEDGER_KEY="match_horse_spawns";
    private final JavaPlugin plugin;private final GameSession session;private final WorldSanitizer sanitizer;
    private final HorseSettings settings;private final UUID worldId;private final NamespacedKey marker,ledger;
    private final HorseSpawnPolicy policy;private final Random random;
    private CompletableFuture<Chunk> pending;private Chunk ticket;
    private int attempts,x,z;private long nextAttempt=Long.MIN_VALUE;private boolean batch,closed,failed;
    public PaperMatchHorses(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,HorseSettings settings,boolean restoring){
        this.plugin=plugin;this.session=session;this.sanitizer=sanitizer;this.settings=settings;
        var world=Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()),"Match world missing");worldId=world.getUID();
        if(!sanitizer.active(worldId))throw new IllegalStateException("Horses require a registered match world");
        marker=new NamespacedKey(plugin,SESSION_KEY);ledger=new NamespacedKey(plugin,LEDGER_KEY);
        String identity=session.sessionId().toString();var data=world.getPersistentDataContainer();HorseSpawnPolicy restored;
        try{
            if(identity.equals(data.get(marker,PersistentDataType.STRING)))restored=HorseSpawnPolicy.fromEncoded(settings,data.get(ledger,PersistentDataType.LONG_ARRAY));
            else if(restoring){failed=true;restored=new HorseSpawnPolicy(settings,new double[0]);}
            else{restored=new HorseSpawnPolicy(settings,new double[0]);data.set(ledger,PersistentDataType.LONG_ARRAY,restored.encodedSnapshot());data.set(marker,PersistentDataType.STRING,identity);}
        }catch(RuntimeException invalid){failed=true;restored=new HorseSpawnPolicy(settings,new double[0]);plugin.getLogger().warning("马匹生成账本不可用，已停止本局新增马匹："+session.room().id());}
        policy=restored;random=new Random(session.sessionId().getMostSignificantBits()^session.sessionId().getLeastSignificantBits()^policy.count());
    }
    public void tick(ZoneRuntime zone,long now){
        if(closed||failed||!settings.enabled()||session.state()!=GameState.RUNNING)return;
        try{
            var world=world();if(!sanitizer.active(worldId)){stop();return;}
            if(pending!=null){
                if(!pending.isDone())return;Chunk chunk=pending.join();pending=null;sanitizer.ensure(chunk);
                int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
                if(zone.current().contains(x-1,z-1)&&zone.current().contains(x+2,z+2)&&policy.separated(x+.5,z+.5)&&safe(world,x,y,z)){
                    if(!policy.claim(x+.5,z+.5))throw new IllegalStateException("Horse claim rejected");
                    // Persist the claim before introducing its entity. Failed/cancelled spawns consume the slot.
                    world.getPersistentDataContainer().set(ledger,PersistentDataType.LONG_ARRAY,policy.encodedSnapshot());
                    world.spawn(new Location(world,x+.5,y,z+.5),Horse.class,SpawnReason.CUSTOM,true,horse->{
                        horse.getPersistentDataContainer().set(marker,PersistentDataType.STRING,session.sessionId().toString());
                        horse.setAdult();horse.setAgeLock(true);horse.setTamed(true);horse.setPersistent(true);horse.setRemoveWhenFarAway(false);
                        horse.setColor(Horse.Color.values()[random.nextInt(Horse.Color.values().length)]);
                        horse.setStyle(Horse.Style.values()[random.nextInt(Horse.Style.values().length)]);
                        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                    });
                    finishBatch(now);return;
                }
                release();
            }
            if(!policy.available())return;
            if(!batch){if(nextAttempt!=Long.MIN_VALUE&&now<nextAttempt)return;batch=true;attempts=0;}
            if(attempts++>=settings.maxAttempts()){finishBatch(now);return;}
            var current=zone.current();double radius=current.halfSize()-3;if(radius<=0){finishBatch(now);return;}
            int minX=(int)Math.ceil(current.centerX()-radius-.5),maxX=(int)Math.floor(current.centerX()+radius-.5);
            int minZ=(int)Math.ceil(current.centerZ()-radius-.5),maxZ=(int)Math.floor(current.centerZ()+radius-.5);
            if(minX>maxX||minZ>maxZ){finishBatch(now);return;}
            x=random.nextInt(minX,maxX+1);z=random.nextInt(minZ,maxZ+1);
            if(!HorseSpawnPolicy.insideChunk(x,z)||!policy.separated(x+.5,z+.5))return;
            pending=world.getChunkAtAsync(x>>4,z>>4,true).thenApply(chunk->{
                if(closed||failed||session.state()!=GameState.RUNNING||!plugin.isEnabled())throw new CancellationException("Horse spawning stopped");
                PaperChunkTickets.acquire(plugin,chunk.getWorld(),chunk.getX(),chunk.getZ());ticket=chunk;return chunk;
            });
        }catch(RuntimeException error){failed=true;release();plugin.getLogger().log(java.util.logging.Level.WARNING,"本局新增马匹已停止，比赛可继续："+session.room().id(),error);}
    }
    /** A horse needs more room than a player: flat 3x3 support and three clear air layers. */
    public static boolean safe(World world,int x,int y,int z){
        if(!HorseSpawnPolicy.insideChunk(x,z)||!world.isChunkLoaded(x>>4,z>>4)||y<=world.getMinHeight()||y+3>=world.getMaxHeight())return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(!PaperSpawnTerrain.safeItemGround(world.getBlockAt(x+dx,y-1,z+dz),world.getBlockAt(x+dx,y,z+dz)))return false;
            for(int dy=0;dy<3;dy++)if(!world.getBlockAt(x+dx,y+dy,z+dz).getType().isAir())return false;
        }
        return world.getNearbyEntities(new Location(world,x+.5,y+1,z+.5),1.5,1.5,1.5,entity->entity instanceof LivingEntity).isEmpty();
    }
    private World world(){return Objects.requireNonNull(plugin.getServer().getWorld(worldId),"Match world unloaded");}
    private void finishBatch(long now){release();batch=false;nextAttempt=now+settings.intervalSeconds()*1_000_000_000L;}
    private void release(){if(ticket!=null){PaperChunkTickets.release(plugin,ticket.getWorld(),ticket.getX(),ticket.getZ());ticket=null;}}
    public CompletableFuture<Void> stop(){closed=true;release();return (pending==null?CompletableFuture.<Void>completedFuture(null):pending.handle((unused,error)->(Void)null));}
    public String diagnostics(){return "horses="+(closed?"CLOSED":failed?"RECOVERY_OR_ERROR":settings.enabled()?"ACTIVE":"DISABLED")+" issued="+policy.count()+"/"+settings.maxPerSession()+" pending="+(pending!=null);}
    @Override public void close(){stop();}
}
