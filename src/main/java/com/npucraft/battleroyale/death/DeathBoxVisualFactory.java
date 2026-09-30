package com.npucraft.battleroyale.death;
import net.kyori.adventure.text.Component;
import java.util.*;
public interface DeathBoxVisualFactory {
    List<UUID> spawn(DeathBox box,Component text);
    void remove(Collection<UUID> entities);
}
