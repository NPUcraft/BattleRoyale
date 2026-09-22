package com.lastsector.recovery;
import com.lastsector.loadout.StoredItem;
import com.lastsector.offline.BodySnapshot;
import java.util.*;
/** Bukkit-free durable form of M4 MatchPlayerSnapshot. */
public record LobbySnapshot(Map<Integer,StoredItem> inventory,Map<Integer,StoredItem> enderChest,StoredItem cursor,int selected,int level,float progress,int totalExperience,String mode,double health,int food,float saturation,float exhaustion,List<BodySnapshot.Effect> effects,int fireTicks,float fallDistance,double absorption,boolean allowFlight,boolean flying) {
    public LobbySnapshot{inventory=Map.copyOf(inventory);enderChest=Map.copyOf(enderChest);effects=List.copyOf(effects);if(inventory.keySet().stream().anyMatch(i->i<0||i>=41)||enderChest.keySet().stream().anyMatch(i->i<0||i>=27)||!Double.isFinite(health)||health<=0||!Float.isFinite(progress)||progress<0||progress>1||level<0||totalExperience<0||food<0||food>20)throw new IllegalArgumentException("Invalid Lobby inventory/vitals");if(selected<0 || selected>8 || !Set.of("SURVIVAL","CREATIVE","ADVENTURE","SPECTATOR").contains(mode))throw new IllegalArgumentException("Invalid Lobby state");}
}
