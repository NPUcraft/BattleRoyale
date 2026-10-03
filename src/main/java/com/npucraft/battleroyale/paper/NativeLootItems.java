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
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
public final class NativeLootItems implements LootItemResolver<ItemStack> {
    public static final double ENCHANT_CHANCE=.35;
    public static final double AIRDROP_ENCHANT_CHANCE=.55;
    private record Effect(PotionEffectType type,int ticks,String name,String duration,Color color){}
    private static final Map<String,Effect> POTIONS=Map.of(
            "invisibility",new Effect(PotionEffectType.INVISIBILITY,600,"隐身","30秒",Color.fromRGB(0xADB5C7)),
            "fire_resistance",new Effect(PotionEffectType.FIRE_RESISTANCE,1200,"防火","60秒",Color.fromRGB(0xFF9900)),
            "healing",new Effect(PotionEffectType.INSTANT_HEALTH,1,"治疗","瞬时",Color.fromRGB(0xF82423)),
            "harming",new Effect(PotionEffectType.INSTANT_DAMAGE,1,"伤害","瞬时",Color.fromRGB(0x430A09)),
            "poison",new Effect(PotionEffectType.POISON,160,"剧毒","8秒",Color.fromRGB(0x4E9331)));
    private static final List<Material> GUARANTEES=List.of(Material.DIAMOND_SWORD,Material.DIAMOND_AXE,Material.DIAMOND_HELMET,
            Material.DIAMOND_CHESTPLATE,Material.DIAMOND_LEGGINGS,Material.DIAMOND_BOOTS,Material.BOW);
    /** Early rounds must only outpace mid-tier ground, so their guaranteed piece stays iron. */
    private static final List<Material> IRON_GUARANTEES=List.of(Material.IRON_SWORD,Material.IRON_AXE,Material.IRON_HELMET,
            Material.IRON_CHESTPLATE,Material.IRON_LEGGINGS,Material.IRON_BOOTS,Material.BOW);
    private static final List<Material> MIXED_GUARANTEES=List.of(Material.IRON_SWORD,Material.IRON_AXE,Material.IRON_CHESTPLATE,
            Material.IRON_LEGGINGS,Material.BOW,Material.DIAMOND_HELMET,Material.DIAMOND_SWORD);
    private record Potion(Effect effect,boolean splash){}
    private Potion potion(String key){
        if(!key.startsWith("battleroyale:"))return null;
        String name=key.substring("battleroyale:".length());boolean splash=name.startsWith("splash_");
        if(splash)name=name.substring("splash_".length());
        if(!name.endsWith("_potion"))throw new IllegalArgumentException("Unknown native potion preset: "+key);
        String preset=name.substring(0,name.length()-"_potion".length());
        // Harming is attack-only: a drinkable instant-damage potion only lets a player hurt itself.
        if(!splash&&preset.equals("harming"))throw new IllegalArgumentException("Unknown native potion preset: "+key);
        var type=POTIONS.get(preset);
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
    @Override public void validate(String key) { Objects.requireNonNull(key,"loot key");if(key.equals("battleroyale:combat_firework")||key.equals("battleroyale:knockback_stick")
            ||key.equals("battleroyale:slowness_arrow")||key.equals("battleroyale:levitation_arrow")
            ||key.equals("battleroyale:throwing_torch")||key.equals("battleroyale:sneakers")||key.equals("battleroyale:signal_gun")
            ||key.equals("battleroyale:mystery_food")||key.equals("battleroyale:coin_100")||key.equals("battleroyale:coin_1000"))return;if(potion(key)==null)material(key); }
    @Override public ItemStack resolve(String key) {
        Objects.requireNonNull(key,"loot key");if(key.equals("battleroyale:combat_firework"))return firework();
        if(key.equals("battleroyale:knockback_stick"))return knockbackStick();
        if(key.equals("battleroyale:slowness_arrow"))return arrow(PotionEffectType.SLOWNESS,220,"迟缓药水箭 · Slowness (11s)",Color.fromRGB(0x7CAFC6));
        if(key.equals("battleroyale:levitation_arrow"))return arrow(PotionEffectType.LEVITATION,100,"飘浮药水箭 · Levitation (5s)",Color.fromRGB(0xF4F4B3));
        if(key.equals("battleroyale:throwing_torch"))return throwingTorch();
        if(key.equals("battleroyale:sneakers"))return sneakers();
        if(key.equals("battleroyale:signal_gun"))return signalGun();
        if(key.equals("battleroyale:mystery_food"))return mysteryFood();
        if(key.equals("battleroyale:coin_100"))return coin(100);
        if(key.equals("battleroyale:coin_1000"))return coin(1000);
        var potion=potion(key);
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
    /** The meme melee: an unbreakable stick with knockback V that launches players instead of hurting them. */
    private ItemStack knockbackStick(){
        var item=new ItemStack(Material.STICK);var meta=item.getItemMeta();
        meta.addEnchant(Enchantment.KNOCKBACK,5,true);
        meta.displayName(Component.text("击退棒 · Knockback V").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);return item;
    }
    /** Single tipped arrows with effects vanilla loot cannot produce (slowness, levitation). */
    private ItemStack arrow(PotionEffectType effect,int ticks,String label,Color color){
        var item=new ItemStack(Material.TIPPED_ARROW);var meta=(PotionMeta)item.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);meta.addCustomEffect(new PotionEffect(effect,ticks,0),true);
        meta.setColor(color);
        meta.displayName(Component.text(label).color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        item.setItemMeta(meta);return item;
    }
    /** Throwable torch launched like a fire charge; the glint marks it as a special ordnance item. */
    private ItemStack throwingTorch(){
        var item=new ItemStack(Material.TORCH);var meta=item.getItemMeta();
        meta.displayName(Component.text("投掷火把 · Throwing Torch").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);
        com.npucraft.battleroyale.listener.PaperFunItems.mark(item,com.npucraft.battleroyale.listener.PaperFunItems.TORCH);return item;
    }
    /** Zero-armour golden boots granting permanent Speed I while worn; the glint separates them from loot gold boots. */
    private ItemStack sneakers(){
        var item=new ItemStack(Material.GOLDEN_BOOTS);var meta=item.getItemMeta();
        meta.displayName(Component.text("疾风之履 · Speed I").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.addAttributeModifier(Attribute.ARMOR,new AttributeModifier(NamespacedKey.minecraft("zero_armor"),0,
                AttributeModifier.Operation.ADD_NUMBER,EquipmentSlotGroup.FEET));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS,new AttributeModifier(NamespacedKey.minecraft("zero_toughness"),0,
                AttributeModifier.Operation.ADD_NUMBER,EquipmentSlotGroup.FEET));
        meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);
        com.npucraft.battleroyale.listener.PaperFunItems.mark(item,com.npucraft.battleroyale.listener.PaperFunItems.SNEAKERS);return item;
    }
    /** One-shot flare gun: use anywhere to summon a supply drop descending onto your position. */
    private ItemStack signalGun(){
        var item=new ItemStack(Material.NETHER_STAR);var meta=item.getItemMeta();
        meta.displayName(Component.text("信号枪 · Signal Gun").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);
        com.npucraft.battleroyale.listener.PaperFunItems.mark(item,com.npucraft.battleroyale.listener.PaperFunItems.SIGNAL_GUN);return item;
    }
    /** Cursed feast: eight buffs for a minute, a wither roar for everyone, then death. */
    private ItemStack mysteryFood(){
        var item=new ItemStack(Material.SUSPICIOUS_STEW);var meta=item.getItemMeta();
        meta.displayName(Component.text("神秘食物 · 禁忌盛宴").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        var lore=new java.util.ArrayList<Component>();
        lore.add(Component.text("食用后获得一段时间的大量效果").color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        lore.add(Component.text("……然后你会死。全服都会听见凋零的咆哮。").color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);
        com.npucraft.battleroyale.listener.PaperFunItems.mark(item,com.npucraft.battleroyale.listener.PaperFunItems.MYSTERY_FOOD);return item;
    }
    /** Trial economy voucher: an emerald token whose face value deposits on either click. */
    private ItemStack coin(long amount){
        var item=new ItemStack(Material.EMERALD);var meta=item.getItemMeta();
        boolean vault=amount>=1000;
        meta.displayName(Component.text(vault?"佣金币 · 空投储备":"佣金币 · 战场钱袋").color(UiText.VALUE)
                .append(Component.text(" ×"+amount)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        var lore=new java.util.ArrayList<Component>();
        lore.add(Component.text("左键或右键使用：存入账户 +"+amount).color(UiText.VALUE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        lore.add(Component.text(vault?"空投专属储备，价值连城。":"战场补给中拾获的钱袋。").color(net.kyori.adventure.text.format.NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
        meta.lore(lore);meta.setEnchantmentGlintOverride(true);item.setItemMeta(meta);
        com.npucraft.battleroyale.listener.PaperFunItems.mark(item,vault?com.npucraft.battleroyale.listener.PaperFunItems.COIN_1000:com.npucraft.battleroyale.listener.PaperFunItems.COIN_100);
        return item;
    }
    /** Ordinary equipment receives at most one applicable, non-curse level I/II enchantment. */
    public ItemStack roll(String key,RandomGenerator random){
        var item=resolve(key);if(random.nextDouble()<ENCHANT_CHANCE)enchant(item,random);return item;
    }
    public ItemStack rollAirdrop(String key,RandomGenerator random){
        var item=resolve(key);if(random.nextDouble()<AIRDROP_ENCHANT_CHANCE)enchantAirdrop(item,random);return item;
    }
    /** Exactly one enchanted equipment stack; totems are ordinary low-weight table rolls, never guaranteed. */
    public List<ItemStack> airdropGuarantees(RandomGenerator random){return airdropGuarantees(random,2);}
    /** tier 0 = iron only, 1 = iron with a diamond pinch, 2 = the full diamond pool. */
    public List<ItemStack> airdropGuarantees(RandomGenerator random,int tier){
        var pool=switch(Math.max(0,Math.min(2,tier))){case 0->IRON_GUARANTEES;case 1->MIXED_GUARANTEES;default->GUARANTEES;};
        var equipment=new ItemStack(pool.get(random.nextInt(pool.size())));
        if(!enchantAirdrop(equipment,random))throw new IllegalStateException("No applicable enchantment for guaranteed equipment "+equipment.getType());
        return List.of(equipment);
    }
    private boolean enchantAirdrop(ItemStack item,RandomGenerator random){
        String type=item.getType().name();Enchantment primary;
        if(type.endsWith("_SWORD")||type.endsWith("_AXE")||type.endsWith("_SPEAR"))primary=Enchantment.SHARPNESS;
        else if(type.endsWith("_HELMET")||type.endsWith("_CHESTPLATE")||type.endsWith("_LEGGINGS")||type.endsWith("_BOOTS"))primary=Enchantment.PROTECTION;
        else if(item.getType()==Material.BOW)primary=Enchantment.POWER;
        else if(item.getType()==Material.CROSSBOW)primary=Enchantment.QUICK_CHARGE;
        else if(item.getType()==Material.TRIDENT)primary=Enchantment.LOYALTY;
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
                if(type.endsWith("_SWORD")||type.endsWith("_SPEAR"))addMaximum(item,Enchantment.SWEEPING_EDGE,Enchantment.FIRE_ASPECT,Enchantment.LOOTING);
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
        if(type.endsWith("_SWORD")||type.endsWith("_AXE")||type.endsWith("_SPEAR"))choices.add(Enchantment.SHARPNESS);
        if(type.endsWith("_HELMET")||type.endsWith("_CHESTPLATE")||type.endsWith("_LEGGINGS")||type.endsWith("_BOOTS"))choices.add(Enchantment.PROTECTION);
        if(type.endsWith("_BOOTS"))choices.add(Enchantment.FEATHER_FALLING);
        if(type.endsWith("_SPEAR"))choices.add(Enchantment.KNOCKBACK);
        if(item.getType()==Material.CROSSBOW)choices.add(Enchantment.QUICK_CHARGE);
        // Impaling only boosts hits in water or rain, which this land-based clear-weather mode never has;
        // Loyalty keeps a thrown trident recoverable, so it is the meaningful primary here.
        if(item.getType()==Material.TRIDENT)choices.add(Enchantment.LOYALTY);
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
