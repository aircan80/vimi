package com.vibeminer.formats.audio;

/** Bounded signed 16-bit big-endian PCM audio. */
public record PcmClip(int sampleRate, int channels, byte[] bytes) {
    public static final int MAX_SECONDS = 30, MAX_BYTES = 48_000 * 2 * 2 * MAX_SECONDS;

    public PcmClip {
        if (sampleRate < 8_000
                || sampleRate > 48_000
                || channels < 1
                || channels > 2
                || bytes.length == 0
                || bytes.length % (channels * 2) != 0
                || bytes.length > (long) sampleRate * channels * 2 * MAX_SECONDS) {
            throw new IllegalArgumentException("Audio exceeds voice preview bounds");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public double seconds() {
        return (double) bytes.length / (sampleRate * channels * 2);
    }
}
