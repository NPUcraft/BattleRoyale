package com.lastsector.paper;
import com.lastsector.progression.*;
import com.lastsector.cosmetic.*;
import com.lastsector.config.ProgressionConfig;
import com.lastsector.storage.*;
import com.lastsector.economy.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.*;
/** Permanent-data coordinator. JDBC and local outbox IO use the existing bounded worker. */
public final class PaperProgression implements AutoCloseable {
    private final JavaPlugin plugin;
    private final RecoveryStorage storage;
    private final ProgressionConfig config;
    private final PermanentRepository repository;
    private final CosmeticRepository cosmetics;
    private final LeaderboardRepository leaderboards;
    private final LeaderboardCache cache;
    private final Map<UUID,PlayerProfile> profiles=new HashMap<>();
    private final Set<UUID> reloadAfterLoad=new HashSet<>();
    private final Set<UUID> loading=new HashSet<>(),failed=new HashSet<>(),writing=new HashSet<>();
    private final Map<UUID,MatchResult> pending=new LinkedHashMap<>();
    private final Map<UUID,CompletableFuture<Void>> durable=new HashMap<>();
    private final PurchaseService purchases;
    private ResultOutbox outbox;
    private boolean initialized,initializing,closed;
    private final EconomySelection economy;
    public PaperProgression(JavaPlugin plugin,RecoveryStorage storage,StorageProvider provider,ProgressionConfig config,String economyName) {
        this.plugin=plugin;this.storage=storage;this.config=config;
        repository=new PermanentRepository(provider,config.ranking());cosmetics=new CosmeticRepository(provider);leaderboards=new LeaderboardRepository(provider);
        cache=new LeaderboardCache(config.ranking().cacheSeconds()*1000L,System::currentTimeMillis);
        purchases=new PurchaseService(cosmetics,new PurchaseService.AsyncDatabase(){public <T> CompletableFuture<T> call(Callable<T> work){return storage.call(work);}});
        economy=EconomySelection.select(economyName,config.economyPriority(),config.currency(),config.shopEnabled(),name->{
            try {return switch(name) {
                case "coinsengine" -> plugin.getServer().getPluginManager().isPluginEnabled("CoinsEngine") && plugin.getServer().getPluginManager().getPlugin("CoinsEngine").getPluginMeta().getVersion().startsWith("2.7.")?new LegacyCoinsEngineEconomyProvider(plugin.getServer(),config.currency()):null;
                case "excellenteconomy" -> plugin.getServer().getPluginManager().isPluginEnabled("ExcellentEconomy")?new ExcellentEconomyProvider(plugin.getServer(),config.currency()):null;
                case "vault" -> plugin.getServer().getPluginManager().isPluginEnabled("Vault")?new VaultEconomyProvider(plugin.getServer()):null;
                default -> null;
            };}catch(LinkageError|RuntimeException error){plugin.getLogger().warning("Economy adapter unavailable: "+name+" ("+error.getClass().getSimpleName()+")");return null;}
        });
        plugin.getLogger().info("Economy: "+economy.diagnostics());
        if(config.shopEnabled() && economy.provider()==null)plugin.getLogger().warning("Paid cosmetic shop disabled: configured economy provider/currency is unavailable. Matches and free cosmetics remain available.");
    }
    public String diagnostics(){return "profiles="+profiles.size()+" profileLoading="+loading.size()+" profileFailed="+failed.size()+" resultOutboxPending="+pending.size()+" initialized="+initialized+" leaderboards="+cache.diagnostics();}
    public boolean ready(){return initialized;}
    public ProgressionConfig config(){return config;}
    public EconomySelection economy(){return economy;}
    public boolean idle(){return loading.isEmpty()&&writing.isEmpty()&&pending.isEmpty()&&plugin.getServer().getOnlinePlayers().stream().noneMatch(p->purchases.busy(p.getUniqueId()));}
    public void tick(boolean recoveryReady) {
        if(closed || !recoveryReady)return;
        if(!initialized) {
            if(initializing)return;initializing=true;
            storage.call(()->{outbox=new ResultOutbox(plugin.getDataFolder().toPath().resolve("result-outbox"));cosmetics.quarantineAmbiguous();return outbox.pending();}).whenComplete((results,error)->{
                initializing=false;if(error!=null)return;initialized=true;results.forEach(this::submit);
            });return;
        }
        purchases.retryReviews();
        for(var p:plugin.getServer().getOnlinePlayers())if(!loading.contains(p.getUniqueId()) && (!profiles.containsKey(p.getUniqueId()) || !profiles.get(p.getUniqueId()).name().equals(p.getName())))load(p);
        profiles.keySet().removeIf(id->plugin.getServer().getPlayer(id)==null && !purchases.busy(id));
        for(var result:List.copyOf(pending.values()))flush(result);
    }
    public void load(Player player) {
        if(!initialized)return;
        if(!loading.add(player.getUniqueId())){reloadAfterLoad.add(player.getUniqueId());return;}
        UUID id=player.getUniqueId();String name=player.getName();
        storage.call(()->repository.load(id,name,System.currentTimeMillis())).whenComplete((profile,error)->{
            loading.remove(id);if(error!=null){failed.add(id);return;}failed.remove(id);
            var online=plugin.getServer().getPlayer(id);if(online!=null)profiles.put(id,profile);
            if(reloadAfterLoad.remove(id)&&online!=null)load(online);
        });
    }
    public PlayerProfile profile(UUID id){return profiles.get(id);}
    public void check(UUID id){if(pending.values().stream().anyMatch(r->r.players().stream().anyMatch(p->p.playerId().equals(id))))throw new IllegalStateException("Your previous match result is still saving.");if(!initialized || loading.contains(id) || !profiles.containsKey(id))throw new IllegalStateException(failed.contains(id)?"Profile unavailable":"Your LastSector profile is still loading.");}
    public Map<UUID,SessionProgress.Frozen> freeze(Set<UUID> ids) {
        var values=new HashMap<UUID,SessionProgress.Frozen>();
        for(UUID id:ids) {
            check(id);var p=profiles.get(id);var selected=new EnumMap<CosmeticCategory,String>(CosmeticCategory.class);
            p.equipped().forEach((category,cosmetic)->{if(p.unlocks().contains(cosmetic)&&config.cosmetics().containsKey(cosmetic)){var definition=config.cosmetics().get(cosmetic);if(definition.category().name().equals(category))selected.put(definition.category(),cosmetic);}});
            values.put(id,new SessionProgress.Frozen(p.rating(),new SessionCosmeticLoadout(selected)));
        }return Map.copyOf(values);
    }
    public CompletableFuture<Void> submit(MatchResult result) {
        pending.putIfAbsent(result.sessionId(),result);var future=durable.computeIfAbsent(result.sessionId(),id->new CompletableFuture<>());
        if(initialized)flush(result);return future;
    }
    private void flush(MatchResult result) {
        UUID id=result.sessionId();if(!writing.add(id))return;
        storage.call(()->{outbox.persist(result);return null;}).whenComplete((ignored,error)->{
            if(error!=null){writing.remove(id);plugin.getLogger().severe("Result outbox write failed; cleanup remains blocked for "+id);return;}
            durable.get(id).complete(null);
            storage.call(()->{var saved=repository.finalizeResult(result);outbox.acknowledge(id);return saved;}).whenComplete((saved,failure)->{
                writing.remove(id);if(failure!=null)return;pending.remove(id);durable.remove(id);cache.invalidate();
                for(var p:saved.players()){var online=plugin.getServer().getPlayer(p.playerId());if(online!=null)load(online);}
            });
        });
    }
    public CompletableFuture<List<LeaderboardRepository.Row>> leaderboard(LeaderboardRepository.Query query){return cache.get(query,()->storage.call(()->leaderboards.query(query)));}
    public CompletableFuture<String> buy(UUID id,CosmeticDefinition definition) {
        check(id);if(!definition.equals(config.cosmetics().get(definition.id())))throw new IllegalStateException("Cosmetic configuration changed; reopen the shop");if(definition.price().signum()>0 && !economy.shopEnabled())throw new IllegalStateException("Paid shop unavailable: no economy provider");
        return purchases.buy(id,definition,economy.provider(),economy.active(),economy.currency()).whenComplete((value,error)->{refresh(id);if(value!=null&&value.contains("review"))plugin.getLogger().severe(value);});
    }
    public CompletableFuture<Void> equip(UUID id,CosmeticDefinition definition,boolean remove) {
        check(id);return storage.call(()->{if(!remove&&definition.price().signum()==0)cosmetics.grant(id,definition.id(),System.currentTimeMillis());cosmetics.equip(id,definition.category(),remove?null:definition.id());return (Void)null;}).whenComplete((value,error)->refresh(id));
    }
    public CompletableFuture<Void> grant(UUID id,String cosmetic,boolean revoke) {
        if(!config.cosmetics().containsKey(cosmetic))throw new IllegalArgumentException("Unknown cosmetic");
        return storage.call(()->{if(revoke)cosmetics.revoke(id,cosmetic);else cosmetics.grant(id,cosmetic,System.currentTimeMillis());return (Void)null;}).whenComplete((value,error)->refresh(id));
    }
    public CompletableFuture<Long> manualReviewCount(){return storage.call(cosmetics::manualReviewCount);}
    public CompletableFuture<List<Purchase>> purchases(){return storage.call(()->cosmetics.manualReview(0));}
    private void refresh(UUID id){var online=plugin.getServer().getPlayer(id);if(online!=null)load(online);}
    public void close(){closed=true;for(var result:pending.values())storage.call(()->{if(outbox==null)outbox=new ResultOutbox(plugin.getDataFolder().toPath().resolve("result-outbox"));outbox.persist(result);return null;});}
}
