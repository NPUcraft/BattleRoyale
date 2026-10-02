package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.ZoneUiSettings;
import com.npucraft.battleroyale.zone.*;
import org.bukkit.Server;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import java.util.*;

/** Per-match shared world border pushed with {@link Player#setWorldBorder} only; the server world border is never touched.
 *  The shown square is deliberately larger than the ZoneDamage square so the wall is felt before the damage edge. */
public final class PaperZoneBorder implements AutoCloseable {
    /** Extra blocks past the damage square: the wall can never hide or replace the ZoneDamage boundary. */
    static final double MARGIN=2;
    /** Below this the square is considered unchanged, so a waiting circle pushes no updates at all. */
    static final double EPSILON=.01;
    private final Server server;
    private final WorldBorder border;
    private final Set<UUID> viewers=new HashSet<>();
    private double lastSize=Double.NaN,lastX=Double.NaN,lastZ=Double.NaN;
    private final boolean enabled;
    public PaperZoneBorder(Server server,ZoneUiSettings settings) {
        this.server=server; this.enabled=settings.worldBorderEnabled();
        if(!enabled){border=null;return;}
        border=server.createWorldBorder();
        border.setWarningTimeTicks(0); border.setWarningDistance(0);
        // ZoneDamage stays the single damage source; the border must never add a second one.
        border.setDamageAmount(0); border.setDamageBuffer(0);
    }
    public boolean enabled(){return enabled;}
    public int size(){return viewers.size();}
    public WorldBorder border(){return border;}
    /** Subscribe a live combatant and track the interpolated safe square with the absolute-size API only. */
    public void apply(Player player,ZoneRuntime zone) { apply(player,zone.current()); }
    /** Static-square overload used before RUNNING (flight deployment shows the initial square). */
    public void apply(Player player,Zone current) {
        if(!enabled)return;
        // The final collapse has no safe square; the server border is the only sane fallback.
        if(current.halfSize()<=0){restore(player.getUniqueId());return;}
        double size=2*(current.halfSize()+MARGIN);
        if(!Double.isFinite(lastSize) || Math.abs(size-lastSize)>=EPSILON
                || Math.abs(current.centerX()-lastX)>=EPSILON || Math.abs(current.centerZ()-lastZ)>=EPSILON){
            border.setCenter(current.centerX(),current.centerZ());
            border.setSize(size); lastSize=size; lastX=current.centerX(); lastZ=current.centerZ();
        }
        if(viewers.add(player.getUniqueId()))player.setWorldBorder(border);
    }
    /** Restore one player to the server's own border; repeatable and safe after the player left the match world. */
    public void restore(UUID id){if(!enabled||!viewers.remove(id))return;Player player=server.getPlayer(id);if(player!=null)player.setWorldBorder(null);}
    /** Every exit path must reach this before the player can see the lobby. */
    public void restoreAll(){if(!enabled)return;for(UUID id:List.copyOf(viewers))restore(id);}
    @Override public void close(){restoreAll();}
}
