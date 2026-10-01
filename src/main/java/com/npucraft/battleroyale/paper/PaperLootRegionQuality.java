package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.loot.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread, session-owned first-load samples. Never loads a neighbour or requests a chunk. */
public final class PaperLootRegionQuality implements Listener,AutoCloseable {
    private static final int MAX_CACHED_CHUNKS=8192;
    private final UUID worldId;private final String session;private final LootRegionQualitySettings settings;
    private final boolean recovering;private final NamespacedKey sessionKey,sampleKey;
    private final Map<Long,LootRegionSample> cache=new LinkedHashMap<>(64,.75f,true);
    private boolean closed;private long sampledChunks,blockReads,persistedHits,cacheHits,recoveredFallbacks;
    public PaperLootRegionQuality(JavaPlugin plugin,World world,UUID session,LootRegionQualitySettings settings,boolean recovering){
        this.worldId=world.getUID();this.session=session.toString();this.settings=Objects.requireNonNull(settings);this.recovering=recovering;
        sessionKey=new NamespacedKey(plugin,"loot_quality_session");sampleKey=new NamespacedKey(plugin,"loot_quality_sample");
        if(settings.enabled()){
            plugin.getServer().getPluginManager().registerEvents(this,plugin);
            try{for(Chunk chunk:world.getLoadedChunks())sample(chunk);}catch(RuntimeException error){close();throw error;}
        }
    }
    public LootRegionQuality quality(Chunk chunk){return sample(chunk).quality();}
    public String table(Chunk chunk,String fallback){return settings.table(quality(chunk),fallback);}
    public LootTable resolve(Chunk chunk,LootTable fallback,Map<String,LootTable> tables){return settings.resolve(quality(chunk),fallback,tables);}
    public LootRegionSample sample(Chunk chunk){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Region samples require the server thread");
        if(closed||!worldId.equals(chunk.getWorld().getUID()))throw new IllegalStateException("Region sample outside its live match world");
        if(!settings.enabled())return LootRegionSample.RECOVERED_UNKNOWN;
        var value=cache.get(chunk.getChunkKey());if(value!=null){cacheHits++;return value;}
        if(!chunk.isLoaded())throw new IllegalStateException("Region quality does not load chunks");
        var pdc=chunk.getPersistentDataContainer();
        if(session.equals(pdc.get(sessionKey,PersistentDataType.STRING))){value=LootRegionSample.decode(pdc.get(sampleKey,PersistentDataType.INTEGER_ARRAY));persistedHits++;}
        else{
            if(recovering){value=LootRegionSample.RECOVERED_UNKNOWN;recoveredFallbacks++;}
            else value=inspect(chunk);
            // Persist result before the session identifier; an incomplete write cannot masquerade as valid data.
            pdc.set(sampleKey,PersistentDataType.INTEGER_ARRAY,value.encode());pdc.set(sessionKey,PersistentDataType.STRING,session);
        }
        cache.put(chunk.getChunkKey(),value);if(cache.size()>MAX_CACHED_CHUNKS)cache.remove(cache.keySet().iterator().next());return value;
    }
    private LootRegionSample inspect(Chunk chunk){
        World world=chunk.getWorld();int artificial=0;
        for(int sample=0;sample<LootRegionQualityPolicy.COLUMNS;sample++){
            int x=LootRegionQualityPolicy.x(sample),z=LootRegionQualityPolicy.z(sample);
            int top=world.getHighestBlockYAt((chunk.getX()<<4)+x,(chunk.getZ()<<4)+z,HeightMap.WORLD_SURFACE);
            boolean built=false;
            for(int depth=0;depth<LootRegionQualityPolicy.DEPTH&&top-depth>=world.getMinHeight();depth++){
                blockReads++;if(LootRegionQualityPolicy.built(chunk.getBlock(x,top-depth,z).getType().name()))built=true;
            }
            if(built)artificial++;
        }
        sampledChunks++;return new LootRegionSample(LootRegionQualityPolicy.classify(artificial,LootRegionQualityPolicy.COLUMNS,settings.builtThreshold()),LootRegionQualityPolicy.COLUMNS,artificial);
    }
    @EventHandler(priority=EventPriority.LOWEST)public void loaded(ChunkLoadEvent event){
        if(!closed&&settings.enabled()&&worldId.equals(event.getWorld().getUID()))sample(event.getChunk());
    }
    @EventHandler public void unloaded(ChunkUnloadEvent event){if(worldId.equals(event.getWorld().getUID()))cache.remove(event.getChunk().getChunkKey());}
    public long sampledChunks(){return sampledChunks;}public long blockReads(){return blockReads;}public int cachedChunks(){return cache.size();}
    public String diagnostics(){return "region-quality="+(settings.enabled()?"enabled":"disabled")+" sampled="+sampledChunks+" block-reads="+blockReads+" cache-hits="+cacheHits+" saved-hits="+persistedHits+" recovered-fallbacks="+recoveredFallbacks;}
    @Override public void close(){if(closed)return;closed=true;HandlerList.unregisterAll(this);cache.clear();}
}
