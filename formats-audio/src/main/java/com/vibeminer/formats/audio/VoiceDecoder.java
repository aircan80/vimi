package com.vibeminer.formats.audio;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Finite Vorbis decoding in a heap- and time-bounded child JVM; no external codec executable. */
public final class VoiceDecoder {
    public static final String REVISION = "voice-vorbis-jogg-1.0.6-v1";
    public static final int MAX_ENCODED = 2 << 20;
    static final int MAGIC = 0x56504331;

    private VoiceDecoder() {}

    public static PcmClip decode(byte[] encoded, BooleanSupplier canceled) throws IOException {
        check(canceled);
        if (encoded.length < 32
                || encoded.length > MAX_ENCODED
                || encoded[0] != 'O'
                || encoded[1] != 'g'
                || encoded[2] != 'g'
                || encoded[3] != 'S') {
            throw new IOException("Voice preview requires bounded Ogg Vorbis input");
        }
        OggVoiceInput.validate(encoded, canceled);
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-Xmx64m",
                                "-XX:MaxMetaspaceSize=48m",
                                "-cp",
                                workerClasspath(),
                                VorbisWorker.class.getName())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    try (var input = process.getInputStream()) {
                        output.complete(input.readNBytes(PcmClip.MAX_BYTES + 17));
                    } catch (IOException failure) {
                        output.completeExceptionally(failure);
                    }
                });
        try {
            try (var input = process.getOutputStream()) {
                input.write(encoded);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check(canceled);
                if (System.nanoTime() > deadline) {
                    throw new IOException("Voice decoder exceeded its time bound");
                }
            }
            check(canceled);
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0
                    || bytes.length < 16
                    || bytes.length > PcmClip.MAX_BYTES + 16) {
                throw new IOException("Voice preview could not be decoded within its bounds");
            }
            return decodePcm(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failure) {
            throw new IOException("Voice decoder output unavailable", failure);
        } finally {
            process.destroyForcibly();
        }
    }

    public static PcmClip decodePcm(byte[] bytes) throws IOException {
        if (bytes.length < 16 || bytes.length > PcmClip.MAX_BYTES + 16) {
            throw new IOException("Invalid PCM derivative size");
        }
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != MAGIC) {
            throw new IOException("Unknown PCM derivative");
        }
        int rate = input.readInt(), channels = input.readInt(), length = input.readInt();
        if (length != bytes.length - 16) {
            throw new IOException("Truncated PCM derivative");
        }
        try {
            return new PcmClip(rate, channels, input.readNBytes(length));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid PCM derivative", invalid);
        }
    }

    public static byte[] encodePcm(PcmClip clip) {
        var buffer = java.nio.ByteBuffer.allocate(16 + clip.bytes().length);
        buffer.putInt(MAGIC)
                .putInt(clip.sampleRate())
                .putInt(clip.channels())
                .putInt(clip.bytes().length)
                .put(clip.bytes());
        return buffer.array();
    }

    private static String workerClasspath() throws IOException {
        try {
            var paths = new java.util.LinkedHashSet<String>();
            for (Class<?> type :
                    new Class<?>[] {VorbisWorker.class, de.jarnbjo.vorbis.VorbisStream.class}) {
                var source = type.getProtectionDomain().getCodeSource();
                if (source == null) {
                    throw new IOException("Voice decoder runtime path unavailable");
                }
                paths.add(Path.of(source.getLocation().toURI()).toString());
            }
            return String.join(java.io.File.pathSeparator, paths);
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Voice decoder runtime path unavailable", invalid);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
