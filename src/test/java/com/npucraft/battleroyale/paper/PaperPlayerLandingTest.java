package com.npucraft.battleroyale.paper;

import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperPlayerLandingTest {
    private record Cell(Material material,List<BoundingBox> boxes) {}
    private final Map<String,Cell> cells=new HashMap<>();
    private final Set<Long> unloaded=new HashSet<>();
    private int reads;
    private final World world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class<?>[]{World.class},(proxy,method,args)->switch(method.getName()) {
        case "getMinHeight" -> -64;case "getMaxHeight" -> 320;
        case "isChunkLoaded" -> !unloaded.contains(((long)(int)args[0]<<32)^((int)args[1]&0xffffffffL));
        case "getBlockAt" -> block((int)args[0],(int)args[1],(int)args[2]);
        case "toString" -> "LandingWorld";default -> throw new AssertionError("Unexpected world API: "+method);
    });
    private Block block(int x,int y,int z) {
        reads++;var cell=cells.getOrDefault(x+":"+y+":"+z,new Cell(Material.AIR,List.of()));
        VoxelShape shape=(VoxelShape)Proxy.newProxyInstance(VoxelShape.class.getClassLoader(),new Class<?>[]{VoxelShape.class},(proxy,method,args)->switch(method.getName()) {
            case "getBoundingBoxes" -> cell.boxes();default -> throw new AssertionError("Unexpected shape API: "+method);
        });
        return (Block)Proxy.newProxyInstance(Block.class.getClassLoader(),new Class<?>[]{Block.class},(proxy,method,args)->switch(method.getName()) {
            case "getX" -> x;case "getY" -> y;case "getZ" -> z;case "getWorld" -> world;
            case "getType" -> cell.material();case "getCollisionShape" -> shape;case "getBlockData" -> null;
            case "isLiquid" -> cell.material()==Material.WATER||cell.material()==Material.LAVA;
            default -> throw new AssertionError("Unexpected block API: "+method);
        });
    }
    private void put(int x,int y,int z,Material material,BoundingBox... boxes){cells.put(x+":"+y+":"+z,new Cell(material,List.of(boxes)));}
    @Test void convertsLocalBoxesToWorldCoordinatesAndPlansFractionalFeet() {
        put(20,80,0,Material.STONE_SLAB,new BoundingBox(0,0,0,1,.5,1));
        assertTrue(PaperPlayerLanding.safeStanding(new Location(world,20.5,80.5,.5)));
        assertEquals(80.5,PaperPlayerLanding.safeSurface(block(20,80,0)));
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,20.5,81,.5)));
    }
    @Test void loadedOnlyChecksDoNotReadMissingNeighbourOrRequestChunks() {
        unloaded.add(1L<<32);
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,15.9,81,.5)));
        assertEquals(0,reads,"Check all footprint chunks before reading any blocks");
    }
    @Test void hazardousFloorAndHeadroomRemainRejected() {
        put(1,80,1,Material.MAGMA_BLOCK,new BoundingBox(0,0,0,1,1,1));
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,1.5,81,1.5)));
        put(1,80,1,Material.STONE,new BoundingBox(0,0,0,1,1,1));
        put(1,81,1,Material.FIRE);
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,1.5,81,1.5)));
        put(1,81,1,Material.AIR);put(1,82,1,Material.STONE,new BoundingBox(0,0,0,1,1,1));
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,1.5,81,1.5)));
    }
    @Test void boundsAndNonfiniteInputFailWithoutAnyBlockReads() {
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,Double.MAX_VALUE,81,0)));
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,0,319,0)));
        assertFalse(PaperPlayerLanding.safeStanding(new Location(world,0,Double.NaN,0)));
        assertEquals(0,reads);
    }
}
