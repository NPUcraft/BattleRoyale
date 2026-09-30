package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.LobbySidebarSettings;
import com.npucraft.battleroyale.service.UiText;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/** Server-thread-only per-player sidebar. It never mutates the server main scoreboard. */
public final class PaperLobbySidebar implements AutoCloseable {
    public static final String OBJECTIVE="br_lobby";
    private final ScoreboardManager manager;
    private final Consumer<Throwable> errors;
    private LobbySidebarSettings settings;
    private final Map<UUID,View> views=new HashMap<>();
    private final Set<UUID> suppressed=new HashSet<>();
    private boolean closed;
    private record View(Player player,Board board,SidebarLease<Scoreboard> lease) {}
    public PaperLobbySidebar(ScoreboardManager manager,LobbySidebarSettings settings,Consumer<Throwable> errors){
        this.manager=Objects.requireNonNull(manager);this.settings=Objects.requireNonNull(settings);this.errors=Objects.requireNonNull(errors);
    }
    public void configure(LobbySidebarSettings value){
        Objects.requireNonNull(value);if(settings.equals(value))return;
        // Keep takeover suppression across /br reload, including a takeover not yet observed by tick.
        for(var view:List.copyOf(views.values()))release(view.player(),false);
        settings=value;
    }
    public void update(Player player,boolean eligible,LobbySidebarModel.Page page){
        if(closed)return;
        UUID id=player.getUniqueId();
        if(!eligible){hide(player);return;}
        if(!settings.enabled()){suspend(player);return;}
        if(suppressed.contains(id))return;
        try{
            View view=views.get(id);
            if(view!=null&&!view.lease().mayUpdate(player.getScoreboard())){
                views.remove(id);suppressed.add(id);return;
            }
            if(view==null){
                var previous=player.getScoreboard();var board=new Board(manager,LobbyText.defaultLabel(player,settings.title()));board.render(page);
                view=new View(player,board,new SidebarLease<>(previous,board.scoreboard()));views.put(id,view);
                player.setScoreboard(board.scoreboard());
            }else {view.board().title(LobbyText.defaultLabel(player,settings.title()));view.board().render(page);}
        }catch(RuntimeException error){
            report(error);release(player,false);suppressed.add(id);
        }
    }
    public void hide(Player player){release(player,true);}
    /** A temporary readiness/config gate does not end the visit or revoke another plugin's takeover. */
    public void suspend(Player player){release(player,false);}
    private void release(Player player,boolean forgetSuppression){
        UUID id=player.getUniqueId();var view=views.remove(id);
        try{
            if(view!=null){
                var current=player.getScoreboard();
                if(!view.lease().mayUpdate(current)&&!forgetSuppression)suppressed.add(id);
                view.lease().restore(current).ifPresent(player::setScoreboard);
            }
        }catch(RuntimeException error){report(error);if(!forgetSuppression)suppressed.add(id);}
        finally{if(forgetSuppression)suppressed.remove(id);}
    }
    public void retain(Set<UUID> online){
        for(var view:List.copyOf(views.values()))if(!online.contains(view.player().getUniqueId()))hide(view.player());
        suppressed.retainAll(online);
    }
    private void report(Throwable error){try{errors.accept(error);}catch(RuntimeException ignored){/* One error reporter must not stop the lobby loop. */}}
    @Override public void close(){
        closed=true;for(var view:List.copyOf(views.values()))release(view.player(),true);views.clear();suppressed.clear();
    }
    /** Stable objective, teams and entries. Only changed prefixes or newly visible scores are sent. */
    public static final class Board {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final Team[] teams=new Team[LobbySidebarModel.MAX_LINES];
        private final String[] entries=new String[LobbySidebarModel.MAX_LINES];
        private final Component[] rendered=new Component[LobbySidebarModel.MAX_LINES];
        private final boolean[] visible=new boolean[LobbySidebarModel.MAX_LINES];
        public Board(ScoreboardManager manager,String title){
            scoreboard=manager.getNewScoreboard();objective=scoreboard.registerNewObjective(OBJECTIVE,Criteria.DUMMY,UiText.heading(title));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);objective.numberFormat(NumberFormat.blank());
            for(int i=0;i<teams.length;i++){
                entries[i]="\u00a7"+Integer.toHexString(i)+"\u00a7r";
                teams[i]=scoreboard.registerNewTeam("br_line_"+i);teams[i].addEntry(entries[i]);teams[i].prefix(Component.empty());teams[i].suffix(Component.empty());
            }
        }
        public Scoreboard scoreboard(){return scoreboard;}
        public void title(String title){var text=UiText.heading(title);if(!objective.displayName().equals(text))objective.displayName(text);}
        public void render(LobbySidebarModel.Page page){
            for(int i=0;i<teams.length;i++){
                if(i<page.lines().size()){
                    var line=page.lines().get(i);
                    if(!line.equals(rendered[i])){teams[i].prefix(line);rendered[i]=line;}
                    if(!visible[i]){objective.getScore(entries[i]).setScore(teams.length-i);visible[i]=true;}
                }else if(visible[i]){scoreboard.resetScores(entries[i]);visible[i]=false;}
            }
        }
    }
}
