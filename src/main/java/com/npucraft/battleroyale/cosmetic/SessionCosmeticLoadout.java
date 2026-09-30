package com.npucraft.battleroyale.cosmetic;
import java.util.*;
/** Frozen identifiers; missing definitions resolve to safe builtin defaults without deleting ownership. */
public record SessionCosmeticLoadout(Map<CosmeticCategory,String> equipped) {
    public SessionCosmeticLoadout {
        var copy=new EnumMap<CosmeticCategory,String>(CosmeticCategory.class);copy.putAll(equipped);
        copy.remove(CosmeticCategory.LOBBY_EFFECT);equipped=Map.copyOf(copy);
    }
    public static SessionCosmeticLoadout empty(){return new SessionCosmeticLoadout(Map.of());}
}
