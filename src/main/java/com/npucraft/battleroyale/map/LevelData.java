package com.npucraft.battleroyale.map;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded standard NBT reader for validation only; never rewrites seed or generator data. */
public final class LevelData {
    private LevelData() {}
    public static final String WORLD_GEN_SETTINGS = "data/minecraft/world_gen_settings.dat";
    /** Legacy fixture/import inspection. Runtime Paper 26.2 uses validateDimension instead. */
    public static long validate(Path file) throws IOException {
        var root = read(file);
        if (!(root.get("Data") instanceof Map<?, ?> data)
                || !(data.get("WorldGenSettings") instanceof Map<?, ?> settings))
            throw new IOException("level.dat lacks Data.WorldGenSettings");
        return seed(settings, file);
    }
    /** Validates a Paper 26.2 dimension without changing seed/generator data. */
    public static long validateDimension(Path directory) throws IOException {
        Path file = directory.resolve(WORLD_GEN_SETTINGS);
        var root = read(file);
        if (!(root.get("DataVersion") instanceof Integer version) || version < 1
                || !(root.get("data") instanceof Map<?, ?> settings))
            throw new IOException("Invalid Paper dimension world_gen_settings.dat: " + file);
        return seed(settings, file);
    }
    /** Complete saves and exported overworld dimensions are both accepted; legacy worlds stay untouched. */
    public static Path dimension(Path template) throws IOException {
        if (Files.isRegularFile(template.resolve(WORLD_GEN_SETTINGS), LinkOption.NOFOLLOW_LINKS)) {
            validateDimension(template);
            return template;
        }
        Path overworld = template.resolve("dimensions/minecraft/overworld");
        if (Files.isRegularFile(overworld.resolve(WORLD_GEN_SETTINGS), LinkOption.NOFOLLOW_LINKS)) {
            validateDimension(overworld);
            return overworld;
        }
        throw new IOException("Template is not a Paper 26.2 overworld dimension. Upgrade legacy worlds offline on Paper 26.2 and export the saved dimension; BattleRoyale never migrates active worlds.");
    }
    private static long seed(Map<?, ?> settings, Path file) throws IOException {
        if (!(settings.get("seed") instanceof Long seed)
                || !(settings.get("dimensions") instanceof Map<?, ?> dimensions)
                || !(dimensions.get("minecraft:overworld") instanceof Map<?, ?> overworld)
                || overworld.isEmpty())
            throw new IOException("World generation data lacks seed/overworld dimensions: " + file);
        return seed;
    }
    private static Map<?, ?> read(Path file) throws IOException {
        WorldFiles.noLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing regular world data: " + file);
        try (var gzip = new GZIPInputStream(Files.newInputStream(file));
             var input = new DataInputStream(new BufferedInputStream(new LimitedInputStream(gzip, 64L * 1024 * 1024)))) {
            if (input.readUnsignedByte() != 10) throw new IOException("World data root is not an NBT compound");
            input.readUTF();
            return (Map<?, ?>) payload(input, 10, 0);
        } catch (IOException | RuntimeException error) { throw new IOException("Invalid world data " + file + ": " + error.getMessage(), error); }
    }
    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting too deep");
        return switch (type) {
            case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt(); case 4 -> in.readLong();
            case 5 -> in.readFloat(); case 6 -> in.readDouble();
            case 7 -> { in.skipNBytes(length(in)); yield null; }
            case 8 -> in.readUTF();
            case 9 -> {
                int child = in.readUnsignedByte(), count = length(in);
                if (child == 0 && count > 0) throw new IOException("Invalid NBT list");
                for (int i = 0; i < count; i++) payload(in, child, depth + 1);
                yield null;
            }
            case 10 -> {
                Map<String, Object> fields = new HashMap<>();
                int child;
                while ((child = in.readUnsignedByte()) != 0) {
                    String name = in.readUTF(); fields.put(name, payload(in, child, depth + 1));
                    if (fields.size() > 100000) throw new IOException("Too many NBT fields");
                }
                yield fields;
            }
            case 11 -> { in.skipNBytes(4L * length(in)); yield null; }
            case 12 -> { in.skipNBytes(8L * length(in)); yield null; }
            default -> throw new IOException("Unknown NBT tag: " + type);
        };
    }
    private static int length(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 16000000) throw new IOException("Invalid NBT collection length");
        return count;
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            if (--remaining < 0) throw new IOException("NBT size limit exceeded");
            return super.read();
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new IOException("NBT size limit exceeded");
            int read = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
        @Override public long skip(long amount) throws IOException {
            if (remaining <= 0) throw new IOException("NBT size limit exceeded");
            long skipped = in.skip(Math.min(amount, remaining)); remaining -= skipped; return skipped;
        }
    }
}

