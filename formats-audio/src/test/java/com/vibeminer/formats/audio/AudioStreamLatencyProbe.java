package com.vibeminer.formats.audio;

import java.io.IOException;
import java.util.Arrays;

/**
 * Measures the time from {@link AudioStreamDecoder#open} to the first decoded PCM block for the
 * authored tone, as a track start would see it. Each start follows an idle pause, as when a track
 * plays or a recorded silence runs between starts. Prints milliseconds; asserts nothing.
 */
public final class AudioStreamLatencyProbe {
    private AudioStreamLatencyProbe() {}

    public static void main(String[] args) throws Exception {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        long idleMillis = args.length > 1 ? Long.parseLong(args[1]) : 1500;
        String fixture = args.length > 2 ? args[2] : "authored-tone.ogg";
        byte[] encoded;
        try (var input = AudioStreamLatencyProbe.class.getResourceAsStream("/audio/" + fixture)) {
            encoded = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        double[] first = new double[runs];
        for (int i = 0; i < runs; i++) {
            Thread.sleep(idleMillis);
            long start = System.nanoTime();
            try (var stream = AudioStreamDecoder.open(encoded, () -> false)) {
                byte[] block = new byte[4096];
                if (stream.read(block, 0, block.length, () -> false) <= 0) {
                    throw new IOException("no audio");
                }
                first[i] = (System.nanoTime() - start) / 1e6;
                while (stream.read(block, 0, block.length, () -> false) >= 0) {
                    /* Drain the track as playback would. */
                }
            }
        }
        // The first start pays any one-time cost; report it apart from the steady starts.
        double[] steady = Arrays.copyOfRange(first, 1, runs);
        Arrays.sort(steady);
        System.out.printf(
                "World audio first-PCM latency (%s): first %.0f ms; next %d starts median %.0f ms,"
                        + " min %.0f ms, max %.0f ms%n",
                fixture,
                first[0],
                steady.length,
                steady[steady.length / 2],
                steady[0],
                steady[steady.length - 1]);
    }
}
