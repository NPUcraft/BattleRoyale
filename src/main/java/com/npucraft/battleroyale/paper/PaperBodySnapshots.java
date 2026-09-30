package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.offline.*;
import com.npucraft.battleroyale.combat.ExperienceMath;
import com.npucraft.battleroyale.loadout.StoredItem;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.*;
import java.util.*;
/** Converts mutable Paper state to detached match payloads and back on the server thread. */
public final class PaperBodySnapshots {
    private final NativeItemSerializer items;
    public PaperBodySnapshots(NativeItemSerializer items){this.items=items;}
    public static BodyPosition position(Location at){return new BodyPosition(at.getWorld().getUID(),at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());}
    public static Location location(BodyPosition at){return new Location(Objects.requireNonNull(Bukkit.getWorld(at.world())),at.x(),at.y(),at.z(),at.yaw(),at.pitch());}
    private List<BodySnapshot.Effect> effects(LivingEntity entity){return entity.getActivePotionEffects().stream().map(e->new BodySnapshot.Effect(e.getType().getKey().toString(),e.getDuration(),e.getAmplifier(),e.isAmbient(),e.hasParticles(),e.hasIcon())).toList();}
    public BodySnapshot player(Player p) {
        Map<Integer,StoredItem> carried=new HashMap<>();var contents=p.getInventory().getContents();for(int i=0;i<contents.length;i++){var stored=items.store(contents[i]);if(stored!=null)carried.put(i,stored);}
        return new BodySnapshot(position(p.getLocation()),p.getHealth(),p.getAttribute(Attribute.MAX_HEALTH).getValue(),p.getAbsorptionAmount(),p.getFoodLevel(),p.getSaturation(),p.getFireTicks(),p.getRemainingAir(),p.getFallDistance(),effects(p),carried,items.store(p.getItemOnCursor()),p.getInventory().getHeldItemSlot(),p.getLevel(),p.getExp(),ExperienceMath.total(p.getLevel(),p.getExp()));
    }
    public BodySnapshot carrier(LivingEntity carrier,BodySnapshot original) {
        Map<Integer,StoredItem> inventory=new HashMap<>(original.inventory());EntityEquipment equipment=carrier.getEquipment();
        if(equipment!=null){var armor=equipment.getArmorContents();for(int i=0;i<4;i++)put(inventory,36+i,armor[i]);put(inventory,40,equipment.getItemInOffHand());put(inventory,original.selected(),equipment.getItemInMainHand());}
        return new BodySnapshot(position(carrier.getLocation()),carrier.getHealth(),carrier.getAttribute(Attribute.MAX_HEALTH).getValue(),carrier.getAbsorptionAmount(),original.food(),original.saturation(),carrier.getFireTicks(),carrier.getRemainingAir(),carrier.getFallDistance(),effects(carrier),inventory,original.cursor(),original.selected(),original.level(),original.progress(),original.totalXp());
    }
    private void put(Map<Integer,StoredItem> inventory,int slot,ItemStack item){var stored=items.store(item);if(stored==null)inventory.remove(slot);else inventory.put(slot,stored);}
    public void equipment(EntityEquipment equipment,BodySnapshot state) {
        if(equipment==null)return;ItemStack[] armor=new ItemStack[4];for(int i=0;i<4;i++)armor[i]=items.item(state.inventory().get(36+i));
        equipment.setArmorContents(armor);equipment.setItemInMainHand(items.item(state.inventory().get(state.selected())));equipment.setItemInOffHand(items.item(state.inventory().get(40)));
        if(equipment.getHolder() instanceof Mob)for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET,EquipmentSlot.HAND,EquipmentSlot.OFF_HAND))equipment.setDropChance(slot,0);
    }
    public void vital(LivingEntity entity,BodySnapshot state) {
        entity.getActivePotionEffects().forEach(e->entity.removePotionEffect(e.getType()));
        for(var effect:state.effects()){var type=Registry.EFFECT.get(Objects.requireNonNull(NamespacedKey.fromString(effect.key())));if(type!=null)entity.addPotionEffect(new PotionEffect(type,effect.duration(),effect.amplifier(),effect.ambient(),effect.particles(),effect.icon()));}
        entity.setHealth(Math.min(Math.max(.01,state.health()),entity.getAttribute(Attribute.MAX_HEALTH).getValue()));entity.setAbsorptionAmount(state.absorption());entity.setFireTicks(state.fireTicks());entity.setRemainingAir(state.air());entity.setFallDistance(state.fallDistance());
    }
    /** Avoid applying Health Boost/equipment maximum-health modifiers twice on the surrogate. */
    public void carrierVitals(LivingEntity entity,BodySnapshot state) {
        vital(entity,state);var max=Objects.requireNonNull(entity.getAttribute(Attribute.MAX_HEALTH));
        double add=0,scalar=0,multiplier=1;
        for(var modifier:max.getModifiers())switch(modifier.getOperation()) {
            case ADD_NUMBER -> add+=modifier.getAmount();
            case ADD_SCALAR -> scalar+=modifier.getAmount();
            case MULTIPLY_SCALAR_1 -> multiplier*=1+modifier.getAmount();
        }
        double factor=(1+scalar)*multiplier;
        if(factor<=0)throw new IllegalStateException("Invalid maximum health modifiers");
        max.setBaseValue(state.maxHealth()/factor-add);
        entity.setHealth(Math.min(state.health(),max.getValue()));
    }
    public void restore(Player p,BodySnapshot state) {
        // Decode before the first destructive inventory write. The body remains authoritative until commit.
        ItemStack[] inventory=new ItemStack[41];state.inventory().forEach((slot,item)->inventory[slot]=items.item(item));ItemStack cursor=items.item(state.cursor());
        p.setItemOnCursor(null);p.closeInventory();p.getInventory().clear();p.getEnderChest().clear();p.setGameMode(GameMode.SURVIVAL);p.setFlying(false);p.setAllowFlight(false);
        if(!p.teleport(location(state.position())))throw new IllegalStateException("Reconnect teleport rejected");
        p.getInventory().setContents(inventory);p.getInventory().setHeldItemSlot(state.selected());p.setItemOnCursor(cursor);
        p.setLevel(state.level());p.setExp(state.progress());p.setTotalExperience(state.totalXp());p.setFoodLevel(state.food());p.setSaturation(state.saturation());vital(p,state);
    }
    public void quarantine(Player p){p.setItemOnCursor(null);p.closeInventory();p.getInventory().clear();p.getEnderChest().clear();p.setLevel(0);p.setExp(0);p.setTotalExperience(0);p.setGameMode(GameMode.SPECTATOR);}
}
