package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in per-tile write/read timing for the largest textured terrain packet. Run with {@code
 * VIMI_TILE_IO_BENCH=1 ./gradlew :rendering-bgfx:test --tests '*WorldStreamTileFileBenchmark'};
 * results go to stdout and {@code build/tile-io-bench.txt}.
 */
@EnabledIfEnvironmentVariable(named = "VIMI_TILE_IO_BENCH", matches = "1")
final class WorldStreamTileFileBenchmark {
    private static final int WARMUP = 15, RUNS = 40;
    @TempDir Path directory;

    @Test
    void maximumTexturedTileWriteAndRead() throws Exception {
        var tile = WorldStreamTileFileTest.full(true);
        var header = WorldStreamTileFileTest.header(tile, true);
        var atlas = WorldStreamTileFileTest.atlas();
        long[] write = new long[RUNS], read = new long[RUNS], fingerprint = new long[RUNS];
        long[] sha = new long[RUNS], crc = new long[RUNS], fsync = new long[RUNS];
        Path path = directory.resolve("tile-42.wst");
        byte[] bytes = null;
        for (int i = -WARMUP; i < RUNS; i++) {
            long t0 = System.nanoTime();
            var expected = WorldStreamTileFile.write(directory, header, tile, atlas, () -> false);
            long t1 = System.nanoTime();
            try (var payload = WorldStreamTileFile.read(path, expected, () -> false)) {
                payload.tile();
            }
            long t2 = System.nanoTime();
            TerrainGeometryFingerprint.sha256(tile);
            long t3 = System.nanoTime();
            bytes = Files.readAllBytes(path);
            long t4 = System.nanoTime();
            MessageDigest.getInstance("SHA-256").digest(bytes);
            long t5 = System.nanoTime();
            var c = new CRC32C();
            c.update(bytes);
            c.getValue();
            long t6 = System.nanoTime();
            Files.delete(path);
            long t7 = System.nanoTime();
            fsyncWrite(directory.resolve("fsync.bin"), bytes);
            long t8 = System.nanoTime();
            Files.delete(directory.resolve("fsync.bin"));
            if (i >= 0) {
                write[i] = t1 - t0;
                read[i] = t2 - t1;
                fingerprint[i] = t3 - t2;
                sha[i] = t5 - t4;
                crc[i] = t6 - t5;
                fsync[i] = t8 - t7;
            }
        }
        var report =
                String.format(
                        Locale.ROOT,
                        "tile-io-bench bytes=%d runs=%d (median / p90 ms)%n"
                                + "  write          %s%n"
                                + "  read           %s%n"
                                + "  fingerprint    %s%n"
                                + "  sha256(file)   %s%n"
                                + "  crc32c(file)   %s%n"
                                + "  write+fsync    %s%n",
                        bytes.length,
                        RUNS,
                        stats(write),
                        stats(read),
                        stats(fingerprint),
                        stats(sha),
                        stats(crc),
                        stats(fsync));
        System.out.print(report);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "tile-io-bench.txt"), report);
    }

    private static void fsyncWrite(Path path, byte[] bytes) throws IOException {
        try (var channel =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
    }

    private static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "%7.2f / %7.2f",
                sorted[sorted.length / 2] / 1e6,
                sorted[sorted.length * 9 / 10] / 1e6);
    }
}
