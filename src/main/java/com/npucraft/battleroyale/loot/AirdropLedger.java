package com.npucraft.battleroyale.loot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Claimed on the IO executor before any block/item mutation. Stored inside the disposable game world. */
public final class AirdropLedger {
    private AirdropLedger() {}
    public static boolean claim(Path world,UUID session,int stage)throws IOException {
        if(stage<0||!Files.isDirectory(world,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(world))throw new IOException("Invalid airdrop world/stage");
        Path file=world.resolve("battleroyale-airdrops.ledger"),next=world.resolve("battleroyale-airdrops.ledger.next");
        if(Files.isSymbolicLink(file)||Files.isSymbolicLink(next))throw new IOException("Linked airdrop ledger");
        if(Files.exists(file)){
            if(Files.size(file)>256)throw new IOException("Oversized airdrop ledger");
            String[] fields=Files.readString(file,StandardCharsets.UTF_8).split("\\n",-1);
            try{
                if(fields.length!=3||!fields[2].isEmpty()||!UUID.fromString(fields[0]).equals(session))throw new IllegalArgumentException();
                int previous=Integer.parseInt(fields[1]);if(previous<0)throw new IllegalArgumentException();if(stage<=previous)return false;
            }catch(IllegalArgumentException invalid){throw new IOException("Invalid or foreign airdrop ledger",invalid);}
        }
        byte[] bytes=(session+"\n"+stage+"\n").getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(next,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        // Refuse an unsafe non-atomic fallback. A failed claim never creates a supply crate.
        Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
