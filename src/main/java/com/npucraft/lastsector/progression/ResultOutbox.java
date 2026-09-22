package com.npucraft.lastsector.progression;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
/** Worker-only local write-ahead outbox, independent of database availability and runtime worlds.
 * The caller must await persist before permitting outcome checkpoint retirement/world cleanup.
 */
public final class ResultOutbox {
    private final Path directory;
    private final Gson gson=new Gson();
    private record Envelope(int version,String checksum,String payload) {}
    private final com.npucraft.lastsector.recovery.SnapshotCodec codec=new com.npucraft.lastsector.recovery.SnapshotCodec();
    public ResultOutbox(Path directory)throws IOException {
        this.directory=directory.toAbsolutePath().normalize();
        for(Path p=this.directory;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p))throw new IOException("Linked outbox path");
        Files.createDirectories(this.directory);
    }
    private Path path(UUID id) { return directory.resolve(id+".json"); }
    public void persist(MatchResult result)throws IOException {
        Path target=path(result.sessionId());
        if(Files.exists(target)) {
            if(!read(target).equals(result))throw new IOException("Conflicting pending result");
            return;
        }
        Path temporary=directory.resolve(result.sessionId()+".tmp");
        var encoded=codec.encode(result);
        byte[] bytes=gson.toJson(new Envelope(encoded.version(),encoded.checksum(),encoded.json())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
    }
    public List<MatchResult> pending()throws IOException {
        var results=new ArrayList<MatchResult>();
        try(var paths=Files.newDirectoryStream(directory,"*.json")) {for(var path:paths) results.add(read(path));}
        results.sort(Comparator.comparingLong(MatchResult::completedAt).thenComparing(r->r.sessionId().toString()));
        return List.copyOf(results);
    }
    private MatchResult read(Path path)throws IOException {
        if(Files.isSymbolicLink(path)||Files.size(path)>32*1024*1024)throw new IOException("Invalid outbox file");
        try {
            var envelope=gson.fromJson(Files.readString(path),Envelope.class);
            var result=codec.decode(envelope.version(),envelope.payload(),envelope.checksum(),MatchResult.class);
            if(result==null || !path.getFileName().toString().equals(result.sessionId()+".json"))throw new IOException("Invalid result identity");
            return result;
        }catch(RuntimeException e){throw new IOException("Invalid result payload",e);}
    }
    /** Only after the permanent transaction is confirmed committed (including an existing result). */
    public void acknowledge(UUID id)throws IOException {Files.deleteIfExists(path(id));}
}
