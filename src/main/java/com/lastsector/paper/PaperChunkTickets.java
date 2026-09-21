package com.lastsector.paper;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
/** Main-thread reference counting: Paper itself stores only one ticket per plugin/chunk. */
final class PaperChunkTickets {
    private record Key(UUID world,int x,int z) {}
    private static final Map<JavaPlugin,Map<Key,Integer>> owners=new WeakHashMap<>();
    private PaperChunkTickets() {}
    static void acquire(JavaPlugin plugin,World world,int x,int z) {
        var counts=owners.computeIfAbsent(plugin,p->new HashMap<>());var key=new Key(world.getUID(),x,z);
        if(!counts.containsKey(key))world.addPluginChunkTicket(x,z,plugin);
        counts.merge(key,1,Integer::sum);
    }
    static void release(JavaPlugin plugin,World world,int x,int z) {
        var counts=owners.get(plugin);if(counts==null)return;var key=new Key(world.getUID(),x,z);Integer count=counts.get(key);if(count==null)return;
        if(count>1)counts.put(key,count-1);else{counts.remove(key);world.removePluginChunkTicket(x,z,plugin);}
        if(counts.isEmpty())owners.remove(plugin);
    }
}
