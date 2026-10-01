package com.npucraft.battleroyale.flight;

import com.npucraft.battleroyale.zone.Zone;
import java.util.*;
import java.util.random.RandomGenerator;

/** A bounded cardinal course with random orientation and lateral offset inside the initial square. */
public record FlightRoute(int startX,int startZ,int endX,int endZ) {
    public record Cell(int x,int y,int z) {}
    public record Chunk(int x,int z) {}
    public static final int WING=8,NOSE=9,HEIGHT=3;
    public static final int BOARDING_SECONDS=5,FLIGHT_SECONDS=45,LANDING_SECONDS=90,LOADING_SECONDS=45;
    public FlightRoute {
        long dx=(long)endX-startX,dz=(long)endZ-startZ;
        if((dx==0)==(dz==0)||Math.abs(dx)+Math.abs(dz)>540)throw new IllegalArgumentException("Flight course must be one bounded cardinal segment");
    }
    public static FlightRoute random(Zone zone,RandomGenerator random){
        Objects.requireNonNull(zone);Objects.requireNonNull(random);
        int length=(int)Math.min(540,Math.floor(zone.halfSize()*1.25));
        if(length<32||zone.halfSize()<32)throw new IllegalArgumentException("Initial zone is too small for flight");
        int offset=(int)Math.floor((random.nextDouble()-.5)*zone.halfSize());
        boolean alongX=random.nextBoolean(),forward=random.nextBoolean();
        int cx=(int)Math.floor(zone.centerX()),cz=(int)Math.floor(zone.centerZ());
        int first=-(length/2),last=first+length;
        int sx=alongX?cx+first:cx+offset,sz=alongX?cz+offset:cz+first;
        int ex=alongX?cx+last:cx+offset,ez=alongX?cz+offset:cz+last;
        FlightRoute route=forward?new FlightRoute(sx,sz,ex,ez):new FlightRoute(ex,ez,sx,sz);
        for(Cell at:route.corridor(0))if(!zone.contains(at.x(),at.z()))throw new IllegalArgumentException("Flight corridor leaves the initial zone");
        return route;
    }
    public int length(){return Math.abs(endX-startX)+Math.abs(endZ-startZ);}
    public int dx(){return Integer.signum(endX-startX);}
    public int dz(){return Integer.signum(endZ-startZ);}
    public Cell center(double fraction,int y){
        double bounded=Math.max(0,Math.min(1,fraction));
        return new Cell(startX+(int)Math.round((endX-startX)*bounded),y,startZ+(int)Math.round((endZ-startZ)*bounded));
    }
    public float yaw(){return dx()>0?-90:dx()<0?90:dz()>0?0:180;}
    /** Local x is the wing axis; negative local z is the nose. */
    public Cell translate(Cell center,int x,int y,int z){return new Cell(center.x()+dz()*x-dx()*z,center.y()+y,center.z()-dx()*x-dz()*z);}
    public List<Cell> corridor(int y){
        var result=new ArrayList<Cell>();
        for(int along=-NOSE-1;along<=length()+NOSE+1;along++)for(int across=-WING-1;across<=WING+1;across++)
            for(int up=0;up<=HEIGHT+2;up++)result.add(new Cell(startX+dx()*along+dz()*across,y+up,startZ+dz()*along-dx()*across));
        return List.copyOf(result);
    }
    public Set<Chunk> chunks(){
        var result=new LinkedHashSet<Chunk>();
        for(int along=-NOSE-1;along<=length()+NOSE+1;along++)for(int across=-WING-1;across<=WING+1;across++)
            result.add(new Chunk(Math.floorDiv(startX+dx()*along+dz()*across,16),Math.floorDiv(startZ+dz()*along-dx()*across,16)));
        if(result.size()>160)throw new IllegalArgumentException("Flight course loads too many chunks");
        return Collections.unmodifiableSet(result);
    }
}