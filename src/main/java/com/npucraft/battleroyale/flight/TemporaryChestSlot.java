package com.npucraft.battleroyale.flight;

import java.util.*;

/** Original equipment stays in one authoritative lease, never inside a transferable temporary item. */
public final class TemporaryChestSlot {
    private final UUID owner,session;
    private byte[] original;
    public TemporaryChestSlot(UUID owner,UUID session,byte[] original){
        this.owner=Objects.requireNonNull(owner);this.session=Objects.requireNonNull(session);
        Objects.requireNonNull(original);if(original.length>4*1024*1024)throw new IllegalArgumentException("Chest item too large");
        this.original=original.clone();
    }
    public boolean active(){return original!=null;}
    public boolean belongsTo(UUID player,UUID match){return owner.equals(player)&&session.equals(match);}
    public Optional<byte[]> release(UUID player,UUID match){
        if(!belongsTo(player,match))throw new IllegalArgumentException("Temporary equipment owner mismatch");
        if(original==null)return Optional.empty();byte[] value=original;original=null;return Optional.of(value.clone());
    }
}