package com.npucraft.battleroyale.loot;

import com.npucraft.battleroyale.admin.AtomicFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable intentions precede every temporary beacon mutation. Chunk PDC is the restore commit marker. */
public final class AirdropBeaconLedger {
    public static final String DIRECTORY="battleroyale-airdrop-beacons";
    private static final int MAGIC=0x4c534237,MAX_PLANS=128,MAX_BYTES=32768;
    public record Cell(int x,int y,int z,String original,String placed){
        public Cell {
            if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||y< -2048||y>2048
                    ||original==null||placed==null||original.length()>2048||placed.length()>2048
                    ||!original.startsWith("minecraft:")||!placed.startsWith("minecraft:"))throw new IllegalArgumentException("Invalid beacon cell");
        }
        public boolean restores(String current,boolean owned){return owned&&placed.equals(current);}
    }
    public record Plan(UUID session,UUID world,int stage,int x,int y,int z,List<Cell> cells){
        public Plan {
            Objects.requireNonNull(session);Objects.requireNonNull(world);cells=List.copyOf(cells);
            if(stage<0||stage>=MAX_PLANS||cells.size()!=11)throw new IllegalArgumentException("Invalid beacon plan");
            var seen=new HashSet<String>();
            for(var cell:cells){
                if(!seen.add(cell.x()+":"+cell.y()+":"+cell.z()))throw new IllegalArgumentException("Duplicate beacon cell");
                String expected;
                if(cell.y()==y-1&&Math.abs((long)cell.x()-x)<=1&&Math.abs((long)cell.z()-z)<=1)expected="minecraft:iron_block";
                else if(cell.x()==x&&cell.z()==z&&cell.y()==y)expected="minecraft:beacon";
                else if(cell.x()==x&&cell.z()==z&&cell.y()==y+1)expected="minecraft:yellow_stained_glass";
                else throw new IllegalArgumentException("Beacon cell outside fixed eleven-block footprint");
                if(!expected.equals(cell.placed()))throw new IllegalArgumentException("Unexpected temporary material");
            }
        }
        public String identity(){return session+":"+stage;}
    }
    private AirdropBeaconLedger(){}
    private static Path directory(Path world)throws IOException {
        AtomicFiles.safe(world);
        if(!Files.isDirectory(world,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing beacon world");
        Path directory=world.resolve(DIRECTORY);AtomicFiles.safe(directory);return directory;
    }
    public static void save(Path world,Plan plan)throws IOException {
        Path dir=directory(world);var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeUTF(plan.session().toString());out.writeUTF(plan.world().toString());out.writeInt(plan.stage());
            out.writeInt(plan.x());out.writeInt(plan.y());out.writeInt(plan.z());out.writeInt(plan.cells().size());
            for(var cell:plan.cells()){out.writeInt(cell.x());out.writeInt(cell.y());out.writeInt(cell.z());out.writeUTF(cell.original());out.writeUTF(cell.placed());}
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("Beacon record too large");
        AtomicFiles.write(dir.resolve("beacon-"+plan.stage()+".dat"),bytes.toByteArray());
    }
    public static List<Plan> load(Path world,UUID session,UUID worldId)throws IOException {
        Path dir=directory(world);if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid beacon ledger directory");
        var result=new ArrayList<Plan>();
        try(var stream=Files.list(dir)){
            for(var file:stream.toList()){
                AtomicFiles.safe(file);String name=file.getFileName().toString();
                // An interrupted atomic write never authorizes world mutation.
                if(name.matches("beacon-[0-9]+\\.dat\\.tmp-[0-9a-f-]+"))continue;
                if(!name.matches("beacon-[0-9]+\\.dat")||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        ||Files.size(file)>MAX_BYTES||result.size()>=MAX_PLANS)throw new IOException("Invalid beacon ledger file");
                try(var in=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))){
                    if(in.readInt()!=MAGIC)throw new IOException("Unknown beacon record");
                    UUID owner=UUID.fromString(in.readUTF()),dimension=UUID.fromString(in.readUTF());int stage=in.readInt();
                    int x=in.readInt(),y=in.readInt(),z=in.readInt(),count=in.readInt();if(count!=11)throw new IOException("Invalid beacon cell count");
                    var cells=new ArrayList<Cell>();for(int i=0;i<count;i++)cells.add(new Cell(in.readInt(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF()));
                    var plan=new Plan(owner,dimension,stage,x,y,z,cells);
                    if(in.read()!=-1||!owner.equals(session)||!dimension.equals(worldId)||!name.equals("beacon-"+stage+".dat"))throw new IOException("Foreign or corrupt beacon record");
                    result.add(plan);
                }catch(IllegalArgumentException invalid){throw new IOException("Invalid beacon record",invalid);}
            }
        }
        return List.copyOf(result);
    }
}
