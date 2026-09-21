package com.lastsector.combat;
import java.util.*;
/** Shared session provenance for PvP protection and combat; legacy name retained for source compatibility. */
public final class PvPHazardTracker {
    public record BlockKey(UUID world,int x,int y,int z) {}
    private static <K> Map<K,UUID> bounded() {
        return new LinkedHashMap<>() { @Override protected boolean removeEldestEntry(Map.Entry<K,UUID> entry) { return size()>65536; } };
    }
    private final Map<BlockKey,UUID> blocks=bounded();
    private final Map<UUID,UUID> entities=bounded();
    private final Map<UUID,UUID> burning=bounded();
    public void block(BlockKey key,UUID owner) { if(owner!=null) blocks.put(key,owner); else blocks.remove(key); }
    public UUID owner(BlockKey key) { return blocks.get(key); }
    public void spread(BlockKey from,BlockKey to) { block(to,owner(from)); }
    public void entity(UUID entity,UUID owner) { if(owner!=null) entities.put(entity,owner); }
    public UUID entityOwner(UUID entity) { return entities.get(entity); }
    public void burning(UUID player,UUID owner) { if(owner!=null) burning.put(player,owner); else burning.remove(player); }
    public UUID burningOwner(UUID player) { return burning.get(player); }
    public void clear() { blocks.clear(); entities.clear(); burning.clear(); }
    public int size() { return blocks.size()+entities.size()+burning.size(); }
}

