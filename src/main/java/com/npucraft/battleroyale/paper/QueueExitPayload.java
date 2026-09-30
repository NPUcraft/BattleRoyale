package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.session.GameState;
import java.io.*;
import java.security.*;
import java.util.*;

/** Persistent original-slot envelope carried by the temporary exit item; no live Bukkit objects. */
public record QueueExitPayload(UUID owner,UUID session,byte[] original) {
    private static final int MAGIC=0x4c535131, MAX_ITEM_BYTES=4*1024*1024;
    public QueueExitPayload {
        Objects.requireNonNull(owner);Objects.requireNonNull(session);Objects.requireNonNull(original);
        if(original.length>MAX_ITEM_BYTES)throw new IllegalArgumentException("原物品数据过大，无法显示退出按钮。");
        original=original.clone();
    }
    @Override public byte[] original(){return original.clone();}
    public boolean belongsTo(UUID player,UUID expectedSession){return owner.equals(player)&&session.equals(expectedSession);}
    public static boolean queue(GameState state){return state==GameState.WAITING||state==GameState.COUNTDOWN;}
    public byte[] encode(){
        try {
            var body=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(body)){
                out.writeInt(MAGIC);out.writeLong(owner.getMostSignificantBits());out.writeLong(owner.getLeastSignificantBits());
                out.writeLong(session.getMostSignificantBits());out.writeLong(session.getLeastSignificantBits());out.writeInt(original.length);out.write(original);
            }
            byte[] bytes=body.toByteArray();body.write(hash(bytes));return body.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    public static QueueExitPayload decode(byte[] value){
        if(value==null||value.length<72||value.length>MAX_ITEM_BYTES+72)throw new IllegalArgumentException("退出按钮数据长度无效，已保留物品。");
        byte[] body=Arrays.copyOf(value,value.length-32),checksum=Arrays.copyOfRange(value,value.length-32,value.length);
        if(!MessageDigest.isEqual(checksum,hash(body)))throw new IllegalArgumentException("退出按钮数据校验失败，已保留物品。");
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            if(in.readInt()!=MAGIC)throw new IllegalArgumentException("退出按钮版本无效，已保留物品。");
            UUID owner=new UUID(in.readLong(),in.readLong()),session=new UUID(in.readLong(),in.readLong());int size=in.readInt();
            if(size<0||size>MAX_ITEM_BYTES||size!=in.available())throw new IllegalArgumentException("退出按钮原物品数据无效，已保留物品。");
            return new QueueExitPayload(owner,session,in.readNBytes(size));
        }catch(IOException error){throw new IllegalArgumentException("无法读取退出按钮，已保留物品。",error);}
    }
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
