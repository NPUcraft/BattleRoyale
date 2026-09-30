package com.npucraft.battleroyale.paper;
import com.npucraft.battleroyale.loot.LootItemResolver;
import com.npucraft.battleroyale.loot.AirdropEnchantmentTier;
import com.npucraft.battleroyale.service.UiText;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
public final class NativeLootItems implements LootItemResolver<ItemStack> {
    public static final double ENCHANT_CHANCE=.35;
    public static final double AIRDROP_ENCHANT_CHANCE=.80;
    private record Effect(PotionEffectType type,int ticks,String name,String duration,Color color){}
    private static final Map<String,Effect> POTIONS=Map.of(
            "invisibility",new Effect(PotionEffectType.INVISIBILITY,600,"隐身","30秒",Color.fromRGB(0xADB5C7)),
            "fire_resistance",new Effect(PotionEffectType.FIRE_RESISTANCE,1200,"防火","60秒",Color.fromRGB(0xFF9900)),
            "healing",new Effect(PotionEffectType.INSTANT_HEALTH,1,"治疗","瞬时",Color.fromRGB(0xF82423)),
            "harming",new Effect(PotionEffectType.INSTANT_DAMAGE,1,"伤害","瞬时",Color.fromRGB(0x430A09)),
            "poison",new Effect(PotionEffectType.POISON,160,"剧毒","8秒",Color.fromRGB(0x4E9331)));
    private static final List<Material> GUARANTEES=List.of(Material.DIAMOND_SWORD,Material.DIAMOND_AXE,Material.DIAMOND_HELMET,
            Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS,Material.BOW);
    private record Potion(Effect effect,boolean splash){}
    private Potion potion(String key){
        if(!key.startsWith("battleroyale:"))return null;
        String name=key.substring("battleroyale:".length());boolean splash=name.startsWith("splash_");
        if(splash)name=name.substring("splash_".length());
        if(!name.endsWith("_potion"))throw new IllegalArgumentException("Unknown native potion preset: "+key);
        var type=POTIONS.get(name.substring(0,name.length()-"_potion".length()));
        if(type==null)throw new IllegalArgumentException("Unknown native potion preset: "+key);
        return new Potion(type,splash);
    }
    private Material material(String key) {
        Objects.requireNonNull(key,"loot key");
        if (!key.startsWith("minecraft:")) throw new IllegalArgumentException("Unsupported loot provider: " + key);
        Material material = Material.matchMaterial(key);
        if (material == null || material.isAir() || !material.isItem()) throw new IllegalArgumentException("Unknown loot item: " + key);
        if(material==Material.ELYTRA)throw new IllegalArgumentException("Elytra is not allowed in BattleRoyale loot");
        return material;
    }
    @Override public void validate(String key) { Objects.requireNonNull(key,"loot key");if(key.equals("battleroyale:combat_firework"))return;if(potion(key)==null)material(key); }
    @Override public ItemStack resolve(String key) {
        Objects.requireNonNull(key,"loot key");if(key.equals("battleroyale:combat_firework"))return firework();var potion=potion(key);
        if(potion==null)return new ItemStack(material(key));
        var item=new ItemStack(potion.splash()?Material.SPLASH_POTION:Material.POTION);
        var effect=potion.effect();var meta=(PotionMeta)item.getItemMeta();
        // The water base contains no full-duration vanilla effect. Only this short level-I effect applies.
        meta.setBasePotionType(PotionType.WATER);meta.addCustomEffect(new PotionEffect(effect.type(),effect.ticks(),0),true);
        String preset=key.substring("battleroyale:".length()).replaceFirst("^splash_","");preset=preset.substring(0,preset.length()-"_potion".length());
        String nativeKey="item.minecraft."+(potion.splash()?"splash_potion":"potion")+".effect."+preset;
        meta.setColor(effect.color());meta.displayName(Component.translatable(nativeKey).color(UiText.VALUE)
                .append(Component.text(" I"+(effect.ticks()>1?" · "+String.format(Locale.ROOT,"%d:%02d",effect.ticks()/1200,effect.ticks()/20%60):"")))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        item.setItemMeta(meta);return item;
    }
    private ItemStack firework(){
        var item=new ItemStack(Material.FIREWORK_ROCKET);var meta=(FireworkMeta)item.getItemMeta();
        meta.setPower(0);meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(Color.fromRGB(0x46D9F7)).build());
        meta.displayName(Component.translatable("item.minecraft.firework_rocket").color(UiText.VALUE).append(Component.text(" · ★ × 1")).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));item.setItemMeta(meta);return item;
    }
    /** Ordinary equipment receives at most one applicable, non-curse level I/II enchantment. */
    public ItemStack roll(String key,RandomGenerator random){
        var item=resolve(key);if(random.nextDouble()<ENCHANT_CHANCE)enchant(item,random);return item;
    }
    public ItemStack rollAirdrop(String key,RandomGenerator random){
        var item=resolve(key);if(random.nextDouble()<AIRDROP_ENCHANT_CHANCE)enchantAirdrop(item,random);return item;
    }
    /** Exactly two amount-one stacks, independent of the random airdrop table and its capacity. */
    public List<ItemStack> airdropGuarantees(RandomGenerator random){
        var equipment=new ItemStack(GUARANTEES.get(random.nextInt(GUARANTEES.size())));
        if(!enchantAirdrop(equipment,random))throw new IllegalStateException("No applicable enchantment for guaranteed equipment "+equipment.getType());
        return List.of(equipment,new ItemStack(Material.TOTEM_OF_UNDYING));
    }
    private boolean enchantAirdrop(ItemStack item,RandomGenerator random){
        String type=item.getType().name();Enchantment primary;
        if(type.endsWith("_SWORD")||type.endsWith("_AXE"))primary=Enchantment.SHARPNESS;
        else if(type.endsWith("_HELMET")||type.endsWith("_CHESTPLATE")||type.endsWith("_LEGGINGS")||type.endsWith("_BOOTS"))primary=Enchantment.PROTECTION;
        else if(item.getType()==Material.BOW)primary=Enchantment.POWER;
        else if(item.getType()==Material.CROSSBOW)primary=Enchantment.QUICK_CHARGE;
        else if(item.getType()==Material.TRIDENT)primary=Enchantment.IMPALING;
        else if(item.getType()==Material.MACE)primary=Enchantment.DENSITY;
        else if(type.endsWith("_PICKAXE")||type.endsWith("_SHOVEL")||type.endsWith("_HOE"))primary=Enchantment.EFFICIENCY;
        else primary=Enchantment.UNBREAKING;
        if(!primary.canEnchantItem(item))return false;
        var tier=AirdropEnchantmentTier.roll(random);
        addCompatible(item,primary,tier.level(primary.getMaxLevel(),random));
        if(tier!=AirdropEnchantmentTier.ENHANCED)addCompatible(item,Enchantment.UNBREAKING,tier.level(Enchantment.UNBREAKING.getMaxLevel(),random));
        if(tier==AirdropEnchantmentTier.BEST){
            if(item.getType()==Material.BOW){addMaximum(item,Enchantment.FLAME,Enchantment.PUNCH,Enchantment.INFINITY);}
            else {
                addMaximum(item,Enchantment.MENDING);
                if(type.endsWith("_SWORD"))addMaximum(item,Enchantment.SWEEPING_EDGE,Enchantment.FIRE_ASPECT,Enchantment.LOOTING);
                if(type.endsWith("_AXE")||type.endsWith("_PICKAXE")||type.endsWith("_SHOVEL")||type.endsWith("_HOE"))addMaximum(item,Enchantment.EFFICIENCY,Enchantment.FORTUNE);
                if(type.endsWith("_HELMET"))addMaximum(item,Enchantment.RESPIRATION,Enchantment.AQUA_AFFINITY);
                if(type.endsWith("_BOOTS"))addMaximum(item,Enchantment.FEATHER_FALLING,Enchantment.DEPTH_STRIDER);
                if(primary==Enchantment.PROTECTION)addMaximum(item,Enchantment.THORNS);
                if(item.getType()==Material.CROSSBOW)addMaximum(item,Enchantment.MULTISHOT);
            }
        }
        return !item.getEnchantments().isEmpty();
    }
    private static void addMaximum(ItemStack item,Enchantment... choices){for(var enchantment:choices)addCompatible(item,enchantment,enchantment.getMaxLevel());}
    private static void addCompatible(ItemStack item,Enchantment enchantment,int level){
        if(!enchantment.canEnchantItem(item)||item.getEnchantments().keySet().stream().anyMatch(existing->existing!=enchantment&&(existing.conflictsWith(enchantment)||enchantment.conflictsWith(existing))))return;
        item.addEnchantment(enchantment,Math.clamp(level,1,enchantment.getMaxLevel()));
    }
    private boolean enchant(ItemStack item,RandomGenerator random){
        var choices=new ArrayList<Enchantment>();String type=item.getType().name();
        if(type.endsWith("_SWORD")||type.endsWith("_AXE"))choices.add(Enchantment.SHARPNESS);
        if(type.endsWith("_HELMET")||type.endsWith("_CHESTPLATE")||type.endsWith("_LEGGINGS")||type.endsWith("_BOOTS"))choices.add(Enchantment.PROTECTION);
        if(type.endsWith("_BOOTS"))choices.add(Enchantment.FEATHER_FALLING);
        if(item.getType()==Material.BOW)choices.add(Enchantment.POWER);
        if(item.getType()==Material.CROSSBOW)choices.add(Enchantment.QUICK_CHARGE);
        if(item.getType()==Material.TRIDENT)choices.add(Enchantment.IMPALING);
        if(item.getType()==Material.MACE)choices.add(Enchantment.DENSITY);
        if(type.endsWith("_PICKAXE")||type.endsWith("_SHOVEL")||type.endsWith("_HOE"))choices.add(Enchantment.EFFICIENCY);
        // The API applicability check excludes food, potions, books and other non-equipment items.
        choices.add(Enchantment.UNBREAKING);choices.removeIf(enchantment->!enchantment.canEnchantItem(item));
        if(choices.isEmpty())return false;
        var chosen=choices.get(random.nextInt(choices.size()));int maximum=Math.min(2,chosen.getMaxLevel());
        if(chosen==Enchantment.QUICK_CHARGE||chosen==Enchantment.DENSITY)maximum=1;
        item.addEnchantment(chosen,random.nextInt(1,maximum+1));return true;
    }
}
