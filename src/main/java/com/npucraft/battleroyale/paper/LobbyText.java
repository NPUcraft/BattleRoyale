package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.I18n;
import java.util.*;
import org.bukkit.command.CommandSender;

/** Exact built-in labels only. Player names and arbitrary administrator text are never rewritten. */
public final class LobbyText {
    private record Label(String zh,String en) {}
    private static final Map<String,Label> DEFAULTS=new HashMap<>();
    static {
        label("单人竞技","Solo");label("双人组队","Duo");label("四人小队","Squad");
        label("大逃杀","BattleRoyale");label("出生点附近","Near spawn");
        for(var pair:List.of(new String[]{"选择房间","Select a room"},new String[]{"我的战绩","My stats"},new String[]{"排行榜","Leaderboards"},new String[]{"外观商店","Cosmetic shop"},new String[]{"我的外观","My cosmetics"})){
            label(pair[0],pair[1]);label("大逃杀 · "+pair[0],"BattleRoyale · "+pair[1]);
        }
        label("确认购买","Confirm purchase");label("快速加入","Quick join");
        label("右键查看单人、双人和四人房间","Right-click to view Solo, Duo and Squad rooms");
        label("右键加入可用房间","Right-click to join an available room");label("查看评分与永久统计","View your rating and lifetime stats");
        label("查看总榜和历史排行","View lifetime and historical rankings");label("永久解锁个性外观，不影响比赛属性","Unlock permanent cosmetics without gameplay advantages");
        label("翡翠绽放","Emerald burst");label("成功击杀时绽放一簇粒子。","A burst of particles after a kill.");
        label("幻影闪电","Phantom lightning");label("仅供观赏的无伤闪电。","Visual lightning without damage.");
        label("胜利烟花","Victory fireworks");label("为队伍胜利燃放烟花。","Fireworks to celebrate your team's victory.");
        label("末影物资箱","Ender deathbox");label("将死亡物资箱显示为末影箱。","Display your deathbox as an ender chest.");
        label("黄金物资箱","Golden deathbox");label("将死亡物资箱显示为金块。","Display your deathbox as a gold block.");
        label("大厅星光","Lobby starlight");label("在大厅中环绕身边的星光粒子。","Starlight particles around you in the lobby.");
    }
    private LobbyText(){}
    private static void label(String zh,String en,String...aliases){var value=new Label(zh,en);DEFAULTS.put(zh,value);DEFAULTS.put(en,value);for(String alias:aliases)DEFAULTS.put(alias,value);}
    public static Locale locale(CommandSender viewer){return I18n.locale(viewer);}
    public static String defaultLabel(CommandSender viewer,String value){return defaultLabel(locale(viewer),value);}
    public static String defaultLabel(Locale locale,String value){var known=DEFAULTS.get(value);return known==null?value:I18n.text(locale,known.zh(),known.en());}
}
