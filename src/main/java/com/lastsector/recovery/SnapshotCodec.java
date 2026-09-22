package com.lastsector.recovery;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
/** Explicit v1 DTO codec. Hash bytes before any parsing; never Java native serialization. */
public final class SnapshotCodec {
    public static final int VERSION=1,MAX_BYTES=32*1024*1024;
    private final Gson json=new GsonBuilder().disableHtmlEscaping().registerTypeHierarchyAdapter(java.util.Set.class,(JsonSerializer<java.util.Set<?>>)(values,type,context)->{
        var sorted=values.stream().map(context::serialize).sorted(java.util.Comparator.comparing(JsonElement::toString)).toList();
        var array=new JsonArray();sorted.forEach(array::add);return array;
    }).create();
    public record Payload(int version,String json,String checksum) {}
    public Payload encode(Object value){String text=json.toJson(canonical(json.toJsonTree(value)));if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("Recovery snapshot exceeds size limit");return new Payload(VERSION,text,hash(text));}
    public <T> T decode(int version,String payload,String checksum,Class<T> type){
        if(version!=VERSION)throw new IllegalArgumentException("Unsupported recovery format "+version);
        if(payload==null || checksum==null || payload.length()>MAX_BYTES || payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || !MessageDigest.isEqual(hash(payload).getBytes(StandardCharsets.US_ASCII),checksum.getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Recovery checksum mismatch");
        try{T value=json.fromJson(payload,type);if(value==null)throw new IllegalArgumentException("Empty recovery payload");return value;}catch(JsonParseException error){throw new IllegalArgumentException("Malformed recovery payload",error);}
    }
    private JsonElement canonical(JsonElement value){
        if(value.isJsonObject()){var sorted=new JsonObject();var object=value.getAsJsonObject();object.keySet().stream().sorted().forEach(key->sorted.add(key,canonical(object.get(key))));return sorted;}
        if(value.isJsonArray()){var array=new JsonArray();value.getAsJsonArray().forEach(item->array.add(canonical(item)));return array;}
        return value;
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
