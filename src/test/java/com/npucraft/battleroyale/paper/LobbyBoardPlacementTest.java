package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.LobbySettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyBoardPlacementTest {
    private static LobbySettings settings(boolean enabled,String world,int x,int y,int z){return new LobbySettings(enabled,world,x,y,z,32,1200,false,true,false,true);}
    @Test void enabledSameOriginReloadPreservesPersistentEntities(){
        var original=settings(true,"lobby",0,200,0);assertFalse(LobbyBoardPlacement.retire(original,original));
        assertFalse(LobbyBoardPlacement.retire(original,new LobbySettings(true,"lobby",0,200,0,32,4000,false,false,true,false)));
        assertFalse(LobbyBoardPlacement.retire(settings(false,"lobby",0,200,0),original));
    }
    @Test void disablingOrMovingAnyOriginCoordinateRetiresOldEntities(){
        var original=settings(true,"lobby",0,200,0);
        for(var next:new LobbySettings[]{settings(false,"lobby",0,200,0),settings(true,"elsewhere",0,200,0),settings(true,"lobby",1,200,0),settings(true,"lobby",0,201,0),settings(true,"lobby",0,200,1)})assertTrue(LobbyBoardPlacement.retire(original,next));
    }
}
