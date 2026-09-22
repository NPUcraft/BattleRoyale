package com.npucraft.lastsector.loot;
import java.util.*;
/** Chunk-local first inspection; failed work is not marked complete. */
public final class SanitationLedger {
    private final Set<Long> blocks=new HashSet<>(), entities=new HashSet<>(), inspecting=new HashSet<>();
    public void blocks(long key,Runnable work) { once(blocks,key,work); }
    public void entities(long key,Runnable work) { once(entities,key,work); }
    private void once(Set<Long> done,long key,Runnable work) {
        if(done.contains(key) || !inspecting.add(key)) return;
        try { work.run(); done.add(key); } finally { inspecting.remove(key); }
    }
    public Set<Long> blockKeys(){return Set.copyOf(blocks);}
    public Set<Long> entityKeys(){return Set.copyOf(entities);}
    public void restore(Set<Long> blockKeys,Set<Long> entityKeys){if(!blocks.isEmpty() || !entities.isEmpty())throw new IllegalStateException("Already sanitized");blocks.addAll(blockKeys);entities.addAll(entityKeys);}
    public int chunks() { return blocks.size(); }
}
