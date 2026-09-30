package com.npucraft.battleroyale.probe;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.damage.*;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import java.util.*;
/** Candidate verification precedes selecting a production OfflineBody representation. */
final class M6Probe implements Listener {
    private final TestProbe plugin;private UUID candidate;private UUID hostile;private int events;
    private final Map<UUID,com.npucraft.battleroyale.offline.BodySnapshot> matches=new HashMap<>();
    private final com.npucraft.battleroyale.paper.PaperBodySnapshots snapshots=new com.npucraft.battleroyale.paper.PaperBodySnapshots(new com.npucraft.battleroyale.paper.NativeItemSerializer());
    M6Probe(TestProbe plugin){this.plugin=plugin;}
    void command(CommandSender sender,String[] args) {
        Player p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]));
        switch(args[0]) {
            case "m6state" -> sender.sendMessage("M6 mode="+p.getGameMode()+" world="+p.getWorld().getName()+" health="+p.getHealth()+" target="+(p.getSpectatorTarget()==null?"none":p.getSpectatorTarget().getUniqueId())+" targetName="+(p.getSpectatorTarget() instanceof Player watched?watched.getName():"none")+" items="+Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).filter(i->!i.getType().isAir()).count()+" xp="+com.npucraft.battleroyale.combat.ExperienceMath.total(p.getLevel(),p.getExp()));
            case "m6matchseed" -> {
                p.getInventory().clear();p.getInventory().setItem(0,new org.bukkit.inventory.ItemStack(Material.IRON_SWORD));p.getInventory().setItem(5,new org.bukkit.inventory.ItemStack(Material.DIAMOND,13));p.getInventory().setHeldItemSlot(5);
                p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[]{new org.bukkit.inventory.ItemStack(Material.LEATHER_BOOTS),new org.bukkit.inventory.ItemStack(Material.LEATHER_LEGGINGS),new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE),new org.bukkit.inventory.ItemStack(Material.LEATHER_HELMET)});
                p.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(Material.SHIELD));p.setItemOnCursor(new org.bukkit.inventory.ItemStack(Material.EMERALD,2));
                p.setLevel(0);p.setExp(0);p.setTotalExperience(0);p.giveExp(101);p.setHealth(18);p.setFoodLevel(16);p.setSaturation(2);p.setRemainingAir(200);
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED,12000,1));p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.HEALTH_BOOST,12000,0));matches.put(p.getUniqueId(),snapshots.player(p));sender.sendMessage("M6 match seeded");
            }
            case "m6matchcheck" -> {
                var before=matches.get(p.getUniqueId());var after=snapshots.player(p);
                if(!before.inventory().equals(after.inventory()) || !Objects.equals(before.cursor(),after.cursor()) || before.totalXp()!=after.totalXp() || before.selected()!=after.selected() || before.maxHealth()!=after.maxHealth())throw new IllegalStateException("Match inventory/XP changed");
                sender.sendMessage("M6 match restored=true health="+p.getHealth()+" x="+p.getX()+" z="+p.getZ()+" speed="+p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED));
            }
            case "m6body" -> {
                var body=body(p,args[2]);sender.sendMessage("M6 body entity="+body.getEntityId()+" health="+body.getHealth()+" max="+body.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()+" x="+body.getX()+" z="+body.getZ());
            }
            case "m6removebody" -> {body(p,args[2]).remove();sender.sendMessage("M6 body removed by fixture");}
            case "m6bodyhealth" -> {var body=body(p,args[2]);body.setHealth(Double.parseDouble(args[3]));body.setNoDamageTicks(0);body.setFireTicks(0);sender.sendMessage("M6 body health set");}
            case "m6bodyhit" -> {
                var body=body(p,args[2]);body.setNoDamageTicks(0);double amount=Double.parseDouble(args[4]);
                switch(args[3]) {
                    case "melee" -> body.damage(amount,p);
                    case "arrow" -> {var arrow=body.getWorld().spawnArrow(body.getLocation().add(0,1,-2),new org.bukkit.util.Vector(0,0,1),2,0);arrow.setShooter(p);arrow.setDamage(amount);}
                    case "tnt" -> body.damage(amount,DamageSource.builder(DamageType.PLAYER_EXPLOSION).withCausingEntity(p).withDirectEntity(p).build());
                    case "fire" -> body.setFireTicks(80);
                    case "lava" -> {var block=body.getLocation().getBlock();var old=block.getBlockData();block.setType(Material.LAVA,false);Bukkit.getScheduler().runTaskLater(plugin,()->block.setBlockData(old,false),12);}
                    case "zone" -> {body.getWorld().setChunkForceLoaded(1000>>4,0,true);body.teleport(new Location(body.getWorld(),1000,-60,0));}
                    case "mob" -> {body.getWorld().setDifficulty(Difficulty.NORMAL);var mob=body.getWorld().spawn(body.getLocation().add(1,0,0),Zombie.class,e->e.setShouldBurnInDay(false));hostile=mob.getUniqueId();mob.setTarget(body);}
                }
                sender.sendMessage("M6 body hit health="+body.getHealth());
            }
            case "m6mobobserver" -> {p.setGameMode(GameMode.CREATIVE);p.setFireTicks(0);p.setHealth(20);p.teleport(new Location(p.getWorld(),20,-60,20));sender.sendMessage("M6 mob observer ready");}
            case "m6mobnear" -> {
                var body=body(p,args[2]);body.getWorld().setDifficulty(Difficulty.NORMAL);
                var mob=body.getWorld().spawn(body.getLocation().add(1,0,0),Zombie.class,e->{e.setShouldBurnInDay(false);e.setRemoveWhenFarAway(false);});hostile=mob.getUniqueId();sender.sendMessage("M6 mob spawned target="+mob.getTarget());
            }
            case "m6mobstatus" -> {var mob=(Mob)Bukkit.getEntity(hostile);sender.sendMessage("M6 mob target="+(mob==null || mob.getTarget()==null?"none":mob.getTarget().getType())+" health="+body(p,args[2]).getHealth());}
            case "m6mobclear" -> {var mob=Bukkit.getEntity(hostile);if(mob!=null)mob.remove();sender.sendMessage("M6 mob removed");}
            case "m6teleport" -> {var other=Objects.requireNonNull(Bukkit.getPlayerExact(args[2]));sender.sendMessage("M6 spectate teleport="+p.teleport(other.getLocation(),org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.SPECTATE));}
            case "m6target" -> {
                Player target=Objects.requireNonNull(Bukkit.getPlayerExact(args[2]));
                var event=new com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent(p,p.getSpectatorTarget()==null?p:p.getSpectatorTarget(),target);Bukkit.getPluginManager().callEvent(event);
                sender.sendMessage("M6 target cancelled="+event.isCancelled());
                if(!event.isCancelled())p.setSpectatorTarget(target);
            }
            case "m6free" -> {p.setSpectatorTarget(null);var at=p.getLocation();if(!p.teleport(at.clone().add(5,10,5)))throw new IllegalStateException("Free flight teleport rejected");sender.sendMessage("M6 free=true");}
            case "m6teamspectatetie" -> {for(String name:Arrays.copyOfRange(args,1,args.length)){var victim=Objects.requireNonNull(Bukkit.getPlayerExact(name));victim.setNoDamageTicks(0);victim.damage(1000,DamageSource.builder(DamageType.FALL).build());}sender.sendMessage("M6 tie tick="+Bukkit.getCurrentTick());}
            case "m6candidate" -> {
                Villager v=p.getWorld().spawn(p.getLocation().add(2,0,0),Villager.class,e->{e.setAI(false);e.setAdult();e.setSilent(true);e.setPersistent(true);e.setRemoveWhenFarAway(false);e.setCanPickupItems(false);e.customName(net.kyori.adventure.text.Component.text("Disconnected candidate"));e.setCustomNameVisible(true);});
                candidate=v.getUniqueId();events=0;sender.sendMessage("M6 candidate="+v.getEntityId()+" equipment="+(v.getEquipment()!=null));
            }
            case "m6candidatehit" -> {
                var v=(Villager)Objects.requireNonNull(Bukkit.getEntity(candidate));v.setHealth(20);v.setNoDamageTicks(0);v.setFireTicks(0);
                switch(args[2]) {
                    case "melee" -> v.damage(4,p);
                    case "explosion" -> v.getWorld().createExplosion(v.getLocation(),1,false,false,p);
                    case "arrow" -> {var arrow=v.getWorld().spawnArrow(v.getLocation().add(0,1,-2),new org.bukkit.util.Vector(0,0,1),2,0);arrow.setShooter(p);arrow.setDamage(2);}
                    case "fire" -> v.setFireTicks(80);
                    case "lava" -> {var block=v.getLocation().getBlock();var old=block.getBlockData();block.setType(Material.LAVA,false);Bukkit.getScheduler().runTaskLater(plugin,()->block.setBlockData(old,false),15);}
                    case "mob" -> {v.getWorld().setDifficulty(Difficulty.NORMAL);var mob=v.getWorld().spawn(v.getLocation().add(1,0,0),Zombie.class,e->e.setShouldBurnInDay(false));hostile=mob.getUniqueId();mob.setTarget(v);}
                    case "zone" -> v.setHealth(v.getHealth()-4);
                    default -> throw new IllegalArgumentException(args[2]);
                }
                sender.sendMessage("M6 candidatehit="+args[2]);
            }
            case "m6candidatestatus" -> {var v=(LivingEntity)Objects.requireNonNull(Bukkit.getEntity(candidate));sender.sendMessage("M6 candidate health="+v.getHealth()+" damageEvents="+events+" alive="+v.isValid());}
            case "m6candidateclear" -> {for(UUID id:new UUID[]{candidate,hostile})if(id!=null){var e=Bukkit.getEntity(id);if(e!=null)e.remove();}sender.sendMessage("M6 candidate removed="+(Bukkit.getEntity(candidate)==null));}
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
    private LivingEntity body(Player viewer,String name){UUID id=Bukkit.getOfflinePlayer(name).getUniqueId();return viewer.getWorld().getLivingEntities().stream().filter(e->e instanceof Villager && id.toString().equals(e.getPersistentDataContainer().get(new NamespacedKey("battleroyale","offline_player"),org.bukkit.persistence.PersistentDataType.STRING))).findFirst().orElseThrow(()->new IllegalStateException("No body "+name));}
    @EventHandler(priority=EventPriority.MONITOR) public void damage(EntityDamageEvent event){if(event.getEntity().getUniqueId().equals(candidate))events++;}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(EntityDeathEvent event){if(event.getEntity().getUniqueId().equals(candidate)){event.getDrops().clear();event.setDroppedExp(0);}}
}
