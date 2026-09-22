package com.lastsector.paper;
import com.lastsector.player.*;
import com.lastsector.loadout.*;
import com.lastsector.recovery.*;
import com.lastsector.storage.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
/** Original snapshots commit before mutations. A playerdata generation makes restore retries idempotent. */
public final class PaperDurablePlayers implements PlayerIsolation.Gateway<MatchPlayerSnapshot,LoadoutDefinition>,PlayerIsolation.Durability<MatchPlayerSnapshot> {
    private final JavaPlugin plugin;private final PaperPlayerIsolation delegate;private final SnapshotCodec codec=new SnapshotCodec();private final NamespacedKey restoredGeneration;
    private final Map<UUID,RecoveryRepository.Restore> rows=new HashMap<>();private final Map<UUID,MatchPlayerSnapshot> originals=new HashMap<>();private final Set<UUID> deleting=new HashSet<>(),applied=new HashSet<>(),invalid=new HashSet<>();
    private RecoveryStorage storage;private java.util.function.BooleanSupplier ready=()->false;
    public PaperDurablePlayers(JavaPlugin plugin,PaperPlayerIsolation delegate){this.plugin=plugin;this.delegate=delegate;restoredGeneration=new NamespacedKey(plugin,"restored_generation");}
    public void storage(RecoveryStorage storage,java.util.function.BooleanSupplier ready){this.storage=storage;this.ready=ready;}
    public MatchPlayerSnapshot capture(UUID id){return delegate.capture(id);}
    public void apply(UUID id,LoadoutDefinition loadout){delegate.apply(id,loadout);}
    public static LobbySnapshot pure(MatchPlayerSnapshot s){return new LobbySnapshot(s.inventory(),s.enderChest(),s.cursor(),s.selected(),s.level(),s.progress(),s.totalExperience(),s.mode().name(),s.health(),s.food(),s.saturation(),s.exhaustion(),s.effects().stream().map(e->new com.lastsector.offline.BodySnapshot.Effect(e.getType().getKey().toString(),e.getDuration(),e.getAmplifier(),e.isAmbient(),e.hasParticles(),e.hasIcon())).toList(),s.fireTicks(),s.fallDistance(),s.absorption(),s.allowFlight(),s.flying());}
    private MatchPlayerSnapshot paper(LobbySnapshot s){
        var nativeItems=new NativeItemSerializer();s.inventory().values().forEach(nativeItems::item);s.enderChest().values().forEach(nativeItems::item);if(s.cursor()!=null)nativeItems.item(s.cursor());
        var effects=new ArrayList<PotionEffect>();for(var effect:s.effects()){var key=NamespacedKey.fromString(effect.key());var type=key==null?null:Registry.EFFECT.get(key);if(type==null){plugin.getLogger().warning("Recovery skipped unknown potion effect key: "+effect.key());continue;}effects.add(new PotionEffect(type,effect.duration(),effect.amplifier(),effect.ambient(),effect.particles(),effect.icon()));}
        return new MatchPlayerSnapshot(s.inventory(),s.enderChest(),s.cursor(),s.selected(),s.level(),s.progress(),s.totalExperience(),GameMode.valueOf(s.mode()),s.health(),s.food(),s.saturation(),s.exhaustion(),effects,s.fireTicks(),s.fallDistance(),s.absorption(),s.allowFlight(),s.flying());
    }
    public CompletionStage<Void> store(UUID session,Map<UUID,MatchPlayerSnapshot> snapshots){
        if(storage==null || !ready.getAsBoolean() || !storage.healthy())return CompletableFuture.failedFuture(new IllegalStateException("Match cannot start because recovery storage is unavailable"));
        var records=new ArrayList<RecoveryRepository.Restore>();snapshots.forEach((id,s)->{var payload=codec.encode(pure(s));records.add(new RecoveryRepository.Restore(id,session,UUID.randomUUID(),payload.version(),payload.json(),payload.checksum(),"ORIGINAL",System.currentTimeMillis()));});
        return storage.call(()->{storage.repository().originals(records);return null;}).thenAccept(ignored->{records.forEach(r->rows.put(r.player(),r));originals.putAll(snapshots);});
    }
    public boolean idleForReload(){return deleting.isEmpty() && applied.isEmpty();}
    public void load(List<RecoveryRepository.Restore> records){rows.clear();originals.clear();invalid.clear();for(var row:records){rows.put(row.player(),row);try{originals.put(row.player(),paper(codec.decode(row.version(),row.payload(),row.checksum(),LobbySnapshot.class)));}catch(RuntimeException error){invalid.add(row.player());plugin.getLogger().severe("Invalid durable player restore retained for administrator repair: "+row.player());}}}
    public void route(PlayerIsolation<MatchPlayerSnapshot,LoadoutDefinition> isolation,Map<UUID,Set<UUID>> recoveredActive){
        for(var row:List.copyOf(rows.values())){if(invalid.contains(row.player()))continue;boolean pending=!row.status().equals("ORIGINAL") || !recoveredActive.getOrDefault(row.session(),Set.of()).contains(row.player());
            isolation.recover(row.session(),row.player(),originals.get(row.player()),pending);if(pending)pending(row.player());}
    }
    public LobbySnapshot original(UUID id){var s=originals.get(id);return s==null?null:pure(s);}
    public boolean hasOriginal(UUID id,UUID session){var row=rows.get(id);return row!=null && row.session().equals(session) && row.status().equals("ORIGINAL") && !invalid.contains(id);}
    public void pending(UUID id){var row=rows.get(id);if(row==null)return;storage.call(()->{storage.repository().pendingPlayer(id,row.generation());return null;});}
    public boolean wasRestored(Player player){var row=rows.get(player.getUniqueId());return row!=null && row.generation().toString().equals(player.getPersistentDataContainer().get(restoredGeneration,PersistentDataType.STRING));}
    public boolean blocked(UUID id){return invalid.contains(id) || applied.contains(id);}
    public int pendingCount(){return rows.size();}
    public boolean restore(UUID id,MatchPlayerSnapshot snapshot) {
        Player p=plugin.getServer().getPlayer(id);if(p==null || !p.isOnline() || p.isDead())return false;
        var row=rows.get(id);if(row==null)throw new IllegalStateException("Missing durable original; refusing unsafe restore");
        boolean already=row.generation().toString().equals(p.getPersistentDataContainer().get(restoredGeneration,PersistentDataType.STRING));
        if(!already && !delegate.restore(id,snapshot))return false;
        p.getPersistentDataContainer().set(restoredGeneration,PersistentDataType.STRING,row.generation().toString());
        // Same server-thread turn: no item movement between replacement and the persistent generation/save.
        p.saveData();applied.add(id);delete(row);return true;
    }
    private void delete(RecoveryRepository.Restore row){if(!deleting.add(row.player()))return;
        storage.call(()->{storage.repository().applied(row.player(),row.generation());storage.repository().deleteRestore(row.player(),row.generation());return null;}).whenComplete((ignored,error)->{deleting.remove(row.player());if(error==null && rows.get(row.player())==row){rows.remove(row.player());originals.remove(row.player());applied.remove(row.player());}});
    }
    public void retry(){for(UUID id:List.copyOf(applied)){var row=rows.get(id);if(row!=null)delete(row);}}
}
