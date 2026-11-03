package com.vibeminer.formats.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class VoiceDecoderTest {
    private static byte[] tone() throws IOException {
        try (var input = VoiceDecoderTest.class.getResourceAsStream("/audio/authored-tone.ogg")) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }

    @Test
    void decodesAuthoredVorbisInIsolatedJvmAndRoundTripsPcm() throws IOException {
        var clip = VoiceDecoder.decode(tone(), () -> false);
        assertEquals(22050, clip.sampleRate());
        assertEquals(1, clip.channels());
        assertTrue(clip.seconds() > 0.1 && clip.seconds() < 0.3);
        byte[] samples = clip.bytes();
        assertTrue(
                java.util.stream.IntStream.range(0, samples.length).anyMatch(i -> samples[i] != 0));
        assertArrayEquals(
                clip.bytes(), VoiceDecoder.decodePcm(VoiceDecoder.encodePcm(clip)).bytes());
    }

    @Test
    void rejectsMalformedAndOversizeInputsAndHonorsCancellationBeforeFork() throws IOException {
        assertThrows(IOException.class, () -> VoiceDecoder.decode(new byte[32], () -> false));
        assertThrows(
                IOException.class,
                () -> VoiceDecoder.decode(new byte[VoiceDecoder.MAX_ENCODED + 1], () -> false));
        assertThrows(CancellationException.class, () -> VoiceDecoder.decode(tone(), () -> true));
        var full = tone();
        assertThrows(
                IOException.class,
                () ->
                        VoiceDecoder.decode(
                                java.util.Arrays.copyOf(full, full.length - 1), () -> false));
        var broken = tone();
        java.util.Arrays.fill(broken, 28, broken.length, (byte) 0);
        assertThrows(IOException.class, () -> VoiceDecoder.decode(broken, () -> false));
    }

    @Test
    void pcmDerivativeChecksDurationChannelsAndPayloadLength() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> new PcmClip(22050, 3, new byte[12]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PcmClip(22050, 1, new byte[22050 * 2 * 31]));
        var bytes = VoiceDecoder.encodePcm(new PcmClip(22050, 1, new byte[12]));
        assertThrows(
                IOException.class,
                () -> VoiceDecoder.decodePcm(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
    }
}
