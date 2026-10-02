package com.npucraft.battleroyale.probe;

import com.npucraft.battleroyale.admin.AtomicFiles;
import com.npucraft.battleroyale.combat.*;
import com.npucraft.battleroyale.paper.*;
import com.npucraft.battleroyale.service.*;
import com.npucraft.battleroyale.zone.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Public API rendering/serialization checks; no protocol client or fabricated client locale packet. */
public final class Rc8LocaleProbe {
    private final JavaPlugin plugin;
    private final YamlConfiguration report=new YamlConfiguration();
    public Rc8LocaleProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        require(Boolean.getBoolean("battleroyale.probe.rc8"),"Explicit isolated rc8 flag required");
        require(args.length==2&&args[1].equals("all"),"p26rc8locale all");
        require(plugin.getServer().getOnlinePlayers().isEmpty(),"No real players in locale fixture");
        World world=null;Throwable failure=null;
        try{
            languages();messages();matchUi();nativeItems();
            world=plugin.getServer().createWorld(WorldCreator.ofKey(new NamespacedKey("battleroyale_probe","rc8_locale_"+UUID.randomUUID().toString().replace("-",""))).type(WorldType.FLAT).generateStructures(false));
            require(world!=null,"Dedicated locale world created");
            shared(world);report.set("status","passed");
        }catch(Throwable error){failure=error;report.set("status","failed");report.set("failure",error.toString());}
        finally{if(world!=null)try{require(plugin.getServer().unloadWorld(world,true),"Locale world unloads");}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);report.set("status","failed");report.set("failure",failure.toString());}}
        try{
            report.set("platform",plugin.getServer().getVersion());
            report.set("limits",List.of("Player message checks use isolated Player interface stubs that expose locale and capture components; they do not claim a connected player.","Shared TextDisplay and barrel names are stored through native Paper APIs and rendered through the production GlobalTranslator.","Item names use Minecraft native client translation keys, which follow Minecraft client language rather than plugin server templates.","No real client packet/rendering, live language menu switch, or multiplayer interaction is asserted."));
            var file=plugin.getDataFolder().toPath().resolve("rc8/locale-report.yml");AtomicFiles.write(file,report.saveToString().getBytes(StandardCharsets.UTF_8));
            if(failure!=null)throw new IllegalStateException("Locale checks failed",failure);
            sender.sendMessage("RC8 LOCALE SUCCESS locale=OK messages=OK shared=OK items=OK path="+file.toAbsolutePath());
        }catch(Throwable error){sender.sendMessage("RC8 LOCALE FAILED "+error);plugin.getLogger().log(Level.SEVERE,"RC8 LOCALE FAILED",error);}
    }
    private void languages(){
        for(String tag:List.of("zh-CN","zh-TW","zh-HK","zh-SG","zh-Hant-TW"))require(I18n.chinese(Locale.forLanguageTag(tag)),"Chinese variant "+tag);
        for(Locale locale:List.of(Locale.ENGLISH,Locale.US,Locale.GERMAN,Locale.JAPANESE,Locale.ROOT,Locale.forLanguageTag("zz-ZZ")))require(!I18n.chinese(locale),"English fallback "+locale);
        require(!I18n.chinese((Locale)null),"Null locale falls back to English");
        String name="<red>玩家_%s";require(I18n.text(Locale.ENGLISH,"玩家 %s","Player %s",name).equals("Player "+name),"Literal argument is preserved");
        require(I18n.error(Locale.ENGLISH,"房间不存在："+name).equals("Unknown room: "+name),"Internal error only translates the known prefix");
        report.set("language.zh-variants",List.of("zh-CN","zh-TW","zh-HK","zh-SG","zh-Hant-TW"));report.set("language.other-and-unknown-english",true);report.set("language.arguments-literal",true);
    }
    private void messages(){
        var received=new ArrayList<Component>();Locale[] selected={Locale.ENGLISH};
        Player viewer=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            if(method.getName().equals("locale"))return selected[0];if(method.getName().equals("hasPermission"))return true;
            if(method.getName().equals("sendMessage")&&args!=null&&args.length==1&&args[0] instanceof Component value){received.add(value);return null;}
            if(method.getName().equals("getUniqueId"))return new UUID(0,8);return null;
        });
        var service=new MessageService(plugin.getLogger());var cases=new LinkedHashMap<String,Object[]>();
        cases.put("start-blocked",new Object[]{"Room is full"});cases.put("teams-assigned",new Object[]{4});cases.put("disconnected",new Object[]{"Tester",60});
        cases.put("reconnected",new Object[]{});cases.put("offline-eliminated",new Object[]{});cases.put("offline-timeout",new Object[]{"Tester"});
        cases.put("spectator-joined",new Object[]{"arena"});cases.put("spectator-left",new Object[]{});cases.put("joined",new Object[]{"arena"});cases.put("left",new Object[]{"arena"});
        cases.put("countdown-started",new Object[]{60});cases.put("countdown-cancelled",new Object[]{});cases.put("countdown-remaining",new Object[]{5});cases.put("preparing",new Object[]{"fixture"});
        cases.put("preparation-failed",new Object[]{"房间人数已满。"});cases.put("started",new Object[]{"fixture"});cases.put("protection-started",new Object[]{60});
        cases.put("protection-ended",new Object[]{});cases.put("ended",new Object[]{});cases.put("returned",new Object[]{});
        for(var entry:cases.entrySet()){
            selected[0]=Locale.GERMAN;service.event(viewer,entry.getKey(),entry.getValue());require(!han(plain(received.getLast())),"English runtime event "+entry.getKey());
            selected[0]=Locale.TRADITIONAL_CHINESE;service.event(viewer,entry.getKey(),entry.getValue());require(han(plain(received.getLast())),"Chinese runtime event "+entry.getKey());
        }
        selected[0]=Locale.ENGLISH;received.clear();service.help(viewer);service.denied(viewer);service.reloaded(viewer);service.unknown(viewer);
        require(received.stream().map(Rc8LocaleProbe::plain).noneMatch(Rc8LocaleProbe::han),"All help/permission/reload/unknown output is English");
        service.offlineResult(viewer.getUniqueId(),true);selected[0]=Locale.CHINESE;service.deliverOfflineResult(viewer);require(han(plain(received.getLast())),"Offline notice selects current locale at delivery");
        selected[0]=null;service.event(viewer,"joined","<red>玩家_%s");require(plain(received.getLast()).contains("Joined room <red>玩家_%s"),"Unknown viewer locale uses English without modifying user data");
        report.set("messages.runtime-events-per-language",cases.size());report.set("messages.help-and-errors",true);report.set("messages.locale-selected-at-delivery",true);
    }
    private void matchUi(){
        Zone current=new Zone(0,0,500),next=new Zone(50,0,400);var hint=ZoneNavigation.guide(current,next,600,0,0);
        require(plain(PaperZoneUi.navigationMessage(Locale.ENGLISH,hint,current,next,new ZoneNavigation.Point(10,20),600,0,0)).contains("Next zone edge"),"English next-zone navigation");
        require(plain(PaperZoneUi.navigationMessage(Locale.CHINESE,hint,current,next,null,600,0,0)).contains("下圈边界"),"Chinese next-zone navigation");
        var profile=new ZoneProfile("probe",List.of(new ZoneProfile.InitialSize(4,500)),List.of(new ZoneProfile.Stage(Duration.ofSeconds(60),Duration.ofSeconds(60),400,1,0,1)));
        var zone=new ZoneRuntime(current,profile,new Random(8),0);
        require(!han(plain(PaperZoneUi.bossbarMessage(Locale.ENGLISH,zone,4,1,4,false,0,0))),"English bossbar");
        require(han(plain(PaperZoneUi.bossbarMessage(Locale.CHINESE,zone,4,1,4,false,0,0))),"Chinese bossbar");
        require(plain(CelebrationEffects.returnStatus(Locale.ENGLISH,10)).contains("Lobby return: 10"),"English ending countdown");
        for(DamageOrigin origin:DamageOrigin.values()){
            var reason=new DeathReason(origin,Optional.empty(),Set.of(),true);
            require(!han(plain(DeathReasonRenderer.render(Locale.ENGLISH,reason,id->"Tester"))),"English death cause "+origin);
            require(han(plain(DeathReasonRenderer.render(Locale.CHINESE,reason,id->"Tester"))),"Chinese death cause "+origin);
        }
        report.set("match.navigation-bossbar-ending-deaths",true);
    }
    private void nativeItems(){
        var items=new NativeLootItems();int checked=0;
        for(String name:List.of("invisibility","fire_resistance","healing","harming","poison"))for(String prefix:List.of("","splash_")){
            String key="battleroyale:"+prefix+name+"_potion";
            if(name.equals("harming")&&prefix.isEmpty()){
                // A drinkable instant-damage potion only lets a player hurt itself, so production rejects the
                // non-splash harming preset on purpose (NativeLootItems.java:37-38). Assert that rejection.
                boolean rejected=false;try{items.resolve(key);}catch(IllegalArgumentException expected){rejected=true;}
                require(rejected,"Non-splash harming preset must be rejected");
                report.set("items.non-splash-harming-rejected",true);continue;
            }
            var item=items.resolve(key);var copy=ItemStack.deserializeBytes(item.serializeAsBytes());require(copy.isSimilar(item),"Potion roundtrip");
            Component title=Objects.requireNonNull(copy.getItemMeta().displayName());require(title instanceof TranslatableComponent tr&&tr.key().equals("item.minecraft."+(prefix.isEmpty()?"potion":"splash_potion")+".effect."+name),"Native potion translation key");nativeOnly(title);checked++;
        }
        for(ItemStack item:List.of(items.resolve("battleroyale:combat_firework"),new StoredExperienceBottles(plugin).create(73))){
            var copy=ItemStack.deserializeBytes(item.serializeAsBytes());require(copy.isSimilar(item),"Native item roundtrip");nativeOnly(Objects.requireNonNull(copy.getItemMeta().displayName()));
            if(copy.getItemMeta().lore()!=null)copy.getItemMeta().lore().forEach(Rc8LocaleProbe::nativeOnly);checked++;
        }
        report.set("items.native-localized-roundtrips",checked);report.set("items.no-server-only-translation-keys",true);
    }
    private void shared(World world){
        String literal="<red>玩家_%s";var source=I18n.shared("probe.rc8.label","玩家 {0}","Player {0}",UiText.value(literal)).color(UiText.BODY);
        var display=world.spawn(new Location(world,0,100,0),TextDisplay.class,entity->{entity.setPersistent(false);entity.text(source);});
        require(plain(GlobalTranslator.render(display.text(),Locale.CHINESE)).equals("玩家 "+literal),"Native TextDisplay Chinese rendering");
        Component english=GlobalTranslator.render(display.text(),Locale.JAPANESE);require(plain(english).equals("Player "+literal),"Native TextDisplay other-language English fallback");
        require(styled(english,literal),"Name style survives shared rendering");display.remove();
        var block=world.getBlockAt(0,99,0);block.setType(Material.BARREL,false);var barrel=(org.bukkit.block.Barrel)block.getState();
        barrel.customName(I18n.shared("probe.rc8.barrel","第 {0} 轮补给空投","Supply drop {0}",UiText.value("2")));barrel.update(true,false);
        var saved=(org.bukkit.block.Barrel)block.getState();require(plain(GlobalTranslator.render(Objects.requireNonNull(saved.customName()),Locale.ENGLISH)).equals("Supply drop 2"),"Native barrel stores shared inventory title");
        block.setType(Material.AIR,false);report.set("shared.native-display-and-barrel",true);report.set("shared.literal-name-and-style",true);
    }
    private static void nativeOnly(Component value){
        if(value instanceof TranslatableComponent tr){require(tr.key().startsWith("item.minecraft.")||tr.key().startsWith("block.minecraft."),"Only Minecraft-known item name keys");tr.arguments().forEach(arg->{if(arg.value() instanceof Component component)nativeOnly(component);});}
        if(value instanceof TextComponent text)require(!han(text.content()),"No fixed Chinese description on a shared native item");value.children().forEach(Rc8LocaleProbe::nativeOnly);
    }
    private static boolean styled(Component value,String name){return value instanceof TextComponent text&&text.content().equals(name)&&UiText.VALUE.equals(text.color())||value.children().stream().anyMatch(child->styled(child,name));}
    private static boolean han(String value){return value.codePoints().anyMatch(point->Character.UnicodeScript.of(point)==Character.UnicodeScript.HAN);}
    private static String plain(Component value){return PlainTextComponentSerializer.plainText().serialize(value);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
