package com.lastsector.paper;
import com.lastsector.session.GameSession;
import com.lastsector.spawn.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
/** Conservative surface policy; only completed candidate chunks are inspected or pinned. */
public final class PaperSpawnTerrain implements SpawnTerrain {
    private final JavaPlugin plugin;
    private final GameSession session;
    private boolean released;
    private final Set<Long> acceptedChunks=new HashSet<>(),tickets=new HashSet<>();
    private final WorldSanitizer sanitizer;
    private final java.util.function.Supplier<CompletableFuture<?>> beforeLanding;
    private final Runnable applyLoadout;
    private final java.util.function.BiConsumer<UUID,Location> offlineLanding;
    public PaperSpawnTerrain(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,
            java.util.function.Supplier<CompletableFuture<?>> beforeLanding,Runnable applyLoadout) {
        this(plugin,session,sanitizer,beforeLanding,applyLoadout,null);
    }
    public PaperSpawnTerrain(JavaPlugin plugin,GameSession session,WorldSanitizer sanitizer,
            java.util.function.Supplier<CompletableFuture<?>> beforeLanding,Runnable applyLoadout,java.util.function.BiConsumer<UUID,Location> offlineLanding) {
        this.plugin=plugin; this.session=session; this.sanitizer=sanitizer; this.beforeLanding=beforeLanding; this.applyLoadout=applyLoadout;
        this.offlineLanding=offlineLanding;
    }
    @Override public CompletableFuture<?> beforeLanding() { return beforeLanding.get(); }
    private World world() { return Objects.requireNonNull(plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName()),"Runtime world missing"); }
    private static long key(int x,int z) { return ((long)x<<32) ^ (z&0xffffffffL); }
    @Override public CompletableFuture<?> prepare(SpawnPlanner.Column column) {
        World world=world();int x=column.x()>>4,z=column.z()>>4;
        // Paper completes this future on the server thread. Pin before publishing completion,
        // otherwise an unload can happen before SpawnPreparation inspects it on the next tick.
        return world.getChunkAtAsync(x,z,true).thenApply(chunk->{
            if(released || !plugin.isEnabled())throw new java.util.concurrent.CancellationException("Spawn terrain released");
            if(tickets.add(key(x,z)))PaperChunkTickets.acquire(plugin,world,x,z);
            return chunk;
        });
    }
    @Override public Double safeFeet(SpawnPlanner.Column column) {
        World world=world(); int cx=column.x()>>4,cz=column.z()>>4;
        if(!world.isChunkLoaded(cx,cz)) throw new IllegalStateException("Prepared chunk unloaded");
        if(tickets.add(key(cx,cz))) PaperChunkTickets.acquire(plugin,world,cx,cz);
        sanitizer.ensure(world.getChunkAt(cx,cz));
        int y=world.getHighestBlockYAt(column.x(),column.z(),HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if(y<world.getMinHeight() || y+2>=world.getMaxHeight()) return null;
        return SafeSpawnPolicy.safe(cell(world.getBlockAt(column.x(),y,column.z())),
                cell(world.getBlockAt(column.x(),y+1,column.z())),cell(world.getBlockAt(column.x(),y+2,column.z()))) ? (double)y+1 : null;
    }
    private static SafeSpawnPolicy.Cell cell(Block block) {
        Material type=block.getType();
        var box=block.getBoundingBox();
        boolean full=type.isOccluding() && box.getWidthX()==1 && box.getWidthZ()==1 && box.getHeight()==1;
        return new SafeSpawnPolicy.Cell(full,block.isPassable(),block.isLiquid(),hazard(type),Tag.LEAVES.isTagged(type));
    }
    static boolean safeItemGround(Block floor,Block space) {
        var support=cell(floor); var air=cell(space);
        return support.fullSupport() && !support.liquid() && !support.hazardous() && !support.leaves()
                && air.passable() && !air.liquid() && !air.hazardous();
    }
    public static boolean hazard(Material type) {
        return switch(type) {
            case WATER,LAVA,FIRE,SOUL_FIRE,POWDER_SNOW,MAGMA_BLOCK,CACTUS,SWEET_BERRY_BUSH,
                    WITHER_ROSE,CAMPFIRE,SOUL_CAMPFIRE,POINTED_DRIPSTONE,COBWEB -> true;
            default -> false;
        };
    }
    @Override public void resolved(SpawnPlanner.Column column,boolean accepted) {
        int cx=column.x()>>4,cz=column.z()>>4; long key=key(cx,cz);
        if(accepted) acceptedChunks.add(key);
        else if(!acceptedChunks.contains(key) && tickets.remove(key)) PaperChunkTickets.release(plugin,world(),cx,cz);
    }
    @Override public void teleport(List<UUID> starters,List<SpawnPlanner.Position> plan,BooleanSupplier current) {
        World world=world();
        // A changing starter roster aborts this attempt instead of using an incorrect initial bucket.
        for(UUID id:starters) {
            if(offlineLanding!=null && session.players().get(id).state()==com.lastsector.player.PlayerState.DISCONNECTED)continue;
            var player=plugin.getServer().getPlayer(id);
            if(player==null || !player.isOnline() || session.players().get(id).state()==com.lastsector.player.PlayerState.DISCONNECTED)
                throw new IllegalStateException("Starter disconnected before landing");
        }
        applyLoadout.run();
        for(int i=0;i<starters.size();i++) {
            if(!current.getAsBoolean()) return;
            var position=plan.get(i);
            if(offlineLanding!=null && session.players().get(starters.get(i)).state()==com.lastsector.player.PlayerState.DISCONNECTED){offlineLanding.accept(starters.get(i),new Location(world,position.x(),position.y(),position.z()));continue;}
            var player=Objects.requireNonNull(plugin.getServer().getPlayer(starters.get(i)));
            if(!player.teleport(new Location(world,position.x(),position.y(),position.z())))
                throw new IllegalStateException("Safe spawn teleport rejected");
        }
    }
    @Override public void release() {
        released=true;
        World world=plugin.getServer().getWorld(session.gameWorld().orElseThrow().worldName());
        if(world!=null) for(long key:tickets) PaperChunkTickets.release(plugin,world,(int)(key>>32),(int)key);
        tickets.clear(); acceptedChunks.clear();
    }
}

