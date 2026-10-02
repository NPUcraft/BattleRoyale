package com.npucraft.battleroyale.listener;

import com.npucraft.battleroyale.paper.PaperMatches;
import com.npucraft.battleroyale.service.PluginRuntime;
import com.npucraft.battleroyale.service.UiText;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;

/** Fun ordnance items: throwing torches (fly like fire charges, ignite on impact), the signal gun
 *  (summons an extra supply drop descending straight onto the user), and speed sneakers (permanent
 *  Speed I while worn). All three carry the fun-item PDC marker so loot copies stay recognisable. */
public final class PaperFunItems implements Listener {
    private static final NamespacedKey FUN=new NamespacedKey("battleroyale","fun_item");
    public static final String TORCH="throwing_torch",SNEAKERS="sneakers",SIGNAL_GUN="signal_gun";
    /** Marks a custom fun item stack; called by NativeLootItems while building loot. */
    public static void mark(ItemStack item,String id){
        var meta=item.getItemMeta();meta.getPersistentDataContainer().set(FUN,PersistentDataType.STRING,id);item.setItemMeta(meta);
    }
    private static String funId(ItemStack item){
        if(item==null||!item.hasItemMeta())return null;
        return item.getItemMeta().getPersistentDataContainer().get(FUN,PersistentDataType.STRING);
    }
    private final PluginRuntime runtime;
    private final JavaPlugin plugin;
    private final Map<UUID,Long> lastShot=new HashMap<>();
    private static final long SHOT_COOLDOWN=400_000_000L;
    private final BukkitTask sneakerPulse;
    public PaperFunItems(JavaPlugin plugin,PluginRuntime runtime){
        this.plugin=plugin;this.runtime=runtime;
        sneakerPulse=plugin.getServer().getScheduler().runTaskTimer(plugin,this::sneakerPulse,40L,40L);
    }
    public void close(){sneakerPulse.cancel();}
    private PaperMatches matches(){return runtime.matches();}

    // ---------- throwing torch ----------

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void throwTorchUse(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||!TORCH.equals(funId(event.getItem())))return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        if(matches().activePlayer(event.getPlayer().getUniqueId()).filter(e->e.inWorld(event.getPlayer().getWorld().getUID())).isEmpty())return;
        event.setCancelled(true);
        launchTorch(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void throwTorchSwing(PlayerAnimationEvent event){
        if(event.getAnimationType()!=PlayerAnimationType.ARM_SWING)return;
        var equipment=event.getPlayer().getEquipment();
        if(equipment==null||!TORCH.equals(funId(equipment.getItemInMainHand())))return;
        launchTorch(event.getPlayer());
    }
    private void launchTorch(Player player){
        long now=System.nanoTime();
        if(now-lastShot.getOrDefault(player.getUniqueId(),0L)<SHOT_COOLDOWN)return;
        if(matches().activePlayer(player.getUniqueId()).filter(e->e.inWorld(player.getWorld().getUID())).isEmpty())return;
        lastShot.put(player.getUniqueId(),now);
        var snowball=player.launchProjectile(Snowball.class,player.getEyeLocation().getDirection().normalize().multiply(1.6));
        snowball.setItem(player.getEquipment().getItemInMainHand());
        snowball.getPersistentDataContainer().set(FUN,PersistentDataType.STRING,TORCH);
        consumeOne(player);
    }
    /** Impact: scatter a small patch of fire around the landing spot and ignite hit entities. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void torchImpact(ProjectileHitEvent event){
        if(!(event.getEntity() instanceof Snowball snowball))return;
        if(!TORCH.equals(snowball.getPersistentDataContainer().get(FUN,PersistentDataType.STRING)))return;
        var world=snowball.getWorld();
        var at=snowball.getLocation().getBlock();
        var hitEntity=event.getHitEntity();
        if(hitEntity!=null)hitEntity.setFireTicks(100);
        int placed=0;
        for(int attempt=0;attempt<24&&placed<5;attempt++){
            int dx=ThreadLocalRandom.current().nextInt(-2,3),dz=ThreadLocalRandom.current().nextInt(-2,3),dy=ThreadLocalRandom.current().nextInt(0,2);
            var target=world.getBlockAt(at.getX()+dx,at.getY()+dy,at.getZ()+dz);
            if(!target.getType().isAir())continue;
            var below=target.getRelative(BlockFace.DOWN);
            if(below.getType().isAir()||!below.getType().isSolid())continue;
            target.setType(Material.FIRE,false);placed++;
        }
    }
    private static void consumeOne(Player player){
        var equipment=player.getEquipment();if(equipment==null)return;
        var hand=equipment.getItemInMainHand();
        if(hand.getAmount()<=1)equipment.setItemInMainHand(null);
        else{hand.setAmount(hand.getAmount()-1);equipment.setItemInMainHand(hand);}
    }

    // ---------- signal gun ----------

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void signalGunUse(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||!SIGNAL_GUN.equals(funId(event.getItem())))return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        event.setCancelled(true);
        fireSignalGun(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void signalGunSwing(PlayerAnimationEvent event){
        if(event.getAnimationType()!=PlayerAnimationType.ARM_SWING)return;
        var equipment=event.getPlayer().getEquipment();
        if(equipment==null||!SIGNAL_GUN.equals(funId(equipment.getItemInMainHand())))return;
        fireSignalGun(event.getPlayer());
    }
    private void fireSignalGun(Player player){
        long now=System.nanoTime();
        if(now-lastShot.getOrDefault(player.getUniqueId(),0L)<SHOT_COOLDOWN)return;
        var entryOptional=matches().activePlayer(player.getUniqueId()).filter(e->e.inWorld(player.getWorld().getUID()));
        if(entryOptional.isEmpty())return;
        var entry=entryOptional.get();
        if(entry.session.state()!=com.npucraft.battleroyale.session.GameState.RUNNING)return;
        var airdrops=entry.airdropsView();
        var zone=entry.session.zone().orElse(null);
        if(airdrops==null||zone==null)return;
        lastShot.put(player.getUniqueId(),now);
        if(airdrops.summonSignal(player,zone,now)){
            consumeOne(player);
            player.sendActionBar(UiText.warning(player,"空投正在降落，20 秒后可拾取。","Supply drop incoming; ready in about 20 seconds."));
        }else player.sendActionBar(UiText.warning(player,"空投调度正忙或当前位置无法召唤。","Airdrop scheduler is busy or this spot cannot host a drop."));
    }

    // ---------- sneakers ----------

    /** Every 2s: worn sneakers keep Speed I alive (61t so it lapses ~1s after taking them off). */
    private void sneakerPulse(){
        if(matches()==null)return;
        for(var player:plugin.getServer().getOnlinePlayers()){
            var entry=matches().activePlayer(player.getUniqueId()).filter(e->e.inWorld(player.getWorld().getUID()));
            if(entry.isEmpty())continue;
            var equipment=player.getEquipment();
            if(equipment==null||!SNEAKERS.equals(funId(equipment.getBoots())))continue;
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,61,0,true,false,true));
        }
    }
}
