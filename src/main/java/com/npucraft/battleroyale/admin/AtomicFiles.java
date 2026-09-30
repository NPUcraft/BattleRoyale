package com.npucraft.battleroyale.admin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Fail closed on links and unsupported atomic replacement. No partial destination writes. */
public final class AtomicFiles {
  private AtomicFiles() {}

  public static void safe(Path path) throws IOException {
    for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
      if (Files.isSymbolicLink(p)
          || Files.exists(p, LinkOption.NOFOLLOW_LINKS)
              && !p.toRealPath().equals(p.toAbsolutePath().normalize()))
        throw new IOException("Linked path refused: " + p);
  }

  public static void write(Path target, byte[] bytes) throws IOException {
    safe(target);
    Files.createDirectories(target.toAbsolutePath().getParent());
    safe(target);
    Path temporary =
        target.resolveSibling(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
    try {
      try (var channel =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
