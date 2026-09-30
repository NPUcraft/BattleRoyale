package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.loot.*;
import com.npucraft.battleroyale.service.PluginRuntime;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Additional drops only; vanilla drops/experience and ordinary-world mobs are never changed. */
public final class PaperMobLoot implements Listener,AutoCloseable {
    private final JavaPlugin plugin;
    private final PluginRuntime runtime;
    private final NativeLootItems items=new NativeLootItems();
    private final RandomGenerator random=new Random();
    private final NamespacedKey excluded,decided,offlinePlayer,budgetSession,budgetCount,cooldownSession,cooldownAt;
    private MatchContent cachedContent;
    private LootTable table;
    private boolean closed;

    /** Safe during PluginRuntime construction: the content and matches are read only inside events. */
    public PaperMobLoot(JavaPlugin plugin,PluginRuntime runtime){
        this.plugin=plugin;this.runtime=runtime;
        excluded=new NamespacedKey(plugin,"mob_loot_excluded");decided=new NamespacedKey(plugin,"mob_loot_decided");
        offlinePlayer=new NamespacedKey(plugin,"offline_player");budgetSession=new NamespacedKey(plugin,"mob_loot_session");
        budgetCount=new NamespacedKey(plugin,"mob_loot_count");cooldownSession=new NamespacedKey(plugin,"mob_loot_cooldown_session");
        cooldownAt=new NamespacedKey(plugin,"mob_loot_cooldown_at");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void spawned(CreatureSpawnEvent event){
        // Tag excluded origins globally so an egg/custom mob cannot become eligible after being
        // moved into a match or after its chunk/entity is saved and loaded again.
        if(!closed&&!MobLootPolicy.natural(event.getSpawnReason().name()))
            event.getEntity().getPersistentDataContainer().set(excluded,PersistentDataType.BYTE,(byte)1);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void died(EntityDeathEvent event){
        if(closed||runtime==null||!runtime.recoveryReady())return;
        LivingEntity mob=event.getEntity();var origin=mob.getEntitySpawnReason();
        if(!(mob instanceof Enemy)||mob instanceof Player||mob instanceof Tameable
                ||mob.getPersistentDataContainer().has(excluded)||mob.getPersistentDataContainer().has(offlinePlayer)
                ||!MobLootPolicy.natural(origin==null?null:origin.name()))return;
        // A wolf's owner / old damage credit / environmental grinder is not a direct player kill.
        if(!(event.getDamageSource().getCausingEntity() instanceof Player killer)
                ||!killer.isOnline()||killer.isDead()||!killer.getWorld().equals(mob.getWorld())
                ||runtime.pendingRestore(killer.getUniqueId())||runtime.matches().frozen(killer.getUniqueId()))return;
        var entry=runtime.matches().activePlayer(killer.getUniqueId()).filter(value->value.inWorld(mob.getWorld().getUID())).orElse(null);
        if(entry==null)return;
        MatchContent content=runtime.content();if(content==null||!content.mobLoot().enabled())return;
        String session=entry.session.sessionId().toString();var entityData=mob.getPersistentDataContainer();
        if(entityData.has(decided))return;
        // Even an unsuccessful probability/cooldown decision is final for this entity.
        entityData.set(decided,PersistentDataType.STRING,session);
        try{
            if(cachedContent!=content){table=content.mobLoot().resolvedTable(content.tables());cachedContent=content;}
            var settings=content.mobLoot();var worldData=mob.getWorld().getPersistentDataContainer();
            int count=session.equals(worldData.get(budgetSession,PersistentDataType.STRING))
                    ?worldData.getOrDefault(budgetCount,PersistentDataType.INTEGER,0):0;
            var playerData=killer.getPersistentDataContainer();
            long last=session.equals(playerData.get(cooldownSession,PersistentDataType.STRING))
                    ?playerData.getOrDefault(cooldownAt,PersistentDataType.LONG,Long.MIN_VALUE):Long.MIN_VALUE;
            long now=System.currentTimeMillis();
            if(!MobLootPolicy.canAttempt(settings,count,now,last))return;
            playerData.set(cooldownSession,PersistentDataType.STRING,session);playerData.set(cooldownAt,PersistentDataType.LONG,now);
            if(random.nextDouble()>=settings.chance())return;
            var rewards=rewards(table,items,random);if(rewards.isEmpty())return;
            // Counter and cooldown are persistent; logging in again or restoring a match cannot
            // reset its farming budget. No original item or experience is removed.
            worldData.set(budgetSession,PersistentDataType.STRING,session);worldData.set(budgetCount,PersistentDataType.INTEGER,count+1);
            event.getDrops().addAll(rewards);
        }catch(RuntimeException error){plugin.getLogger().log(java.util.logging.Level.WARNING,"怪物额外物资生成失败；原版掉落已保留",error);}
    }
    /** Used by the real-Paper probe as well as death handling; at most three single stacks. */
    public static List<ItemStack> rewards(LootTable table,NativeLootItems items,RandomGenerator random){
        var result=new ArrayList<ItemStack>();
        for(var roll:table.roll(random)){
            if(result.size()>=3)break;
            var stack=items.roll(roll.item(),random);stack.setAmount(Math.min(8,Math.min(stack.getMaxStackSize(),roll.amount())));result.add(stack);
        }
        return List.copyOf(result);
    }
    @Override public void close(){if(closed)return;closed=true;HandlerList.unregisterAll(this);cachedContent=null;table=null;}
}
