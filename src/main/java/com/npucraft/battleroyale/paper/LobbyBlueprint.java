package com.npucraft.battleroyale.paper;

import java.util.*;
import org.bukkit.Material;

/** Deterministic bounded octagonal plaza, three roofed pavilions and landscaped light wells. */
public final class LobbyBlueprint {
    public static final int VERSION = 1;
    public record Position(int x,int y,int z) {}
    public record Block(Position position, Material material) {}
    private LobbyBlueprint() {}
    public static List<Block> blocks() {
        var plan = new LinkedHashMap<Position,Material>();
        for(int x=-32;x<=32;x++)for(int z=-32;z<=32;z++) {
            if(Math.abs(x)+Math.abs(z)>46)continue;
            for(int y=-3;y<=10;y++)put(plan,x,y,z,Material.AIR);
            int edge=Math.max(Math.max(Math.abs(x),Math.abs(z)),Math.abs(x)+Math.abs(z)-14);
            if(edge<=27)put(plan,x,-3,z,Material.POLISHED_BASALT);
            if(edge<=30)put(plan,x,-2,z,Material.POLISHED_DEEPSLATE);
            put(plan,x,-1,z,Material.DEEPSLATE_TILES);
            Material floor=edge>=30?Material.POLISHED_DEEPSLATE:edge==29?Material.SEA_LANTERN:Material.SMOOTH_QUARTZ;
            if(edge<28&&(Math.abs(x)<=2||Math.abs(z)<=2))floor=Material.CYAN_TERRACOTTA;
            if(edge<28&&(Math.abs(x)==3||Math.abs(z)==3))floor=Material.DARK_PRISMARINE;
            if(x*x+z*z<=49)floor=x*x+z*z>=36?Material.SEA_LANTERN:Material.POLISHED_BLACKSTONE;
            if((x==0||z==0)&&x*x+z*z<=25)floor=Material.SMOOTH_QUARTZ;
            put(plan,x,0,z,floor);
            if(edge==32) {
                put(plan,x,1,z,Material.WHITE_STAINED_GLASS);
                if((x+z)%8==0){put(plan,x,1,z,Material.POLISHED_DEEPSLATE);put(plan,x,2,z,Material.SEA_LANTERN);}
            }
        }
        pavilion(plan,-18,-16,Material.LIGHT_BLUE_CONCRETE);
        pavilion(plan,0,-22,Material.CYAN_CONCRETE);
        pavilion(plan,18,-16,Material.BLUE_CONCRETE);
        for(int x:new int[]{-19,19})for(int z:new int[]{5,18}) {
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
                put(plan,x+dx,1,z+dz,Math.abs(dx)==3||Math.abs(dz)==3?Material.POLISHED_DEEPSLATE:Material.MOSS_BLOCK);
                if(Math.abs(dx)<3&&Math.abs(dz)<3)put(plan,x+dx,2,z+dz,(dx+dz)%3==0?Material.FLOWERING_AZALEA:Material.AZALEA);
            }
            put(plan,x,2,z,Material.STRIPPED_OAK_LOG);put(plan,x,3,z,Material.STRIPPED_OAK_LOG);
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(Math.abs(dx)+Math.abs(dz)<=3)put(plan,x+dx,4,z+dz,Material.AZALEA_LEAVES);
            put(plan,x,5,z,Material.FLOWERING_AZALEA_LEAVES);
        }
        for(int x:new int[]{-9,9})for(int z:new int[]{9,23}) {
            put(plan,x,1,z,Material.CHISELED_QUARTZ_BLOCK);put(plan,x,2,z,Material.DARK_PRISMARINE);
            put(plan,x,3,z,Material.SEA_LANTERN);put(plan,x,4,z,Material.SMOOTH_QUARTZ_SLAB);
        }
        for(int x=-5;x<=5;x++){put(plan,x,1,23,Material.POLISHED_DEEPSLATE);put(plan,x,2,24,Material.DARK_PRISMARINE);}
        put(plan,0,1,7,Material.CHISELED_QUARTZ_BLOCK);put(plan,0,2,7,Material.LECTERN);
        return plan.entrySet().stream().map(e->new Block(e.getKey(),e.getValue())).toList();
    }
    private static void pavilion(Map<Position,Material> p,int x,int z,Material accent) {
        for(int dx=-6;dx<=6;dx++)for(int dz=-5;dz<=5;dz++) {
            put(p,x+dx,0,z+dz,Material.POLISHED_DEEPSLATE);
            if(Math.abs(dx)<6&&Math.abs(dz)<5)put(p,x+dx,0,z+dz,Material.SMOOTH_QUARTZ);
            put(p,x+dx,6,z+dz,Material.DARK_PRISMARINE);
            if(Math.abs(dx)==6||Math.abs(dz)==5)put(p,x+dx,5,z+dz,Material.SMOOTH_QUARTZ);
            if(Math.abs(dx)<=4&&Math.abs(dz)<=3)put(p,x+dx,7,z+dz,accent);
        }
        for(int dx:new int[]{-5,5})for(int dz:new int[]{-4,4})for(int y=1;y<=5;y++)put(p,x+dx,y,z+dz,Material.QUARTZ_PILLAR);
        for(int dx=-4;dx<=4;dx++)for(int y=1;y<=4;y++)put(p,x+dx,y,z-4,Material.CYAN_STAINED_GLASS);
        for(int dx:new int[]{-3,3})put(p,x+dx,4,z+3,Material.SEA_LANTERN);
        put(p,x,1,z+3,Material.CHISELED_QUARTZ_BLOCK);put(p,x,2,z+3,Material.LECTERN);
    }
    private static void put(Map<Position,Material> plan,int x,int y,int z,Material material) {
        if(Math.abs(x)>32||Math.abs(z)>32||y < -3||y>10)throw new IllegalArgumentException("Blueprint leaves construction boundary");
        plan.put(new Position(x,y,z),material);
    }
}
