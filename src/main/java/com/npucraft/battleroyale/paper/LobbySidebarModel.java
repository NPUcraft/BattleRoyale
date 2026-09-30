package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.I18n;
import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.session.GameState;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Immutable presentation inputs; this model does not access players, worlds or Bukkit scoreboards. */
public final class LobbySidebarModel {
    public static final int ROOMS_PER_PAGE=5, MAX_LINES=15;
    private LobbySidebarModel() {}
    public record RoomView(String id,String name,int players,int maxPlayers,int minPlayers,GameState state,int countdown){
        public RoomView {Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(state);if(players<0||maxPlayers<1||minPlayers<1)throw new IllegalArgumentException("Invalid room counts");}
    }
    public record Page(List<Component> lines,int number,int total){
        public Page {lines=List.copyOf(lines);if(lines.size()>MAX_LINES||number<1||number>total)throw new IllegalArgumentException("Invalid sidebar page");}
    }
    public record Audience(boolean ready,boolean sameWorld,boolean dead,boolean pendingRestore,boolean editor,boolean frozen,boolean spectator,GameState state,boolean lobbyEligible) {
        public Audience(boolean ready,boolean sameWorld,boolean dead,boolean pendingRestore,boolean editor,boolean frozen,boolean spectator,GameState state){this(ready,sameWorld,dead,pendingRestore,editor,frozen,spectator,state,false);}
    }
    public static boolean visible(Audience a){return a.ready()&&a.sameWorld()&&!a.dead()&&!a.pendingRestore()&&!a.editor()&&!a.frozen()&&!a.spectator()&&(a.state()==null||QueueExitPayload.queue(a.state())||a.lobbyEligible());}
    public static Page page(List<RoomView> rooms,int online,long elapsedSeconds,int pageSeconds){
        return page(rooms,online,elapsedSeconds,pageSeconds,Locale.CHINESE);
    }
    public static Page page(List<RoomView> rooms,int online,long elapsedSeconds,int pageSeconds,Locale locale){
        if(online<0||elapsedSeconds<0||pageSeconds<1)throw new IllegalArgumentException("Invalid sidebar clock/count");
        int total=Math.max(1,(rooms.size()+ROOMS_PER_PAGE-1)/ROOMS_PER_PAGE);
        int number=(int)((elapsedSeconds/pageSeconds)%total)+1;
        var lines=new ArrayList<Component>();
        lines.add(UiText.muted(I18n.text(locale,"在线：","Online: ")).append(UiText.value(Integer.toString(online))));
        int from=(number-1)*ROOMS_PER_PAGE,to=Math.min(from+ROOMS_PER_PAGE,rooms.size());
        if(rooms.isEmpty())lines.add(UiText.muted(I18n.text(locale,"暂无可用房间","No rooms available")));
        for(var room:rooms.subList(from,to)){
            String state=switch(room.state()){
                case WAITING->I18n.text(locale,"等待","Waiting");case COUNTDOWN->room.countdown()<0?I18n.text(locale,"倒计时","Starting"):room.countdown()+I18n.text(locale,"秒","s");
                case PREPARING,STARTING->I18n.text(locale,"准备","Preparing");case RUNNING->I18n.text(locale,"进行中","Playing");
                case ENDING->I18n.text(locale,"结算","Ending");case CLEANUP->I18n.text(locale,"清理","Cleanup");
            };
            lines.add(UiText.text(shortName(LobbyText.defaultLabel(locale,room.name())))
                    .append(UiText.value("  "+room.players()+"/"+room.maxPlayers())).append(UiText.muted(" · "))
                    .append(Component.text(state,stateColor(room.state())).decoration(TextDecoration.ITALIC,false)));
        }
        lines.add(UiText.muted(I18n.text(locale,"指南针 · 选择房间","Compass · Choose a room"))
                .append(total>1?UiText.value("  "+number+"/"+total):Component.empty()));
        return new Page(lines,number,total);
    }
    private static TextColor stateColor(GameState state){return switch(state){
        case WAITING->UiText.SUCCESS;case COUNTDOWN->UiText.VALUE;case PREPARING,STARTING->UiText.BRAND;
        case RUNNING->UiText.WARNING;case ENDING->NamedTextColor.LIGHT_PURPLE;case CLEANUP->UiText.MUTED;
    };}
    private static String shortName(String name){
        var text=new StringBuilder();name.codePoints().filter(code->!Character.isISOControl(code)).limit(24).forEach(text::appendCodePoint);
        if(name.codePoints().filter(code->!Character.isISOControl(code)).count()>24)text.append("…");return text.toString();
    }
}
