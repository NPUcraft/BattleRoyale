package com.npucraft.battleroyale.cosmetic;
import java.math.BigDecimal;
import java.util.*;
public record CosmeticDefinition(String id,CosmeticCategory category,String displayName,List<String> description,
                                 String icon,BigDecimal price,String effectType,Map<String,String> effectConfig) {
    public CosmeticDefinition {
        Objects.requireNonNull(id);Objects.requireNonNull(category);Objects.requireNonNull(displayName);
        Objects.requireNonNull(icon);Objects.requireNonNull(price);Objects.requireNonNull(effectType);
        description=List.copyOf(description);effectConfig=Map.copyOf(effectConfig);
        if(!id.matches("[a-z0-9_]{1,128}") || price.signum()<0 || price.precision()>32 || Math.abs((long)price.scale())>8)
            throw new IllegalArgumentException("Invalid cosmetic id or price");
        var allowed=switch(category) {
            case KILL_EFFECT -> Set.of("particle_burst","visual_lightning","firework_burst");
            case WIN_EFFECT -> Set.of("firework_burst");
            case DEATHBOX_SKIN -> Set.of("block_display");
            case LOBBY_EFFECT -> Set.of("particle_burst");
        };
        if(!allowed.contains(effectType))throw new IllegalArgumentException("Effect is invalid for category");
    }
}
