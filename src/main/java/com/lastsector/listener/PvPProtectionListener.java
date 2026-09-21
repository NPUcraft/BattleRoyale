package com.lastsector.listener;
import com.lastsector.combat.PvPHazardTracker;
import com.lastsector.paper.PaperMatches;
import com.lastsector.service.PluginRuntime;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import java.util.*;
/** Cancels only attributable player-vs-player harm within one protected running session. */
public final class PvPProtectionListener implements Listener {
    private final PluginRuntime runtime;
    public PvPProtectionListener(PluginRuntime runtime) { this.runtime=runtime; }
    private PaperMatches matches() { return runtime.matches(); }
    private static PvPHazardTracker.BlockKey key(Block block) {
        return new PvPHazardTracker.BlockKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ());
    }
    private boolean inWorld(PaperMatches.Entry entry,World world) {
        return entry.session.gameWorld().orElseThrow().worldName().equals(world.getName());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        matches().activePlayer(victim.getUniqueId()).filter(e -> inWorld(e,victim.getWorld())).ifPresent(entry -> {
            UUID attacker=runtime.provenance().attacker(event,entry);
            if(matches().blocks(entry,attacker,victim.getUniqueId())) event.setCancelled(true);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void combust(EntityCombustEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        matches().activePlayer(victim.getUniqueId()).filter(e -> inWorld(e,victim.getWorld())).ifPresent(entry -> {
            UUID attacker=null;
            if(event instanceof EntityCombustByEntityEvent entity) attacker=runtime.provenance().owner(entity.getCombuster(),entry);
            if(event instanceof EntityCombustByBlockEvent block && block.getCombuster()!=null) attacker=entry.hazards.owner(key(block.getCombuster()));
            if(attacker==null) attacker=runtime.provenance().contactOwner(victim,entry);
            if(matches().blocks(entry,attacker,victim.getUniqueId())) event.setCancelled(true);
            else entry.hazards.burning(victim.getUniqueId(),attacker);
        });
    }
    private static boolean harmful(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(e -> e.getType().getEffectCategory()==org.bukkit.potion.PotionEffectType.Category.HARMFUL);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void splash(PotionSplashEvent event) {
        if(!harmful(event.getPotion().getEffects())) return;
        for(LivingEntity entity:event.getAffectedEntities()) if(entity instanceof Player victim)
            matches().activePlayer(victim.getUniqueId()).ifPresent(entry -> {
                if(matches().blocks(entry,runtime.provenance().owner(event.getPotion(),entry),victim.getUniqueId())) event.setIntensity(victim,0);
            });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void lingering(LingeringPotionSplashEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getAreaEffectCloud().getUniqueId(),runtime.provenance().owner(event.getEntity(),entry));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void cloud(AreaEffectCloudApplyEvent event) {
        var cloud=event.getEntity();
        List<PotionEffect> effects=new ArrayList<>(cloud.getCustomEffects());
        if(cloud.getBasePotionType()!=null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if(!harmful(effects)) return;
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim &&
                matches().activePlayer(victim.getUniqueId()).map(entry ->
                        matches().blocks(entry,runtime.provenance().owner(cloud,entry),victim.getUniqueId())).orElse(false));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void bucket(PlayerBucketEmptyEvent event) {
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,event.getBlock().getWorld())).ifPresent(entry ->
                entry.hazards.block(key(event.getBlock()),event.getBucket()==Material.LAVA_BUCKET?event.getPlayer().getUniqueId():null));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void place(BlockPlaceEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            entry.hazards.block(key(event.getBlock()),null);
            if(event.getBlock().getType()==Material.TNT && entry.session.players().containsKey(event.getPlayer().getUniqueId()))
                entry.hazards.block(key(event.getBlock()),event.getPlayer().getUniqueId());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void explosiveInteraction(PlayerInteractEvent event) {
        Block block=event.getClickedBlock();
        if(block==null || event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock()==Event.Result.DENY) return;
        if(block.getType()!=Material.RESPAWN_ANCHOR && !Tag.BEDS.isTagged(block.getType())) return;
        matches().activePlayer(event.getPlayer().getUniqueId()).filter(e -> inWorld(e,block.getWorld())).ifPresent(entry -> {
            entry.hazards.block(key(block),event.getPlayer().getUniqueId());
            if(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
                var direction=bed.getPart()==org.bukkit.block.data.type.Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
                entry.hazards.block(key(block.getRelative(direction)),event.getPlayer().getUniqueId());
            }
        });
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void ignite(BlockIgniteEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=runtime.provenance().owner(event.getIgnitingEntity(),entry);
            if(owner==null && event.getIgnitingBlock()!=null) owner=entry.hazards.owner(key(event.getIgnitingBlock()));
            entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spread(BlockSpreadEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getSource()),key(event.getBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void flow(BlockFromToEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld()))
            entry.hazards.spread(key(event.getBlock()),key(event.getToBlock()));
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void prime(TNTPrimeEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getBlock().getWorld())) {
            UUID owner=runtime.provenance().owner(event.getPrimingEntity(),entry);
            if(owner==null && event.getPrimingBlock()!=null) owner=entry.hazards.owner(key(event.getPrimingBlock()));
            if(owner!=null) entry.hazards.block(key(event.getBlock()),owner);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void spawn(EntitySpawnEvent event) {
        if(!(event.getEntity() instanceof TNTPrimed)) return;
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getEntity().getWorld())) {
            UUID owner=runtime.provenance().owner(event.getEntity(),entry);
            if(owner==null) owner=entry.hazards.owner(key(event.getLocation().getBlock()));
            entry.hazards.entity(event.getEntity().getUniqueId(),owner);
            entry.hazards.block(key(event.getLocation().getBlock()),null);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void launch(ProjectileLaunchEvent event) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,event.getEntity().getWorld()))
            entry.hazards.entity(event.getEntity().getUniqueId(),runtime.provenance().owner(event.getEntity(),entry));
    }
    private void clear(Block block) {
        for(var entry:matches().runningEntries()) if(inWorld(entry,block.getWorld())) entry.hazards.block(key(block),null);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void broken(BlockBreakEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fade(BlockFadeEvent event) { clear(event.getBlock()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void fill(PlayerBucketFillEvent event) { clear(event.getBlock()); }
}

