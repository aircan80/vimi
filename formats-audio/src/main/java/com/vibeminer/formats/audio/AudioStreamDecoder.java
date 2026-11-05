package com.vibeminer.formats.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Streams bounded Vorbis or MPEG audio as PCM from a heap-bounded child JVM. Decoded PCM is only
 * passed through the returned stream; this class never writes a file or a stored derivative.
 */
public final class AudioStreamDecoder {
    public static final String REVISION = "world-audio-stream-jogg-1.0.6-javamp3-1.0.1-v1";
    public static final int MAX_ENCODED = 16 << 20, MAX_SECONDS = 15 * 60;
    static final int MAGIC = 0x56505332;
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(8);

    /** Container identified from leading bytes; never inferred from a name or extension. */
    public enum Codec {
        VORBIS,
        MPEG,
        UNSUPPORTED
    }

    private AudioStreamDecoder() {}

    public static Codec codec(byte[] encoded) {
        if (encoded.length >= 4
                && encoded[0] == 'O'
                && encoded[1] == 'g'
                && encoded[2] == 'g'
                && encoded[3] == 'S') {
            return Codec.VORBIS;
        }
        int offset = mpegStart(encoded);
        // MPEG-1 frame sync, a reserved-free layer, bitrate and sample rate.
        if (offset >= 0
                && offset <= encoded.length - 4
                && (encoded[offset] & 0xff) == 0xff
                && (encoded[offset + 1] & 0xf8) == 0xf8
                && (encoded[offset + 1] & 0x06) != 0
                && (encoded[offset + 2] & 0xf0) != 0xf0
                && (encoded[offset + 2] & 0x0c) != 0x0c) {
            return Codec.MPEG;
        }
        return Codec.UNSUPPORTED;
    }

    /**
     * Offset of the first byte after the leading ID3v2 tags (0 without one), or -1 for a tag whose
     * size is not syncsafe. Consecutive tags, and zero padding after a tag that its size does not
     * count, are skipped too. The MPEG decoder does not skip ID3v2 itself: UTF-16 text frames start
     * with {@code FF FE}, which it would take for a Layer I frame header.
     */
    static int mpegStart(byte[] encoded) {
        int offset = 0;
        while (encoded.length - offset >= 10 && ascii(encoded, offset, "ID3")) {
            int length = id3Length(encoded, offset);
            if (length < 0) {
                return -1;
            }
            offset += length;
            while (offset < encoded.length && encoded[offset] == 0) {
                offset++;
            }
        }
        return offset;
    }

    /**
     * End of the MPEG data that starts at {@code start}: tags appended after the last frame (ID3v1,
     * APEv2, and ID3v2 with or without a footer, in any order) are excluded. The decoder scans
     * every byte for frame sync, so sync-like bytes in tag text would otherwise decode as a bogus
     * frame: noise, a decoder exception, or running out of data near the end of the track.
     */
    static int mpegEnd(byte[] encoded, int start) {
        int end = encoded.length;
        for (int tag = trailingTag(encoded, start + 4, end);
                tag >= 0;
                tag = trailingTag(encoded, start + 4, end)) {
            end = tag;
        }
        return end;
    }

    /** Start of a recognized tag that ends exactly at {@code end} and starts at or after floor. */
    private static int trailingTag(byte[] encoded, int floor, int end) {
        // ID3v1 has no size or checksum, so "TAG" at the right offset is the only evidence. Audio
        // that happens to have those bytes there loses at most one final frame (a 1 in 2^24
        // chance per file). Rejecting blocks that contain frame sync would be no safer: sync-like
        // bytes in the tag text are exactly what makes the decoder fail.
        if (end - 128 >= floor && ascii(encoded, end - 128, "TAG")) {
            return end - 128;
        }
        if (end - 32 >= floor && ascii(encoded, end - 32, "APETAGEX")) {
            // The size counts the items and the footer; a flagged header adds 32 bytes before them.
            // A corrupt size must not cut audio, so the items have to fill the declared size
            // exactly,
            // and a flagged header has to be where the size puts it.
            long size = littleEndian(encoded, end - 20);
            boolean header = (littleEndian(encoded, end - 12) & 0x8000_0000L) != 0;
            long length = size + (header ? 32 : 0);
            if (size >= 32
                    && length <= end - floor
                    && (!header || ascii(encoded, (int) (end - length), "APETAGEX"))
                    && apeItemsEnd(encoded, (int) (end - size), littleEndian(encoded, end - 16))
                            == end - 32) {
                return (int) (end - length);
            }
        }
        // A trailing ID3v2 tag is found by its header: one whose declared length, including any
        // ID3v2.4 footer, reaches exactly to the end. A footer alone proves nothing more, since it
        // only repeats the header's size.
        for (int tag = end - 10; tag >= floor; tag--) {
            if (encoded[tag] == 'I'
                    && encoded[tag + 3] >= 2
                    && encoded[tag + 3] <= 4
                    && id3Length(encoded, tag) == end - tag) {
                return tag;
            }
        }
        return -1;
    }

    /**
     * End of {@code count} APEv2 items starting at offset, or -1 if they are malformed. Each item
     * is a value size, flags, a key of 2 to 255 printable ASCII characters ending in 0, and the
     * value.
     */
    private static long apeItemsEnd(byte[] encoded, int offset, long count) {
        long at = offset;
        for (long item = 0; item < count; item++) {
            if (at > encoded.length - 8) {
                return -1;
            }
            long value = littleEndian(encoded, (int) at);
            int key = (int) at + 8;
            int nul = key;
            while (nul < encoded.length && encoded[nul] >= 0x20 && encoded[nul] <= 0x7e) {
                nul++;
            }
            if (nul >= encoded.length || encoded[nul] != 0 || nul - key < 2 || nul - key > 255) {
                return -1;
            }
            at = nul + 1 + value;
        }
        return at;
    }

    /** Whole length of an ID3v2 tag whose header is at offset, or -1 without a valid header. */
    private static int id3Length(byte[] encoded, int offset) {
        if (offset < 0 || encoded.length - offset < 10 || !ascii(encoded, offset, "ID3")) {
            return -1;
        }
        int size = syncsafe(encoded, offset + 6);
        return size < 0 ? -1 : 10 + size + ((encoded[offset + 5] & 0x10) != 0 ? 10 : 0);
    }

    private static int syncsafe(byte[] encoded, int offset) {
        int size = 0;
        for (int i = offset; i < offset + 4; i++) {
            if ((encoded[i] & 0x80) != 0) {
                return -1;
            }
            size = size << 7 | encoded[i];
        }
        return size;
    }

    private static long littleEndian(byte[] encoded, int offset) {
        return (encoded[offset] & 0xffL)
                | (encoded[offset + 1] & 0xffL) << 8
                | (encoded[offset + 2] & 0xffL) << 16
                | (encoded[offset + 3] & 0xffL) << 24;
    }

    private static boolean ascii(byte[] encoded, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (encoded[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Messages starting with {@code "World audio "} (here and in {@link PcmStream}) are shown to
     * the user as is, so they must never include paths or other local details.
     */
    public static PcmStream open(byte[] encoded, BooleanSupplier canceled) throws IOException {
        check(canceled);
        if (encoded.length < 32 || encoded.length > MAX_ENCODED) {
            throw new IOException("World audio requires bounded encoded input");
        }
        var codec = codec(encoded);
        if (codec == Codec.UNSUPPORTED) {
            throw new IOException("World audio requires Ogg Vorbis or MPEG-1 audio");
        }
        if (codec == Codec.VORBIS) {
            OggVoiceInput.validate(encoded, canceled);
        }
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-Xmx128m",
                                "-XX:MaxMetaspaceSize=48m",
                                "-cp",
                                workerClasspath(),
                                AudioStreamWorker.class.getName(),
                                codec.name())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        return PcmStream.start(process, encoded, canceled, STALL_NANOS);
    }

    private static String workerClasspath() throws IOException {
        try {
            var paths = new java.util.LinkedHashSet<String>();
            for (Class<?> type :
                    new Class<?>[] {
                        AudioStreamWorker.class,
                        de.jarnbjo.vorbis.VorbisStream.class,
                        fr.delthas.javamp3.Sound.class
                    }) {
                var source = type.getProtectionDomain().getCodeSource();
                if (source == null) {
                    throw new IOException("World audio decoder runtime path unavailable");
                }
                paths.add(Path.of(source.getLocation().toURI()).toString());
            }
            return String.join(java.io.File.pathSeparator, paths);
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("World audio decoder runtime path unavailable", invalid);
        }
    }

    static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
