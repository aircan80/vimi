package com.vimi.gui;

import java.util.List;
import java.util.function.Predicate;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Output device capabilities shared by World audio and voice previews: which channel counts a
 * device accepts, and a stereo-to-mono downmix for devices that only play mono.
 */
final class AudioOutput {
    private AudioOutput() {}

    /**
     * Common 16-bit line formats, used only to ask what a device accepts; no line is created.
     * Channels and frame size must be concrete: {@code AudioFormat.matches} treats unspecified
     * values as wildcards only on the mixer's side, and Windows DirectSound advertises concrete
     * channel counts, so an unspecified probe matched no Windows device. Only the rate is left
     * open, which every mixer leaves unspecified too.
     */
    static final AudioFormat STEREO = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
            AudioSystem.NOT_SPECIFIED, 16, 2, 4, AudioSystem.NOT_SPECIFIED, true),
            MONO = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioSystem.NOT_SPECIFIED,
                    16, 1, 2, AudioSystem.NOT_SPECIFIED, true);
    static final List<AudioFormat> PROBES = List.of(STEREO, MONO);

    /** 2 for a stereo device, 1 for a mono-only device, 0 without a usable device. */
    static int channels(Predicate<AudioFormat> supported) {
        if (supported.test(STEREO)) return 2;
        return supported.test(MONO) ? 1 : 0;
    }

    /**
     * The default output device's channels; 0 when the sound system cannot be asked. {@code
     * isLineSupported} asks every installed mixer, which matches {@code getSourceDataLine}: it
     * opens a line on whichever mixer supports the requested format.
     */
    static int channels() {
        try {
            return channels(format -> AudioSystem.isLineSupported(
                    new DataLine.Info(SourceDataLine.class, format)));
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /**
     * Channels to open a line with for a stream: mono on a mono-only device, otherwise the
     * stream's own. An unknown device (0) keeps the stream's channels, so opening the line
     * reports the failure as before.
     */
    static int lineChannels(int stream, int device) {
        return device == 1 ? 1 : stream;
    }

    /**
     * Downmixes big-endian 16-bit stereo frames in place to mono, (L + R) / 2 per frame, and
     * returns the mono byte count. The sum is taken in int, so it cannot overflow, and the mean
     * of two 16-bit samples is always a 16-bit sample: no clamping is needed. Division truncates
     * toward zero, so the rounding is symmetric around silence. A partial frame is dropped.
     */
    static int downmix(byte[] block, int count) {
        int out = 0;
        for (int i = 0; i + 4 <= count; i += 4) {
            int left = (short) (block[i] << 8 | block[i + 1] & 0xff);
            int right = (short) (block[i + 2] << 8 | block[i + 3] & 0xff);
            int mono = (left + right) / 2;
            block[out++] = (byte) (mono >> 8);
            block[out++] = (byte) mono;
        }
        return out;
    }
}
