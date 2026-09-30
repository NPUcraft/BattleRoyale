package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.service.I18n;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Two persistent language variants, never one entity per player. All operations run on the server thread. */
public final class LobbyDisplayAudience implements Listener,AutoCloseable {
    public static final String LANGUAGE_KEY="lobby_display_language";
    private record Display(Entity entity,boolean chinese,Map<UUID,Boolean> visible) {}
    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final Predicate<Entity> owned;
    private final Map<UUID,Display> displays=new HashMap<>();
    private boolean closed;
    public LobbyDisplayAudience(JavaPlugin plugin,Predicate<Entity> owned){
        this.plugin=plugin;this.owned=owned;key=new NamespacedKey(plugin,LANGUAGE_KEY);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void track(Entity entity,boolean chinese){
        if(closed)return;
        entity.setVisibleByDefault(false);
        entity.getPersistentDataContainer().set(key,PersistentDataType.STRING,chinese?"zh":"en");
        var old=displays.get(entity.getUniqueId());
        if(old==null||old.entity()!=entity||old.chinese()!=chinese)displays.put(entity.getUniqueId(),new Display(entity,chinese,new HashMap<>()));
        for(var player:plugin.getServer().getOnlinePlayers())update(player);
    }
    public static boolean visible(boolean chinese,Player viewer,Entity entity){return I18n.chinese(viewer)==chinese&&viewer.getWorld().equals(entity.getWorld());}
    public void tick(){
        if(closed)return;
        var players=plugin.getServer().getOnlinePlayers();var online=new HashSet<UUID>();
        for(var player:players)online.add(player.getUniqueId());
        displays.values().removeIf(value->!value.entity().isValid());
        for(var value:displays.values())value.visible().keySet().retainAll(online);
        for(var player:players)update(player);
    }
    public void update(Player player){
        if(closed)return;
        for(var value:displays.values()){
            if(!value.entity().isValid())continue;
            boolean show=visible(value.chinese(),player,value.entity());
            Boolean before=value.visible().put(player.getUniqueId(),show);
            if(before==null||before!=show){if(show)player.showEntity(plugin,value.entity());else player.hideEntity(plugin,value.entity());}
        }
    }
    @EventHandler public void loaded(EntitiesLoadEvent event){
        for(var entity:event.getEntities())if(owned.test(entity)){
            String language=entity.getPersistentDataContainer().get(key,PersistentDataType.STRING);
            if("zh".equals(language)||"en".equals(language))track(entity,"zh".equals(language));
        }
    }
    @EventHandler public void unloaded(EntitiesUnloadEvent event){for(var entity:event.getEntities())displays.remove(entity.getUniqueId());}
    @Override public void close(){
        if(closed)return;closed=true;HandlerList.unregisterAll(this);
        for(var player:plugin.getServer().getOnlinePlayers())for(var value:displays.values())if(value.entity().isValid())player.hideEntity(plugin,value.entity());
        displays.clear();
    }
}
