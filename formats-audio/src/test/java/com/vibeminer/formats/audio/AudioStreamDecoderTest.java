package com.vibeminer.formats.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class AudioStreamDecoderTest {
    private static byte[] fixture(String name) throws IOException {
        try (var input = AudioStreamDecoderTest.class.getResourceAsStream("/audio/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }

    private static byte[] drain(PcmStream stream) throws IOException {
        var all = new ByteArrayOutputStream();
        byte[] block = new byte[1001];
        int count;
        while ((count = stream.read(block, 0, block.length, () -> false)) >= 0) {
            assertEquals(0, count % (stream.channels() * 2), "reads return whole frames");
            all.write(block, 0, count);
        }
        return all.toByteArray();
    }

    private static boolean audible(byte[] samples) {
        for (int i = 0; i + 1 < samples.length; i += 2) {
            if (Math.abs((short) (samples[i] << 8 | samples[i + 1] & 0xff)) > 1000) {
                return true;
            }
        }
        return false;
    }

    @Test
    void identifiesContainersFromBytesOnly() throws IOException {
        assertEquals(
                AudioStreamDecoder.Codec.VORBIS,
                AudioStreamDecoder.codec(fixture("authored-tone.ogg")));
        assertEquals(
                AudioStreamDecoder.Codec.MPEG,
                AudioStreamDecoder.codec(fixture("authored-tone.mp3")));
        assertEquals(
                AudioStreamDecoder.Codec.MPEG,
                AudioStreamDecoder.codec(new byte[] {(byte) 0xff, (byte) 0xfb, 0x50, 0x64}));
        assertEquals(AudioStreamDecoder.Codec.UNSUPPORTED, AudioStreamDecoder.codec(new byte[64]));
        // RIFF/WAVE and MPEG-2.5 headers are explicitly outside the reviewed decoders.
        assertEquals(
                AudioStreamDecoder.Codec.UNSUPPORTED,
                AudioStreamDecoder.codec(
                        "RIFF....WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(
                AudioStreamDecoder.Codec.UNSUPPORTED,
                AudioStreamDecoder.codec(new byte[] {(byte) 0xff, (byte) 0xe3, 0x50, 0x64}));
    }

    @Test
    void streamsAuthoredVorbisAsBigEndianPcm() throws IOException {
        try (var stream = AudioStreamDecoder.open(fixture("authored-tone.ogg"), () -> false)) {
            assertEquals(22050, stream.sampleRate());
            assertEquals(1, stream.channels());
            byte[] pcm = drain(stream);
            double seconds = pcm.length / (22050.0 * 2);
            assertTrue(seconds > 0.1 && seconds < 0.3, "duration " + seconds);
            assertTrue(audible(pcm));
            assertEquals(-1, stream.read(new byte[2], 0, 2, () -> false));
        }
    }

    @Test
    void streamsAuthoredMpegAsBigEndianPcm() throws IOException {
        try (var stream = AudioStreamDecoder.open(fixture("authored-tone.mp3"), () -> false)) {
            assertEquals(44100, stream.sampleRate());
            assertEquals(2, stream.channels());
            byte[] pcm = drain(stream);
            double seconds = pcm.length / (44100.0 * 4);
            assertTrue(seconds > 0.4 && seconds < 0.7, "duration " + seconds);
            // Little-endian output interpreted as big-endian would be noise near full scale.
            assertTrue(audible(pcm));
            int peak = 0;
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                peak = Math.max(peak, Math.abs((short) (pcm[i] << 8 | pcm[i + 1] & 0xff)));
            }
            assertTrue(peak < 32000, "authored tone is not full-scale noise: " + peak);
        }
    }

    @Test
    void rejectsMalformedOversizeAndCanceledInput() throws IOException {
        assertThrows(IOException.class, () -> AudioStreamDecoder.open(new byte[64], () -> false));
        assertThrows(
                IOException.class,
                () ->
                        AudioStreamDecoder.open(
                                new byte[AudioStreamDecoder.MAX_ENCODED + 1], () -> false));
        assertThrows(
                CancellationException.class,
                () -> AudioStreamDecoder.open(fixture("authored-tone.ogg"), () -> true));
        var ogg = fixture("authored-tone.ogg");
        assertThrows(
                IOException.class,
                () -> AudioStreamDecoder.open(Arrays.copyOf(ogg, ogg.length - 1), () -> false));
        var mpeg = fixture("authored-tone.mp3");
        byte[] header = Arrays.copyOf(mpeg, 64);
        Arrays.fill(header, 24, 64, (byte) 0);
        // A frame sync without decodable frames fails as a decoder error, never as silence.
        assertThrows(
                IOException.class,
                () -> {
                    try (var stream = AudioStreamDecoder.open(header, () -> false)) {
                        drain(stream);
                    }
                });
    }

    @Test
    void closeDestroysTheDecoderAndStallsFailWithinTheirBound() throws Exception {
        var stream = AudioStreamDecoder.open(fixture("authored-tone.mp3"), () -> false);
        stream.close();
        assertThrows(
                CancellationException.class, () -> stream.read(new byte[4], 0, 4, () -> false));

        var stalled =
                new ProcessBuilder(
                                java.nio.file.Path.of(
                                                System.getProperty("java.home"),
                                                "bin",
                                                System.getProperty("os.name").startsWith("Windows")
                                                        ? "java.exe"
                                                        : "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                StalledVoiceWorker.class.getName())
                        .start();
        long started = System.nanoTime();
        assertThrows(
                IOException.class,
                () ->
                        PcmStream.start(
                                stalled,
                                fixture("authored-tone.ogg"),
                                () -> false,
                                TimeUnit.MILLISECONDS.toNanos(300)));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
        assertTrue(stalled.waitFor(5, TimeUnit.SECONDS), "stalled decoder is destroyed");
    }
}
