package com.npucraft.battleroyale.loot;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.npucraft.battleroyale.admin.AtomicFiles;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Immutable planned points plus an unordered durable claim set in one atomic file.
 * Caller serializes every operation on its owned IO executor. No method uses Bukkit.
 * A successful claim must precede item/entity creation; a claimed point is never reopened.
 */
public final class GroundSupplyLedger {
    public static final String FILE="battleroyale-ground-supplies.json";
    public static final int MAX_POINTS=2048,MAX_BYTES=1024*1024;
    private static final String SCHEMA="battleroyale-ground-supplies";
    private static final int VERSION=1;
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> ROOT_FIELDS=Set.of("schema","version","session","world","points","claimed","sha256");
    private static final Set<String> POINT_FIELDS=Set.of("id","x","y","z","table","seed","minRolls","maxRolls");
    private final Path worldFolder,file;
    private final UUID sessionId,worldId;

    public record Point(int id,int x,int y,int z,String table,long seed,int minRolls,int maxRolls) {
        public Point {
            if(id<0||id>=MAX_POINTS)throw new IllegalArgumentException("Supply point ID outside budget");
            if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||y< -4096||y>4096)
                throw new IllegalArgumentException("Supply point coordinates outside supported bounds");
            if(table==null||table.isBlank()||table.length()>128||table.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid supply loot table ID");
            if(minRolls<0||maxRolls<minRolls||maxRolls>128)throw new IllegalArgumentException("Supply rolls must be within 0..128");
        }
    }
    public record Snapshot(List<Point> points,Set<Integer> claimed) {
        public Snapshot {
            Objects.requireNonNull(points);Objects.requireNonNull(claimed);
            if(points.size()>MAX_POINTS||claimed.size()>MAX_POINTS)throw new IllegalArgumentException("Supply ledger budget exceeded");
            var sorted=new ArrayList<>(points);sorted.sort(Comparator.comparingInt(Point::id));
            for(int i=0;i<sorted.size();i++)if(sorted.get(i).id()!=i)throw new IllegalArgumentException("Supply point IDs must be unique and continuous from zero");
            points=List.copyOf(sorted);claimed=Set.copyOf(claimed);
            for(int id:claimed)if(id<0||id>=points.size())throw new IllegalArgumentException("Claim refers to unknown supply point");
        }
    }
    public GroundSupplyLedger(Path worldFolder,UUID sessionId,UUID worldId) {
        this.worldFolder=Objects.requireNonNull(worldFolder).toAbsolutePath().normalize();
        this.sessionId=Objects.requireNonNull(sessionId);this.worldId=Objects.requireNonNull(worldId);
        file=this.worldFolder.resolve(FILE);
    }
    /** Publishes a complete plan once. Even a corrupt existing record must never be overwritten. */
    public void seal(List<Point> points)throws IOException {
        safe();
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Supply plan already exists; refusing to reseal");
        final Snapshot snapshot;
        try{snapshot=new Snapshot(points,Set.of());}catch(RuntimeException invalid){throw new IOException("Invalid supply plan",invalid);}
        write(snapshot);
    }
    /** A missing committed file means a legacy/no-supply world, never permission to plan new points. */
    public Optional<Snapshot> read()throws IOException {
        safe();
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw new IOException("Invalid or oversized supply ledger");
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(MAX_BYTES+1);}
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("Invalid supply ledger length");
        final String encoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try(var reader=new JsonReader(new StringReader(encoded))){
            reader.setStrictness(Strictness.STRICT);reader.beginObject();
            var fields=new HashSet<String>();String schema=null,session=null,world=null,digest=null;Integer version=null;
            List<Point> points=null;Set<Integer> claimed=null;
            while(reader.hasNext()){
                String key=reader.nextName();field(fields,ROOT_FIELDS,key);
                switch(key){
                    case "schema"->schema=string(reader);
                    case "version"->version=integer(reader);
                    case "session"->session=string(reader);
                    case "world"->world=string(reader);
                    case "sha256"->digest=string(reader);
                    case "points"->points=points(reader);
                    case "claimed"->claimed=claimed(reader);
                    default->throw new IOException("Unknown supply ledger field");
                }
            }
            reader.endObject();
            if(!fields.equals(ROOT_FIELDS)||reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Incomplete or trailing supply ledger content");
            if(!SCHEMA.equals(schema)||!Integer.valueOf(VERSION).equals(version))throw new IOException("Unsupported supply ledger schema/version");
            if(!sessionId.toString().equals(session)||!worldId.toString().equals(world))throw new IOException("Foreign supply ledger session/world");
            Snapshot snapshot=new Snapshot(points,claimed);
            if(digest==null||!digest.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(HexFormat.of().parseHex(digest),hash(payload(snapshot))))
                throw new IOException("Supply ledger checksum mismatch");
            return Optional.of(snapshot);
        }catch(RuntimeException invalid){throw new IOException("Corrupt supply ledger",invalid);}
    }
    /** Persists the claim before returning true. Repeated claims return false without writing. */
    public boolean claim(int id)throws IOException {
        if(id<0||id>=MAX_POINTS)throw new IOException("Supply claim ID outside budget");
        Snapshot before=read().orElseThrow(()->new IOException("No committed supply plan"));
        if(id>=before.points().size())throw new IOException("Unknown supply point ID");
        if(before.claimed().contains(id))return false;
        var claimed=new HashSet<>(before.claimed());claimed.add(id);
        write(new Snapshot(before.points(),claimed));return true;
    }
    private void safe()throws IOException {
        AtomicFiles.safe(worldFolder);
        if(!Files.isDirectory(worldFolder,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing supply world directory");
        AtomicFiles.safe(file);
    }
    private void write(Snapshot snapshot)throws IOException {
        safe();JsonObject payload=payload(snapshot);
        payload.addProperty("sha256",HexFormat.of().formatHex(hash(payload)));
        byte[] bytes=(JSON.toJson(payload)+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Supply ledger byte budget exceeded");
        // AtomicFiles forces the temporary file before atomic replacement and refuses unsafe links.
        AtomicFiles.write(file,bytes);
    }
    private JsonObject payload(Snapshot snapshot){
        var root=new JsonObject();root.addProperty("schema",SCHEMA);root.addProperty("version",VERSION);
        root.addProperty("session",sessionId.toString());root.addProperty("world",worldId.toString());
        var points=new JsonArray();
        for(var point:snapshot.points()){
            var value=new JsonObject();value.addProperty("id",point.id());value.addProperty("x",point.x());value.addProperty("y",point.y());value.addProperty("z",point.z());
            value.addProperty("table",point.table());value.addProperty("seed",point.seed());value.addProperty("minRolls",point.minRolls());value.addProperty("maxRolls",point.maxRolls());points.add(value);
        }
        root.add("points",points);var claimed=new JsonArray();new TreeSet<>(snapshot.claimed()).forEach(claimed::add);root.add("claimed",claimed);return root;
    }
    private static byte[] hash(JsonObject value){
        try{return MessageDigest.getInstance("SHA-256").digest(JSON.toJson(value).getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void field(Set<String> seen,Set<String> allowed,String name)throws IOException {
        if(!allowed.contains(name)||!seen.add(name))throw new IOException("Unknown or duplicate supply field: "+name);
    }
    private static String string(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.STRING)throw new IOException("Expected supply string");
        return reader.nextString();
    }
    private static long number(JsonReader reader)throws IOException {
        if(reader.peek()!=JsonToken.NUMBER)throw new IOException("Expected supply integer");
        String value=reader.nextString();
        if(!value.matches("-?(0|[1-9][0-9]*)"))throw new IOException("Supply number is not an exact integer");
        try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new IOException("Supply integer overflow",invalid);}
    }
    private static int integer(JsonReader reader)throws IOException {
        long value=number(reader);if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IOException("Supply integer overflow");return (int)value;
    }
    private static List<Point> points(JsonReader reader)throws IOException {
        reader.beginArray();var result=new ArrayList<Point>();
        while(reader.hasNext()){
            if(result.size()>=MAX_POINTS)throw new IOException("Too many supply points");
            reader.beginObject();var fields=new HashSet<String>();Integer id=null,x=null,y=null,z=null,min=null,max=null;String table=null;Long seed=null;
            while(reader.hasNext()){
                String key=reader.nextName();field(fields,POINT_FIELDS,key);
                switch(key){
                    case "id"->id=integer(reader);case "x"->x=integer(reader);case "y"->y=integer(reader);case "z"->z=integer(reader);
                    case "table"->table=string(reader);case "seed"->seed=number(reader);case "minRolls"->min=integer(reader);case "maxRolls"->max=integer(reader);
                    default->throw new IOException("Unknown supply point field");
                }
            }
            reader.endObject();if(!fields.equals(POINT_FIELDS))throw new IOException("Incomplete supply point");
            result.add(new Point(id,x,y,z,table,seed,min,max));
        }
        reader.endArray();return result;
    }
    private static Set<Integer> claimed(JsonReader reader)throws IOException {
        reader.beginArray();var result=new HashSet<Integer>();
        while(reader.hasNext()){
            if(result.size()>=MAX_POINTS)throw new IOException("Too many supply claims");
            if(!result.add(integer(reader)))throw new IOException("Duplicate supply claim");
        }
        reader.endArray();return result;
    }
}