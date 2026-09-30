package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.config.LobbySettings;
import java.util.*;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyBlueprintTest {
    private final Map<LobbyBlueprint.Position,Material> blocks=LobbyBlueprint.blocks().stream().collect(Collectors.toMap(LobbyBlueprint.Block::position,LobbyBlueprint.Block::material));
    private Material at(int x,int y,int z){return blocks.get(new LobbyBlueprint.Position(x,y,z));}
    @Test void everyMutationFitsTheAuthorizedEnvelopeWithNoDuplicatePositions(){
        var settings=LobbySettings.DEFAULT;
        assertEquals(blocks.size(),LobbyBlueprint.blocks().size());
        assertTrue(blocks.size()<65*65*14);
        for(var p:blocks.keySet())assertTrue(settings.within(settings.x()+p.x(),settings.y()+p.y(),settings.z()+p.z()),p.toString());
        assertFalse(settings.within(settings.x()+33,settings.y(),settings.z()));
        assertFalse(settings.within(settings.x(),settings.y()-4,settings.z()));
        assertFalse(settings.within(settings.x(),settings.y()+11,settings.z()));
    }
    @Test void spawnHasSolidFloorAndTwoClearBlocksForNearbyLanding(){
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
            assertNotNull(at(x,0,z));assertNotEquals(Material.AIR,at(x,0,z));
            assertEquals(Material.AIR,at(x,1,z));assertEquals(Material.AIR,at(x,2,z));
        }
    }
    @Test void eachRoomStandHasARoofSupportedByFourColumnsAndAccessibleFront(){
        for(int[] center:new int[][]{{-18,-16},{0,-22},{18,-16}}){
            int x=center[0],z=center[1];
            assertEquals(Material.LECTERN,at(x,2,z+3));
            assertEquals(Material.DARK_PRISMARINE,at(x,6,z+3));
            for(int dx:new int[]{-5,5})for(int dz:new int[]{-4,4})for(int y=1;y<=5;y++)assertEquals(Material.QUARTZ_PILLAR,at(x+dx,y,z+dz));
            assertEquals(Material.AIR,at(x,1,z+4));assertEquals(Material.AIR,at(x,2,z+4));
        }
        assertEquals(Material.LECTERN,at(0,2,7));
    }
    @Test void entireOctagonalEdgeHasAnUnbrokenGuardRail(){
        for(int x=-32;x<=32;x++)for(int z=-32;z<=32;z++){
            if(Math.abs(x)+Math.abs(z)>46)continue;
            int edge=Math.max(Math.max(Math.abs(x),Math.abs(z)),Math.abs(x)+Math.abs(z)-14);
            if(edge==32)assertTrue(Set.of(Material.WHITE_STAINED_GLASS,Material.POLISHED_DEEPSLATE).contains(at(x,1,z)),x+","+z);
        }
    }
}
