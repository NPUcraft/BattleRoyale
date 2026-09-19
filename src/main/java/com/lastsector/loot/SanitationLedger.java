package com.lastsector.loot;
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
    public int chunks() { return blocks.size(); }
}
