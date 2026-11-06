package com.vimi.gui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javafx.scene.image.Image;

/**
 * F12 screenshots of the World view: the presented frame only, never the HUD over it, written as
 * PNG into the library's {@code screenshots} folder. The encoder is self-contained (no AWT), so
 * it works in any runtime image.
 */
final class WorldScreenshot {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private WorldScreenshot() {}

    /**
     * Writes {@code frame} as {@code world_<map>_<time>.png} under {@code library/screenshots}
     * and returns the file. The map label is reduced to safe file-name characters.
     */
    static Path save(Pixels frame, Path library, String map, LocalDateTime when) throws IOException {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(library);
        var folder = library.resolve("screenshots");
        Files.createDirectories(folder);
        var label = (map == null ? "" : map).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        var base = "world_" + (label.isEmpty() ? "" : label + "_") + NAME.format(when);
        var file = folder.resolve(base + ".png");
        for (int n = 2; Files.exists(file); n++) file = folder.resolve(base + "_" + n + ".png");
        Files.write(file, png(frame));
        return file;
    }

    /** A frame's pixels, copied on the FX thread so encoding can run elsewhere. */
    record Pixels(int width, int height, int[] argb) {
        Pixels {
            if (width <= 0 || height <= 0 || argb.length != width * height)
                throw new IllegalArgumentException("No frame to save");
        }
    }

    /** Copies {@code frame}'s pixels; FX thread (the presented image may be reused in place). */
    static Pixels read(Image frame) {
        int width = (int) frame.getWidth(), height = (int) frame.getHeight();
        if (width <= 0 || height <= 0 || frame.getPixelReader() == null)
            throw new IllegalArgumentException("No frame to save");
        var argb = new int[width * height];
        frame.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, width);
        return new Pixels(width, height, argb);
    }

    /** PNG bytes of {@code frame}: 8-bit RGBA, no interlacing. */
    static byte[] png(Pixels frame) {
        int width = frame.width(), height = frame.height();
        var raw = new byte[height * (1 + width * 4)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0; // filter: none
            for (int x = 0; x < width; x++) {
                int argb = frame.argb()[y * width + x];
                raw[at++] = (byte) (argb >>> 16);
                raw[at++] = (byte) (argb >>> 8);
                raw[at++] = (byte) argb;
                raw[at++] = (byte) (argb >>> 24);
            }
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        var header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", header.array());
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        var compressed = new ByteArrayOutputStream();
        var buffer = new byte[64 * 1024];
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        var name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
