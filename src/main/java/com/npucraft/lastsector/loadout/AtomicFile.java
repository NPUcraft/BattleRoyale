package com.npucraft.lastsector.loadout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** No non-atomic fallback: failure preserves the original destination. */
public final class AtomicFile {
    private AtomicFile() {}
    public static void replace(Path target, String text) throws IOException {
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".loadouts-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
