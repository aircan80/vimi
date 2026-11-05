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
        int offset = 0;
        if (encoded.length >= 10 && encoded[0] == 'I' && encoded[1] == 'D' && encoded[2] == '3') {
            int size = 0;
            for (int i = 6; i < 10; i++) {
                if ((encoded[i] & 0x80) != 0) {
                    return Codec.UNSUPPORTED;
                }
                size = size << 7 | encoded[i];
            }
            offset = 10 + size + ((encoded[5] & 0x10) != 0 ? 10 : 0);
        }
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
