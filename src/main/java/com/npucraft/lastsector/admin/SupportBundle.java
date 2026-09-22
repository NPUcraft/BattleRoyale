package com.npucraft.lastsector.admin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit text-entry allowlist; never recursively archives the plugin directory or server log. */
public final class SupportBundle {
  public static Path write(Path directory, Map<String, String> entries) throws IOException {
    AtomicFiles.safe(directory);
    Files.createDirectories(directory);
    Path target =
        directory.resolve(
            "lastsector-support-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip");
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (var entry : entries.entrySet()) {
        if (!entry.getKey().matches("[a-zA-Z0-9_-]+\\.txt"))
          throw new IOException("Unsafe bundle entry name");
        byte[] text = entry.getValue().getBytes(StandardCharsets.UTF_8);
        if (text.length > 2 * 1024 * 1024) throw new IOException("Bundle component too large");
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(text);
        zip.closeEntry();
      }
      if (bytes.size() > 16 * 1024 * 1024) throw new IOException("Bundle exceeds 16 MiB");
    }
    AtomicFiles.write(target, bytes.toByteArray());
    return target;
  }
}
