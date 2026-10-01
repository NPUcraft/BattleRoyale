package com.npucraft.battleroyale.zone;

import com.npucraft.battleroyale.map.PlayableArea;
import com.npucraft.battleroyale.util.Checks;
import java.util.*;
import java.util.random.RandomGenerator;

/** Opening centers only: either legacy jittered points or named rectangular voting regions. */
public record InitialZoneCenters(double jitterRadius, List<Point> points, List<Region> regions) {
    public static final int MAX_REGIONS=64;
    /** Existing configurations preserve their constructor, random consumption and recovery hash. */
    public InitialZoneCenters(double jitterRadius,List<Point> points){this(jitterRadius,points,List.of());}
    public InitialZoneCenters(List<Region> regions){this(0,List.of(),regions);}
    public InitialZoneCenters {
        Checks.finite(jitterRadius,"jitterRadius");
        if(jitterRadius<0)throw new IllegalArgumentException("jitterRadius >= 0 required");
        points=List.copyOf(points);regions=List.copyOf(regions);
        if(points.isEmpty()==regions.isEmpty())throw new IllegalArgumentException("Choose exactly one nonempty list of initial center points or regions");
        if(points.size()>256)throw new IllegalArgumentException("At most 256 initial center points supported");
        if(regions.size()>MAX_REGIONS)throw new IllegalArgumentException("At most "+MAX_REGIONS+" initial center regions supported");
        if(!regions.isEmpty()&&jitterRadius!=0)throw new IllegalArgumentException("jitter-radius applies only to legacy points, not rectangular regions");
        var ids=new HashSet<String>();
        for(var region:regions)if(!ids.add(region.id()))throw new IllegalArgumentException("Duplicate initial center region id: "+region.id());
    }
    public void validate(PlayableArea area,double halfSize){
        Objects.requireNonNull(area);Checks.finite(halfSize,"halfSize");
        double extent=halfSize+jitterRadius;
        if(halfSize<=0||!Double.isFinite(extent))throw new IllegalArgumentException("Invalid initial center extent");
        for(int i=0;i<points.size();i++){
            Point point=points.get(i);
            if(!fits(area,point.x()-extent,point.x()+extent,point.z()-extent,point.z()+extent))
                throw new IllegalArgumentException("initial-centers.points["+i+"] at ("+point.x()+", "+point.z()
                        +") plus jitter-radius="+jitterRadius+" and half-size="+halfSize+" must fit playable area "+area);
        }
        for(int i=0;i<regions.size();i++){
            Region region=regions.get(i);
            if(!fits(area,region.minX()-halfSize,region.maxX()+halfSize,region.minZ()-halfSize,region.maxZ()+halfSize))
                throw new IllegalArgumentException("initial-centers.regions["+i+"] id="+region.id()+" bounds="+region
                        +" plus half-size="+halfSize+" must fit playable area "+area);
        }
    }
    private static boolean fits(PlayableArea area,double minX,double maxX,double minZ,double maxZ){
        return Double.isFinite(minX)&&Double.isFinite(maxX)&&Double.isFinite(minZ)&&Double.isFinite(maxZ)
                &&area.contains(minX,minZ)&&area.contains(maxX,maxZ);
    }
    public Optional<Region> region(String id){return regions.stream().filter(region->region.id().equals(id)).findFirst();}
    public Zone choose(PlayableArea area,double halfSize,RandomGenerator random){return choose(area,halfSize,random,null);}
    /** A nonnull ID is already frozen by the room vote; no second region lottery is performed. */
    public Zone choose(PlayableArea area,double halfSize,RandomGenerator random,String regionId){
        validate(area,halfSize);Objects.requireNonNull(random);
        final Point point;
        if(!regions.isEmpty()){
            Region selected=regionId==null?regions.get(random.nextInt(regions.size())):
                    region(regionId).orElseThrow(()->new IllegalArgumentException("Unknown initial center region: "+regionId));
            point=selected.sample(random);
        }else{
            if(regionId!=null)throw new IllegalArgumentException("Named region requested for legacy initial points: "+regionId);
            Point selected=points.get(random.nextInt(points.size()));
            double x=selected.x(),z=selected.z();
            if(jitterRadius>0){
                double radius=Math.sqrt(random.nextDouble())*jitterRadius;
                double angle=random.nextDouble()*2*Math.PI;
                x+=radius*Math.cos(angle);z+=radius*Math.sin(angle);
            }
            point=new Point(x,z);
        }
        var zone=new Zone(point.x(),point.z(),halfSize);
        if(!area.contains(zone))throw new IllegalArgumentException("Initial center rounding exceeded playable area");
        return zone;
    }
    /** Keep the exact former record spelling when only points are configured. */
    @Override public String toString(){
        String legacy="InitialZoneCenters[jitterRadius="+jitterRadius+", points="+points;
        return regions.isEmpty()?legacy+"]":legacy+", regions="+regions+"]";
    }
    public record Point(double x,double z){
        public Point{Checks.finite(x,"x");Checks.finite(z,"z");}
    }
    /** Bounds describe possible centers, not the outside boundary of the initial safe square. */
    public record Region(String id,String name,double minX,double maxX,double minZ,double maxZ){
        public Region{
            if(id==null||!id.matches("[a-z0-9][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Region id must be 1..48 lowercase letters, digits, '_' or '-' and begin with a letter or digit");
            if(name==null||name.isBlank()||name.length()>64||!name.equals(name.strip())
                    ||name.codePoints().anyMatch(value->Character.isISOControl(value)||value==0x2028||value==0x2029||value==0x00a7))
                throw new IllegalArgumentException("Region name must be 1..64 visible characters without leading/trailing whitespace, control characters or legacy formatting codes");
            Checks.finite(minX,"min-x");Checks.finite(maxX,"max-x");Checks.finite(minZ,"min-z");Checks.finite(maxZ,"max-z");
            if(minX>=maxX||minZ>=maxZ||!Double.isFinite(maxX-minX)||!Double.isFinite(maxZ-minZ))
                throw new IllegalArgumentException("Region min bounds must be strictly below max bounds with finite width/depth");
        }
        public boolean contains(double x,double z){return Double.isFinite(x)&&Double.isFinite(z)&&x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}
        /** Independent uniform axes make a uniform rectangular sample, independent of the region's area. */
        public Point sample(RandomGenerator random){
            Objects.requireNonNull(random);
            return new Point(Math.fma(random.nextDouble(),maxX-minX,minX),Math.fma(random.nextDouble(),maxZ-minZ,minZ));
        }
    }
}