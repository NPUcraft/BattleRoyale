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
    public static final String TORCH="throwing_torch",SNEAKERS="sneakers",SIGNAL_GUN="signal_gun",MYSTERY_FOOD="mystery_food",
            COIN_100="coin_100",COIN_500="coin_500",COIN_1000="coin_1000";
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
    /** Impact: ignite the outer face of the hit block (always air), scatter a few more fires around
     *  the landing spot, and light up the struck entity. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void torchImpact(ProjectileHitEvent event){
        if(!(event.getEntity() instanceof Snowball snowball))return;
        if(!TORCH.equals(snowball.getPersistentDataContainer().get(FUN,PersistentDataType.STRING)))return;
        var world=snowball.getWorld();
        var hitEntity=event.getHitEntity();
        if(hitEntity!=null)hitEntity.setFireTicks(140);
        int placed=0;
        var hitBlock=event.getHitBlock();
        if(hitBlock!=null&&event.getHitBlockFace()!=null){
            var target=hitBlock.getRelative(event.getHitBlockFace());
            if(target.getType().isAir()){target.setType(Material.FIRE);placed++;}
        }
        var at=snowball.getLocation().getBlock();
        for(int attempt=0;attempt<64&&placed<14;attempt++){
            int dx=ThreadLocalRandom.current().nextInt(-6,7),dz=ThreadLocalRandom.current().nextInt(-6,7),dy=ThreadLocalRandom.current().nextInt(0,4);
            var target=world.getBlockAt(at.getX()+dx,at.getY()+dy,at.getZ()+dz);
            if(!target.getType().isAir())continue;
            var below=target.getRelative(BlockFace.DOWN);
            if(!below.getType().isSolid())continue;
            target.setType(Material.FIRE);placed++;
        }
        plugin.getLogger().info("TORCH_HIT at="+at.getX()+","+at.getY()+","+at.getZ()+" placed="+placed+" entity="+(hitEntity!=null));
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

    // ---------- currency coins ----------

    /** Trial economy tokens: emerald vouchers that deposit their face value on either click. */
    private static long coinValue(ItemStack item){
        String id=funId(item);
        if(COIN_100.equals(id))return 100;
        if(COIN_500.equals(id))return 500;
        if(COIN_1000.equals(id))return 1000; // legacy vouchers still redeem at face value
        return 0;
    }
    /** Runs even on cancelled events: lobby protection cancels block interactions, but a coin
     *  must redeem on any click, air or block. The event is then cancelled to keep it exclusive. */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void coinUse(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND)return;
        long amount=coinValue(event.getItem());
        if(amount<=0)return;
        var action=event.getAction();
        if(action!=Action.RIGHT_CLICK_AIR&&action!=Action.RIGHT_CLICK_BLOCK&&action!=Action.LEFT_CLICK_AIR&&action!=Action.LEFT_CLICK_BLOCK)return;
        event.setCancelled(true);
        redeemCoin(event.getPlayer(),amount);
    }
    private void redeemCoin(Player player,long amount){
        var progression=runtime.progression();
        if(progression==null)return;
        var economy=progression.economy();
        if(!economy.available()){
            player.sendActionBar(UiText.warning(player,"经济服务未就绪，稍后再试。","Economy is not ready; try again later."));
            return;
        }
        boolean paid;
        double balance=Double.NaN;
        try{
            paid=economy.provider().deposit(player.getUniqueId(),java.math.BigDecimal.valueOf(amount));
            if(paid)balance=economy.provider().getBalance(player.getUniqueId()).doubleValue();
        }catch(LinkageError|RuntimeException error){paid=false;}
        if(!paid){
            player.sendActionBar(UiText.warning(player,"入账失败，请稍后再试。","Deposit failed; try again later."));
            return;
        }
        consumeOne(player);
        player.sendActionBar(UiText.success(player,
                "+"+amount+" 金币 · 余额 "+com.npucraft.battleroyale.paper.PaperEconomyRewards.format(balance),
                "+"+amount+" coins · balance "+com.npucraft.battleroyale.paper.PaperEconomyRewards.format(balance)));
        player.playSound(player.getLocation(),org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.8f,1.5f);
    }

    // ---------- mystery food ----------

    /** The cursed feast: eight buffs, a wither roar heard across the world, death after a minute.
     *  Eaten normally the buffs last a full minute; once the match has reached the final zone stage
     *  the curse only grants 15 seconds of power (death still comes 60s after the bite). */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void mysteryFeast(PlayerItemConsumeEvent event){
        if(!MYSTERY_FOOD.equals(funId(event.getItem())))return;
        var player=event.getPlayer();
        var entryOptional=matches().activePlayer(player.getUniqueId()).filter(e->e.inWorld(player.getWorld().getUID()));
        if(entryOptional.isEmpty())return;
        var entry=entryOptional.get();
        var zone=entry.session.zone().orElse(null);
        boolean finalStage=zone!=null&&zone.stageIndex()==zone.stageCount()-1;
        int buffTicks=finalStage?15*20:60*20;
        for(var type:new PotionEffectType[]{PotionEffectType.JUMP_BOOST,PotionEffectType.SPEED,PotionEffectType.STRENGTH,
                PotionEffectType.FIRE_RESISTANCE,PotionEffectType.RESISTANCE,PotionEffectType.HASTE,
                PotionEffectType.NIGHT_VISION,PotionEffectType.GLOWING,PotionEffectType.ABSORPTION,
                PotionEffectType.SATURATION,PotionEffectType.WATER_BREATHING,PotionEffectType.LUCK,
                PotionEffectType.CONDUIT_POWER,PotionEffectType.DOLPHINS_GRACE,PotionEffectType.HERO_OF_THE_VILLAGE}){
            player.addPotionEffect(new PotionEffect(type,buffTicks,0,false,true,true));
        }
        var loc=player.getLocation();
        for(var other:player.getWorld().getPlayers())other.playSound(loc,Sound.ENTITY_WITHER_SPAWN,1f,1f);
        for(var other:player.getWorld().getPlayers())other.sendMessage(UiText.message("§c有人服下了禁忌盛宴——"+(finalStage?"15":"60")+" 秒后将被诅咒吞噬。"));
        player.sendActionBar(UiText.warning(player,finalStage?"15 秒后死亡……跑！":"60 秒后死亡……跑！",finalStage?"You will die in 15 seconds... run!":"You will die in 60 seconds... run!"));
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(!player.isOnline()||player.isDead())return;
            if(matches().activePlayer(player.getUniqueId()).filter(e->e.inWorld(player.getWorld().getUID())).isEmpty())return;
            player.setHealth(0);
        },finalStage?15L*20L:60L*20L);
    }
}
