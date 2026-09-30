package com.npucraft.battleroyale.spectator;
import com.npucraft.battleroyale.session.GameSession;
import java.util.*;
public final class SpectatorRegistry {
    public enum Kind { DEAD_PARTICIPANT,EXTERNAL }
    public record Presence(UUID player,UUID session,UUID world,UUID snapshotOwner,Kind kind) {}
    private final Map<UUID,Presence> entries=new HashMap<>();
    public void add(Presence presence){if(entries.putIfAbsent(presence.player(),presence)!=null)throw new IllegalStateException("Already spectating");}
    public Optional<Presence> find(UUID player){return Optional.ofNullable(entries.get(player));}
    public Presence remove(UUID player){return entries.remove(player);}
    public List<Presence> session(UUID id){return entries.values().stream().filter(p->p.session().equals(id)).toList();}
    public boolean target(UUID viewer,GameSession session,UUID target){var p=entries.get(viewer);return p!=null && p.session().equals(session.sessionId()) && session.combatActive(target);}
    public Optional<UUID> preferred(GameSession session,UUID viewer){return session.players().keySet().stream().filter(session::combatActive)
            .sorted(Comparator.<UUID>comparingInt(id->session.sameTeam(viewer,id)?0:1).thenComparing(UUID::toString)).findFirst();}
    public int size(){return entries.size();}
    public boolean empty(){return entries.isEmpty();}
}
