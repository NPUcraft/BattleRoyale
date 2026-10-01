package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.flight.PlayerLandingGeometry;
import com.npucraft.battleroyale.flight.PlayerLandingGeometry.Box;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

/** Player-only landing geometry. Item drops and loot retain their separate conservative ground policy. */
public final class PaperPlayerLanding {
    private PaperPlayerLanding() {}
    /** At most 2 x 2 x 5 already loaded blocks; never synchronously requests a neighbouring chunk. */
    public static boolean safeStanding(Location feet) {
        World world=feet.getWorld();double x=feet.getX(),y=feet.getY(),z=feet.getZ();
        if(world==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||y<world.getMinHeight()||y+PlayerLandingGeometry.HEIGHT>=world.getMaxHeight())return false;
        int minX=(int)Math.floor(x-PlayerLandingGeometry.RADIUS),maxX=(int)Math.floor(x+PlayerLandingGeometry.RADIUS);
        int minZ=(int)Math.floor(z-PlayerLandingGeometry.RADIUS),maxZ=(int)Math.floor(z+PlayerLandingGeometry.RADIUS);
        int minY=Math.max(world.getMinHeight(),(int)Math.floor(y)-2),maxY=(int)Math.floor(y+PlayerLandingGeometry.HEIGHT);
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++)if(!world.isChunkLoaded(bx>>4,bz>>4))return false;
        var collisions=new ArrayList<Box>();
        for(int bx=minX;bx<=maxX;bx++)for(int bz=minZ;bz<=maxZ;bz++)for(int by=minY;by<=maxY;by++) {
            Block block=world.getBlockAt(bx,by,bz);
            boolean unsafe=block.isLiquid()||PaperSpawnTerrain.hazard(block.getType())
                    ||block.getBlockData() instanceof Waterlogged wet&&wet.isWaterlogged();
            if(unsafe&&new Box(bx,by,bz,bx+1,by+1,bz+1).touchesFeetOrBody(x,y,z))return false;
            // Paper collision boxes are block-local; getBoundingBox() is only an approximate envelope.
            for(var box:block.getCollisionShape().getBoundingBoxes())
                collisions.add(new Box(bx+box.getMinX(),by+box.getMinY(),bz+box.getMinZ(),
                        bx+box.getMaxX(),by+box.getMaxY(),bz+box.getMaxZ()));
        }
        return PlayerLandingGeometry.standing(x,y,z,collisions);
    }
    /** Returns the exact supported fractional Y for a planned column, including slabs and stairs. */
    public static Double safeSurface(Block floor) {
        if(!floor.getWorld().isChunkLoaded(floor.getX()>>4,floor.getZ()>>4))return null;
        var heights=new TreeSet<Double>(Comparator.reverseOrder());
        for(var box:floor.getCollisionShape().getBoundingBoxes())heights.add(floor.getY()+box.getMaxY());
        for(double y:heights)if(safeStanding(new Location(floor.getWorld(),floor.getX()+.5,y,floor.getZ()+.5)))return y;
        return null;
    }
}
