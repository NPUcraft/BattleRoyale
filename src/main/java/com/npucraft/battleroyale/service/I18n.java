package com.npucraft.battleroyale.service;

import java.util.Locale;
import java.util.Objects;
import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Explicit presentation templates. User-provided arguments are never translated or parsed. */
public final class I18n {
    private I18n() {}
    private record Template(String zh,String en) {}
    // Persistent entities/block titles can be read before another instance is spawned after restart.
    private static final Map<String,Template> BUILT_INS=Map.ofEntries(
            entry("airdrop.container","第 {0} 轮补给空投","Supply drop {0}"),
            entry("death.inventory","死亡物资箱：{0}","Death supplies: {0}"),
            entry("death.survived","存活 {0}","Survived {0}"),
            entry("death.killed","被 {0} 淘汰（{1}）","Eliminated by {0} ({1})"),
            entry("death.environment","死于{0}","Died from {0}"),
            entry("offline.label"," [暂时离线]"," [Disconnected]"),
            entry("connection.join"," 加入了服务器"," joined the server"),
            entry("connection.quit"," 离开了服务器"," left the server"),
            entry("cause.player_melee","近战","melee"),entry("cause.projectile","投射物","a projectile"),
            entry("cause.explosion","爆炸","an explosion"),entry("cause.fire","火焰","fire"),
            entry("cause.lava","熔岩","lava"),entry("cause.fall","坠落","a fall"),
            entry("cause.zone","安全区外伤害","zone damage"),entry("cause.drowning","溺水","drowning"),
            entry("cause.void","虚空","the void"),entry("cause.mob","生物攻击","a creature attack"),
            entry("cause.magic","魔法","magic"),entry("cause.other","其他伤害","other damage"),
            entry("cause.disconnect_timeout","重连超时","reconnect timeout"),
            entry("cause.disconnect_body_failure","离线替身异常","offline body failure"));
    private static Map.Entry<String,Template> entry(String id,String zh,String en){return Map.entry("battleroyale."+id,new Template(zh,en));}
    private static final Map<String,Template> SHARED=new ConcurrentHashMap<>();
    private static final Translator TRANSLATOR=new Translator(){
        @Override public Key name(){return Key.key("battleroyale","messages");}
        @Override public MessageFormat translate(String key,Locale locale){var value=SHARED.get(key);return value==null?null:new MessageFormat(chinese(locale)?value.zh():value.en(),Locale.ROOT);}
    };
    public static void install(){BUILT_INS.forEach(SHARED::putIfAbsent);GlobalTranslator.translator().addSource(TRANSLATOR);}
    public static void close(){GlobalTranslator.translator().removeSource(TRANSLATOR);SHARED.clear();}
    /** Shared server-rendered names/titles only. ItemStack text must use native Minecraft keys. */
    public static Component shared(String id,String zh,String en,Component... arguments){
        install();
        String key="battleroyale."+id;var value=new Template(zh,en);var old=SHARED.putIfAbsent(key,value);
        if(old!=null&&!old.equals(value))throw new IllegalArgumentException("Conflicting localized template: "+id);
        return Component.translatable(key,arguments);
    }
    public static boolean chinese(Locale locale) { if(locale==null)return false;String language=locale.getLanguage().toLowerCase(Locale.ROOT);return language.equals("zh")||language.startsWith("zh_")||language.startsWith("zh-"); }
    /** Console output remains Chinese for existing operational scripts; unknown players use English. */
    public static boolean chinese(CommandSender viewer) { return viewer instanceof Player player ? chinese(player.locale()) : viewer != null; }
    public static Locale locale(CommandSender viewer) { return chinese(viewer) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH; }
    public static String text(CommandSender viewer,String zh,String en,Object... arguments) { return text(locale(viewer),zh,en,arguments); }
    public static String text(Locale locale,String zh,String en,Object... arguments) {
        String template=Objects.requireNonNull(chinese(locale)?zh:en);
        return arguments.length==0?template:String.format(Locale.ROOT,template,arguments);
    }
    public static String error(CommandSender viewer,String message) { return error(locale(viewer),message); }
    public static String error(Locale locale,String message) { return chinese(locale)?ChineseMessages.text(message):ChineseMessages.english(message); }
    public static String state(CommandSender viewer,Object state) { return state(locale(viewer),state); }
    public static String state(Locale locale,Object state) {
        if(chinese(locale))return ChineseMessages.state(state);
        return switch(String.valueOf(state)) {
            case "WAITING" -> "Waiting";case "COUNTDOWN" -> "Countdown";case "PREPARING" -> "Preparing";
            case "STARTING" -> "Starting";case "RUNNING" -> "In progress";case "ENDING" -> "Results";
            case "CLEANUP" -> "Cleaning up";case "ALIVE" -> "Alive";case "DISCONNECTED" -> "Disconnected";
            case "ELIMINATED" -> "Eliminated";case "SPECTATING" -> "Spectating";case "SHRINKING" -> "Shrinking";
            case "FINAL" -> "Final zone";case "EDITING" -> "Editing";case "VALIDATING_DEEP" -> "Deep validation";
            case "PREGENERATING" -> "Pregenerating";case "ERROR" -> "Error";case "WARNING", "WARN" -> "Warning";
            case "INFO" -> "Info";case "OK" -> "OK";case "AVAILABLE" -> "Available";default -> String.valueOf(state);
        };
    }
}
