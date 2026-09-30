package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.LobbySettings;

/** Presentation settings changes do not retire entities; disabling or moving the owning lobby does. */
public final class LobbyBoardPlacement {
    private LobbyBoardPlacement(){}
    public static boolean retire(LobbySettings previous,LobbySettings next){
        return previous.buildEnabled()&&(!next.buildEnabled()||!previous.world().equals(next.world())
                ||previous.x()!=next.x()||previous.y()!=next.y()||previous.z()!=next.z());
    }
}
