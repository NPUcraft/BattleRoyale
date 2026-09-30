package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.progression.LobbyRankingRow;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

/** Literal Components only: empty or unavailable data never becomes an invented ranking. */
public final class LobbyRankingModel {
    public enum Status { LOADING,READY,STALE,UNAVAILABLE }
    public static final int MAX_ROWS=8;
    private LobbyRankingModel(){}
    public static Component render(List<LobbyRankingRow> rows,Status status){
        return render(rows,status,Locale.CHINESE);
    }
    public static Component render(List<LobbyRankingRow> rows,Status status,Locale locale){
        Component text=Component.text(I18n.text(locale,"玩家荣誉榜","Player rankings"),UiText.BRAND,TextDecoration.BOLD)
                .appendNewline().append(Component.text(I18n.text(locale,"Rating 总榜 · TOP 8","Lifetime rating · TOP 8"),UiText.VALUE).decoration(TextDecoration.BOLD,false));
        if(rows.isEmpty()){
            String empty=switch(status){case LOADING->I18n.text(locale,"正在读取玩家数据……","Loading player stats…");case UNAVAILABLE,STALE->I18n.text(locale,"数据暂不可用，稍后自动重试","Stats unavailable; retrying shortly");case READY->I18n.text(locale,"暂无玩家数据","No player stats yet");};
            text=text.appendNewline().append(Component.text(empty,UiText.MUTED).decoration(TextDecoration.BOLD,false));
        }else for(int i=0;i<Math.min(MAX_ROWS,rows.size());i++){
            var row=rows.get(i);TextColor rank=switch(i){case 0->NamedTextColor.GOLD;case 1->NamedTextColor.WHITE;case 2->NamedTextColor.YELLOW;default->UiText.BRAND;};
            text=text.appendNewline().append(Component.text((i+1)+". "+name(row.name()),rank).decoration(TextDecoration.BOLD,false))
                    .append(Component.text("  Rating ",UiText.MUTED)).append(Component.text(Integer.toString(row.rating()),UiText.VALUE))
                    .append(Component.text(I18n.text(locale,"  击杀 ","  Kills "),UiText.MUTED)).append(Component.text(Long.toString(row.kills()),UiText.SUCCESS))
                    .append(Component.text(I18n.text(locale,"  胜场 ","  Wins "),UiText.MUTED)).append(Component.text(Long.toString(row.wins()),UiText.VALUE));
        }
        if(status==Status.STALE&&!rows.isEmpty())text=text.appendNewline().append(Component.text(I18n.text(locale,"暂未刷新 · 保留最近结果","Refresh pending · showing latest results"),UiText.WARNING).decoration(TextDecoration.BOLD,false));
        return text.appendNewline().append(Component.text(I18n.text(locale,"约 30 秒更新 · 仅展示服务器真实数据","Updates every 30s · real server stats only"),UiText.MUTED).decoration(TextDecoration.BOLD,false))
                .appendNewline().append(Component.text(I18n.text(locale,"右键下方讲台 · 查看我的数据","Right-click the lectern · My stats"),UiText.VALUE).decoration(TextDecoration.BOLD,false))
                .decoration(TextDecoration.ITALIC,false);
    }
    private static String name(String value){StringBuilder result=new StringBuilder();value.codePoints().filter(code->!Character.isISOControl(code)).limit(24).forEach(result::appendCodePoint);return result.toString();}
}
