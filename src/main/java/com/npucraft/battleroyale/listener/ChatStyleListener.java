package com.npucraft.battleroyale.listener;

import com.npucraft.battleroyale.service.UiText;
import com.npucraft.battleroyale.service.I18n;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Presentation only: preserve chat content, recipients, cancellation and signed-message handling. */
public final class ChatStyleListener implements Listener {
    private final boolean chat, connections;
    public ChatStyleListener(JavaPlugin plugin) {
        chat=plugin.getConfig().getBoolean("chat.format-enabled",true);
        connections=plugin.getConfig().getBoolean("chat.connection-messages",true);
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        if(!chat)return;
        // Only operate on renderer arguments; no asynchronous world/session/player-state access.
        // A dedicated chat plugin can replace this renderer at a later event priority.
        event.renderer(ChatRenderer.viewerUnaware((source,name,message)->Component.empty()
                .append(name.color(UiText.BRAND).decoration(TextDecoration.BOLD,true))
                .append(Component.text(" » ",UiText.VALUE))
                .append(message.colorIfAbsent(UiText.BODY))));
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void joined(PlayerJoinEvent event) {
        if(connections&&event.joinMessage()!=null)event.joinMessage(Component.empty()
                .append(Component.text("＋ ",UiText.SUCCESS))
                .append(Component.text(event.getPlayer().getName(),UiText.BRAND))
                .append(I18n.shared("connection.join"," 加入了服务器"," joined the server").color(UiText.MUTED)));
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        if(connections&&event.quitMessage()!=null)event.quitMessage(Component.empty()
                .append(Component.text("－ ",UiText.WARNING))
                .append(Component.text(event.getPlayer().getName(),UiText.BRAND))
                .append(I18n.shared("connection.quit"," 离开了服务器"," left the server").color(UiText.MUTED)));
    }
}
