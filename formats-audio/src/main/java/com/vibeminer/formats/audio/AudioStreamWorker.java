package com.vibeminer.formats.audio;

import de.jarnbjo.ogg.BasicStream;
import de.jarnbjo.ogg.EndOfOggStreamException;
import de.jarnbjo.ogg.LogicalOggStream;
import de.jarnbjo.vorbis.VorbisStream;
import fr.delthas.javamp3.Sound;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Memory-isolated streaming codec entry point. Stdout carries only the header and big-endian PCM
 * blocks; diagnostics are never written to it.
 */
public final class AudioStreamWorker {
    private AudioStreamWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Codec required");
        }
        var codec = AudioStreamDecoder.Codec.valueOf(args[0]);
        byte[] encoded = System.in.readNBytes(AudioStreamDecoder.MAX_ENCODED + 1);
        if (encoded.length > AudioStreamDecoder.MAX_ENCODED
                || AudioStreamDecoder.codec(encoded) != codec) {
            throw new IllegalArgumentException("Encoded audio exceeds bounds");
        }
        var output = new DataOutputStream(new BufferedOutputStream(System.out, 16_384));
        switch (codec) {
            case VORBIS -> vorbis(encoded, output);
            case MPEG -> mpeg(encoded, output);
            default -> throw new IllegalArgumentException("Unsupported codec");
        }
        output.flush();
    }

    private static void vorbis(byte[] encoded, DataOutputStream output) throws Exception {
        var ogg = new BasicStream(new ByteArrayInputStream(encoded));
        try {
            if (ogg.getLogicalStreams().size() != 1) {
                throw new IllegalArgumentException("One Vorbis stream required");
            }
            Object logical = ogg.getLogicalStreams().iterator().next();
            if (!(logical instanceof LogicalOggStream stream)
                    || !LogicalOggStream.FORMAT_VORBIS.equals(stream.getFormat())) {
                throw new IllegalArgumentException("Vorbis required");
            }
            var vorbis = new VorbisStream(stream);
            try {
                var header = vorbis.getIdentificationHeader();
                var limit = new Limit(header.getSampleRate(), header.getChannels(), output);
                byte[] block = new byte[8192];
                try {
                    while (true) {
                        int count = vorbis.readPcm(block, 0, block.length);
                        if (count < 0) {
                            break;
                        }
                        limit.write(block, count);
                    }
                } catch (EndOfOggStreamException complete) {
                    /* A complete finite Ogg stream. */
                }
            } finally {
                vorbis.close();
            }
        } finally {
            ogg.close();
        }
    }

    private static void mpeg(byte[] encoded, DataOutputStream output) throws IOException {
        // codec() already checked a frame header at this offset, after any ID3v2 tag.
        int start = AudioStreamDecoder.mpegStart(encoded);
        try (var sound =
                new Sound(new ByteArrayInputStream(encoded, start, encoded.length - start))) {
            var limit = new Limit(sound.getSamplingFrequency(), sound.isStereo() ? 2 : 1, output);
            byte[] block = new byte[8192];
            while (true) {
                int count = sound.readNBytes(block, 0, block.length);
                if (count <= 0) {
                    break;
                }
                if (count % 2 != 0) {
                    throw new IllegalArgumentException("Split MPEG sample");
                }
                // The MPEG decoder contract is little-endian; the stream contract is big-endian.
                for (int i = 0; i < count; i += 2) {
                    byte low = block[i];
                    block[i] = block[i + 1];
                    block[i + 1] = low;
                }
                limit.write(block, count);
            }
        }
    }

    private static final class Limit {
        private final DataOutputStream output;
        private final long maximum;
        private long written;

        Limit(int rate, int channels, DataOutputStream output) throws IOException {
            if (rate < 8_000 || rate > 48_000 || channels < 1 || channels > 2) {
                throw new IllegalArgumentException("Audio format exceeds bounds");
            }
            this.output = output;
            maximum = (long) rate * channels * 2 * AudioStreamDecoder.MAX_SECONDS;
            output.writeInt(AudioStreamDecoder.MAGIC);
            output.writeInt(rate);
            output.writeInt(channels);
            output.flush();
        }

        void write(byte[] block, int count) throws IOException {
            if (written > maximum - count) {
                throw new IllegalArgumentException("Audio duration exceeds bounds");
            }
            output.write(block, 0, count);
            written += count;
        }
    }
}
