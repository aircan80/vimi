package com.vibeminer.formats.audio;

import de.jarnbjo.ogg.BasicStream;
import de.jarnbjo.ogg.EndOfOggStreamException;
import de.jarnbjo.ogg.LogicalOggStream;
import de.jarnbjo.vorbis.VorbisStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/** Memory-isolated codec entry point; protocol stdout is never used for diagnostics. */
public final class VorbisWorker {
    private VorbisWorker() {}

    public static void main(String[] args) throws Exception {
        byte[] encoded = System.in.readNBytes(VoiceDecoder.MAX_ENCODED + 1);
        if (encoded.length > VoiceDecoder.MAX_ENCODED) {
            throw new IllegalArgumentException("Encoded voice exceeds bounds");
        }
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
                int rate = header.getSampleRate(), channels = header.getChannels();
                if (rate < 8_000 || rate > 48_000 || channels < 1 || channels > 2) {
                    throw new IllegalArgumentException("Voice format exceeds bounds");
                }
                int maximum = rate * channels * 2 * PcmClip.MAX_SECONDS;
                var pcm = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                try {
                    while (true) {
                        int count = vorbis.readPcm(block, 0, block.length);
                        if (count < 0) {
                            break;
                        }
                        if (pcm.size() > maximum - count) {
                            throw new IllegalArgumentException("Voice duration exceeds bounds");
                        }
                        pcm.write(block, 0, count);
                    }
                } catch (EndOfOggStreamException complete) {
                    /* A complete finite Ogg stream. */
                }
                var clip = new PcmClip(rate, channels, pcm.toByteArray());
                var output = new DataOutputStream(System.out);
                output.writeInt(VoiceDecoder.MAGIC);
                output.writeInt(rate);
                output.writeInt(channels);
                output.writeInt(clip.bytes().length);
                output.write(clip.bytes());
                output.flush();
            } finally {
                vorbis.close();
            }
        } finally {
            ogg.close();
        }
    }
}
