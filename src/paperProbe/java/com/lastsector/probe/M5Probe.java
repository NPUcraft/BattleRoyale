package com.lastsector.probe;

import com.lastsector.paper.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Test-only public-API fixtures and assertions. Not included in the distribution jar. */
final class M5Probe implements Listener {
    private final TestProbe plugin;
    private final Map<UUID,List<ItemStack>> expected=new HashMap<>();
    M5Probe(TestProbe plugin) {this.plugin=plugin;}
    private Player player(String name) {return Objects.requireNonNull(Bukkit.getPlayerExact(name));}
    private void check(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    private PaperDeathBoxes.View view(Player player) {return (PaperDeathBoxes.View)player.getOpenInventory().getTopInventory().getHolder();}
    void command(CommandSender sender,String[] args) {
        Player p=args.length>1?player(args[1]):null;
        switch(args[0]) {
            case "m5stored" -> {
                var main=(org.bukkit.plugin.java.JavaPlugin)Bukkit.getPluginManager().getPlugin("LastSector");
                p.getInventory().addItem(new StoredExperienceBottles(main).create(347));sender.sendMessage("M5 stored=347");
            }
            case "m5kit" -> {
                p.setInvulnerable(false);p.setGameMode(GameMode.SURVIVAL);p.getInventory().clear();p.setItemOnCursor(null);
                for(var effect:p.getActivePotionEffects())p.removePotionEffect(effect.getType());
                p.setHealth(20);p.setFoodLevel(20);p.setSaturation(0);p.setFireTicks(0);p.setNoDamageTicks(0);
                p.setLevel(0);p.setExp(0);p.setTotalExperience(0);p.giveExp(101);
                if(args.length>2 && args[2].equals("full")) {
                    for(int i=0;i<36;i++) {ItemStack item=new ItemStack(Material.STONE,i+1);final int slot=i;item.editMeta(meta->meta.displayName(net.kyori.adventure.text.Component.text("slot-"+slot)));p.getInventory().setItem(i,item);}
                    ItemStack cursed=new ItemStack(Material.LEATHER_BOOTS);cursed.addEnchantment(Enchantment.VANISHING_CURSE,1);
                    p.getInventory().setArmorContents(new ItemStack[]{cursed,new ItemStack(Material.LEATHER_LEGGINGS),new ItemStack(Material.LEATHER_CHESTPLATE),new ItemStack(Material.LEATHER_HELMET)});
                    p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));p.setItemOnCursor(new ItemStack(Material.DIAMOND,7));
                    List<ItemStack> items=new ArrayList<>();for(var item:p.getInventory().getStorageContents())items.add(item.clone());
                    items.add(p.getInventory().getItemInOffHand().clone());for(var item:p.getInventory().getArmorContents())items.add(item.clone());items.add(p.getItemOnCursor().clone());expected.put(p.getUniqueId(),items);
                }
                sender.sendMessage("M5 kit="+p.getName());
            }
            case "m5damage" -> {
                Player victim=player(args[2]);victim.setNoDamageTicks(0);
                if(args[3].equals("fall"))victim.damage(Double.parseDouble(args[4]),DamageSource.builder(DamageType.FALL).build());
                else victim.damage(Double.parseDouble(args[4]),p);
                sender.sendMessage("M5 damage health="+victim.getHealth());
            }
            case "m5arrow" -> {
                Player victim=player(args[2]);victim.setHealth(1);victim.setNoDamageTicks(0);
                var target=victim.getLocation().add(0,1,0);var start=target.clone().add(0,0,-2);
                Arrow arrow=victim.getWorld().spawnArrow(start,new org.bukkit.util.Vector(0,0,1),2,0);arrow.setShooter(p);arrow.setDamage(20);
                sender.sendMessage("M5 arrow launched");
            }
            case "m5tie" -> {
                for(Player victim:List.of(p,player(args[2]))) {victim.setNoDamageTicks(0);victim.damage(1000,DamageSource.builder(DamageType.FALL).build());}
                sender.sendMessage("M5 tie tick="+Bukkit.getCurrentTick());
            }
            case "m5box" -> {
                var entities=p.getWorld().getEntities().stream().filter(e->e instanceof Interaction && e.getPersistentDataContainer().has(new NamespacedKey("lastsector","deathbox_id"))).toList();
                check(!entities.isEmpty(),"no interaction");Entity entity=entities.get(entities.size()-1);
                sender.sendMessage("M5 box entity="+entity.getEntityId()+" x="+entity.getX()+" y="+entity.getY()+" z="+entity.getZ());
            }
            case "m5contents" -> {
                var v=view(p);var items=expected.get(v.box.deceased());
                if(items!=null) {
                    check(v.box.contents().size()==items.size(),"logical size "+v.box.contents().size());
                    var serializer=new NativeItemSerializer();
                    for(int i=0;i<items.size();i++) {
                        ItemStack actual=serializer.item(v.box.contents().get(i));ItemStack want=items.get(i);
                        // Armor durability legitimately changed due to the killing damage; all other metadata must survive.
                        if(i>=37 && i<=40 && actual.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damage && want.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable wanted) {wanted.setDamage(damage.getDamage());want.setItemMeta(wanted);}
                        check(actual.equals(want),"payload slot "+i+" "+actual+" != "+want);
                    }
                    check(v.box.storedXp()==50,"XP "+v.box.storedXp());
                }
                sender.sendMessage("M5 contents="+v.box.contents().size()+" xp="+v.box.storedXp()+" reason="+v.box.reason()+" tick="+v.box.eliminationTick()+" viewers="+v.inventory.getViewers().size());
            }
            case "m5shared" -> {check(view(p).inventory==view(player(args[2])).inventory,"not shared");sender.sendMessage("M5 shared=true");}
            case "m5guards" -> {
                var v=view(p);var before=v.inventory.getContents();
                for(ClickType click:List.of(ClickType.NUMBER_KEY,ClickType.SWAP_OFFHAND,ClickType.DOUBLE_CLICK,ClickType.MIDDLE,ClickType.DROP)) {
                    var e=new InventoryClickEvent(p.getOpenInventory(),InventoryType.SlotType.CONTAINER,1,click,InventoryAction.HOTBAR_SWAP,0);Bukkit.getPluginManager().callEvent(e);check(e.isCancelled(),"click "+click);
                }
                var drag=new InventoryDragEvent(p.getOpenInventory(),new ItemStack(Material.STONE),new ItemStack(Material.STONE,2),false,Map.of(1,new ItemStack(Material.STONE)));
                Bukkit.getPluginManager().callEvent(drag);check(drag.isCancelled(),"drag");
                var creative=new InventoryCreativeEvent(p.getOpenInventory(),InventoryType.SlotType.CONTAINER,1,new ItemStack(Material.DIAMOND));Bukkit.getPluginManager().callEvent(creative);check(creative.isCancelled(),"creative");
                check(Arrays.equals(before,v.inventory.getContents()),"guard mutation");sender.sendMessage("M5 guards=true");
            }
            case "m5empty" -> {var v=view(p);check(Arrays.stream(v.inventory.getContents()).allMatch(i->i==null||i.getType().isAir()),"not empty");check(p.getWorld().getEntities().stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey("lastsector","deathbox_id"))).count()>=3,"visuals gone");sender.sendMessage("M5 empty persists=true");}
            case "m5denied" -> {
                var viewer=player(args[2]);var v=view(p);check(!v.owner.allowed(v,viewer),"access unexpectedly allowed");sender.sendMessage("M5 denied=true");
            }
            case "m5bottles" -> {
                var main=(org.bukkit.plugin.java.JavaPlugin)Bukkit.getPluginManager().getPlugin("LastSector");var bottles=new StoredExperienceBottles(main);
                var serializer=new NativeItemSerializer();ItemStack decoded=serializer.item(serializer.store(bottles.create(347)));check(bottles.read(decoded.getItemMeta().getPersistentDataContainer())==347,"347 roundtrip");
                for(Object bad:List.of(-1,0,Integer.MAX_VALUE,"347")) {
                    ItemStack item=bottles.create(347);item.editMeta(meta->{var data=meta.getPersistentDataContainer();var key=new NamespacedKey("lastsector","stored_xp");if(bad instanceof Integer n)data.set(key,PersistentDataType.INTEGER,n);else data.set(key,PersistentDataType.STRING,(String)bad);});
                    check(bottles.read(item.getItemMeta().getPersistentDataContainer())==null,"bad accepted");
                    var projectile=p.getWorld().spawn(p.getLocation().add(0,4,0),ThrownExpBottle.class);var event=new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent(p,item,projectile);Bukkit.getPluginManager().callEvent(event);check(event.isCancelled(),"bad launch");projectile.remove();
                }
                p.getInventory().addItem(new ItemStack(Material.EXPERIENCE_BOTTLE));sender.sendMessage("M5 bottles=true");
            }
            case "m5blast" -> {
                var visual=p.getWorld().getEntities().stream().filter(e->e.getPersistentDataContainer().has(new NamespacedKey("lastsector","deathbox_id"))).toList();check(visual.size()>=3,"no visuals");
                var players=p.getWorld().getPlayers();players.forEach(q->q.setInvulnerable(true));
                p.getWorld().createExplosion(visual.getFirst().getLocation(),2,false,false);
                players.forEach(q->q.setInvulnerable(false));check(visual.stream().allMatch(Entity::isValid),"explosion removed visual");sender.sendMessage("M5 blast survives=true");
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e) {
        if(!e.getEntity().getWorld().getName().startsWith("plugins/LastSector/runtime/"))return;
        plugin.getLogger().info("M5 death="+e.getEntity().getName()+" drops="+e.getDrops().size()+" xp="+e.getDroppedExp()+" keep="+e.getKeepInventory()+" tick="+Bukkit.getCurrentTick());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void xp(ExpBottleEvent e) {
        plugin.getLogger().info("M5 XP="+e.getExperience()+" marked="+e.getEntity().getPersistentDataContainer().has(new NamespacedKey("lastsector","stored_xp_bottle")));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void firework(FireworkExplodeEvent e) {
        if(!e.getEntity().getPersistentDataContainer().has(new NamespacedKey("lastsector","celebration_session")))return;
        // An eliminated player in the lobby is not covered by ENDING's survivor shield.
        Player outsider=Bukkit.getPlayerExact("LSBob");if(outsider==null)return;
        var damage=new EntityDamageByEntityEvent(e.getEntity(),outsider,EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,6);
        Bukkit.getPluginManager().callEvent(damage);
        plugin.getLogger().info("M5 own firework outsider damage cancelled="+damage.isCancelled());
    }
}

