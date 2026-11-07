package com.vibeminer.formats.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /**
     * An ID3v2.3 album frame with UTF-16 text, as in game files. The text's byte-order mark {@code
     * FF FE} looks like an MPEG Layer I frame header.
     */
    private static final byte[] UTF16_ALBUM =
            concat(
                    "TALB".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    new byte[] {0, 0, 0, 11, 0, 0}, // size 11, no flags
                    new byte[] {1, (byte) 0xff, (byte) 0xfe, 'C', 0, '6', 0, '0', 0, 0, 0});

    private static byte[] concat(byte[]... parts) {
        var all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            all.writeBytes(part);
        }
        return all.toByteArray();
    }

    /**
     * Game music carries an ID3v2 tag with UTF-16 text frames. Their byte-order mark {@code FF FE}
     * looks like an MPEG Layer I frame header, so a decoder that scans the tag for frame sync
     * decodes garbage. The worker must start at the first frame after the tag.
     */
    @Test
    void skipsId3TagsWhoseTextLooksLikeFrameSync() throws IOException {
        byte[] tagged = fixture("authored-tone.mp3");
        int body = 10 + 10; // the fixture's own ID3 header and its 10 padding bytes
        byte[] frame = UTF16_ALBUM;
        var withText = new ByteArrayOutputStream();
        withText.write(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, (byte) frame.length});
        withText.write(frame);
        withText.write(tagged, body, tagged.length - body);
        byte[] expected;
        try (var stream = AudioStreamDecoder.open(tagged, () -> false)) {
            expected = drain(stream);
        }
        try (var stream = AudioStreamDecoder.open(withText.toByteArray(), () -> false)) {
            assertEquals(44100, stream.sampleRate());
            assertEquals(2, stream.channels());
            assertArrayEquals(expected, drain(stream));
        }
    }

    private static byte[] decode(byte[] encoded) throws IOException {
        try (var stream = AudioStreamDecoder.open(encoded, () -> false)) {
            return drain(stream);
        }
    }

    /** Sync-like bytes as they can appear inside tag text. */
    private static final byte[] SYNC_TEXT = {(byte) 0xff, (byte) 0xfe, (byte) 0xff, (byte) 0xfb};

    private static byte[] id3v2(int version, boolean footer, byte[] frames) {
        int size = frames.length;
        byte[] syncsafe = {
            (byte) (size >> 21 & 0x7f),
            (byte) (size >> 14 & 0x7f),
            (byte) (size >> 7 & 0x7f),
            (byte) (size & 0x7f)
        };
        byte flags = footer ? (byte) 0x10 : 0;
        var tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[] {'I', 'D', '3', (byte) version, 0, flags});
        tag.writeBytes(syncsafe);
        tag.writeBytes(frames);
        if (footer) {
            tag.writeBytes(new byte[] {'3', 'D', 'I', (byte) version, 0, flags});
            tag.writeBytes(syncsafe);
        }
        return tag.toByteArray();
    }

    private static byte[] id3v1() {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        System.arraycopy(SYNC_TEXT, 0, tag, 3, SYNC_TEXT.length);
        tag[127] = (byte) 0xff;
        return tag;
    }

    private static byte[] apev2() {
        return apev2(true, 0);
    }

    /** An APEv2 tag; {@code overstated} bytes are added to the size its blocks declare. */
    private static byte[] apev2(boolean header, int overstated) {
        var item = new ByteArrayOutputStream();
        item.writeBytes(le(SYNC_TEXT.length));
        item.writeBytes(le(0));
        item.writeBytes("Album\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        item.writeBytes(SYNC_TEXT);
        int size = item.size() + 32 + overstated;
        return concat(
                header ? apeBlock(size, 0xa000_0000) : new byte[0],
                item.toByteArray(),
                apeBlock(size, header ? 0x8000_0000 : 0));
    }

    private static byte[] apeBlock(int size, int flags) {
        return concat(
                "APETAGEX".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                le(2000),
                le(size),
                le(1),
                le(flags),
                new byte[8]);
    }

    private static byte[] le(int value) {
        return new byte[] {
            (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
        };
    }

    /**
     * Tags after the last frame can hold sync-like bytes. The MPEG decoder takes them for a frame
     * header and runs out of data, so the track used to fail near its end and lose its last block.
     */
    @Test
    void ignoresTrailingTagsWhoseTextLooksLikeFrameSync() throws IOException {
        byte[] audio = fixture("authored-tone.mp3");
        byte[] expected = decode(audio);
        assertEquals(audio.length, AudioStreamDecoder.mpegEnd(audio, 20));
        var trailers =
                java.util.Map.of(
                        "ID3v1", id3v1(),
                        "ID3v2.3", id3v2(3, false, UTF16_ALBUM),
                        "ID3v2.4 with footer", id3v2(4, true, UTF16_ALBUM),
                        "APEv2", apev2(),
                        "APEv2 without header", apev2(false, 0),
                        "ID3v2.3, APEv2 and ID3v1",
                                concat(id3v2(3, false, UTF16_ALBUM), apev2(), id3v1()));
        for (var trailer : trailers.entrySet()) {
            byte[] tagged = concat(audio, trailer.getValue());
            assertArrayEquals(expected, decode(tagged), trailer.getKey());
            assertEquals(audio.length, AudioStreamDecoder.mpegEnd(tagged, 20), trailer.getKey());
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Lyrics3 v1: LYRICSBEGIN, lyrics holding sync-like bytes, LYRICSEND; no size field. */
    private static byte[] lyrics3v1() {
        return concat(ascii("LYRICSBEGIN"), ascii("la "), SYNC_TEXT, ascii("LYRICSEND"));
    }

    /**
     * Lyrics3 v2: LYRICSBEGIN, fields, a six-digit size counting from LYRICSBEGIN to the size, then
     * LYRICS200. {@code misstated} is added to the recorded size.
     */
    private static byte[] lyrics3v2(int misstated) {
        byte[] body =
                concat(
                        ascii("LYRICSBEGIN"),
                        ascii("IND00002"),
                        ascii("11"),
                        ascii("LYR00007"),
                        ascii("la "),
                        SYNC_TEXT);
        return concat(
                body, ascii(String.format("%06d", body.length + misstated)), ascii("LYRICS200"));
    }

    /** Enhanced ID3v1: "TAG+" and 223 bytes of text, before the 128-byte ID3v1 block. */
    private static byte[] enhancedId3v1() {
        byte[] block = new byte[227];
        System.arraycopy(ascii("TAG+"), 0, block, 0, 4);
        System.arraycopy(SYNC_TEXT, 0, block, 4, SYNC_TEXT.length);
        System.arraycopy(SYNC_TEXT, 0, block, 223, SYNC_TEXT.length);
        return concat(block, id3v1());
    }

    /**
     * Lyrics3 (v1 and v2, before ID3v1 or last) and enhanced ID3v1 trailers can hold sync-like
     * bytes; they are excluded as far as their recorded sizes allow, and the track plays whole.
     */
    @Test
    void ignoresLyrics3AndEnhancedId3v1Trailers() throws IOException {
        byte[] audio = fixture("authored-tone.mp3");
        byte[] expected = decode(audio);
        var trailers =
                java.util.Map.of(
                        "Lyrics3 v1 and ID3v1", concat(lyrics3v1(), id3v1()),
                        "Lyrics3 v1", lyrics3v1(),
                        "Lyrics3 v2 and ID3v1", concat(lyrics3v2(0), id3v1()),
                        "Lyrics3 v2", lyrics3v2(0),
                        "TAG+ and ID3v1", enhancedId3v1(),
                        "APEv2, Lyrics3 v2 and ID3v1", concat(apev2(), lyrics3v2(0), id3v1()));
        for (var trailer : trailers.entrySet()) {
            byte[] tagged = concat(audio, trailer.getValue());
            assertEquals(audio.length, AudioStreamDecoder.mpegEnd(tagged, 20), trailer.getKey());
            assertArrayEquals(expected, decode(tagged), trailer.getKey());
        }
    }

    /** A Lyrics3 size that does not reach exactly to its LYRICSBEGIN cuts nothing. */
    @Test
    void keepsAudioWhenALyrics3SizeIsMalformed() {
        byte[] audio = new byte[4096];
        int recorded = lyrics3v2(0).length - 15;
        for (int misstated : new int[] {-1, 1, 1000, 999_999 - recorded}) {
            byte[] lyrics = lyrics3v2(misstated);
            byte[] tagged = concat(audio, lyrics, id3v1());
            assertEquals(
                    audio.length + lyrics.length,
                    AudioStreamDecoder.mpegEnd(tagged, 20),
                    "misstated by " + misstated);
        }
        byte[] digits = lyrics3v2(0);
        digits[digits.length - 12] = 'x'; // not a decimal size
        assertEquals(
                audio.length + digits.length,
                AudioStreamDecoder.mpegEnd(concat(audio, digits), 20));
        // A v1 tag whose LYRICSBEGIN is further than the 5100-byte lyrics limit is refused.
        byte[] far = concat(ascii("LYRICSBEGIN"), new byte[5101], ascii("LYRICSEND"));
        assertEquals(audio.length + far.length, AudioStreamDecoder.mpegEnd(concat(audio, far), 20));
        byte[] limit = concat(ascii("LYRICSBEGIN"), new byte[5100], ascii("LYRICSEND"));
        assertEquals(audio.length, AudioStreamDecoder.mpegEnd(concat(audio, limit), 20));
        // A lone TAG+ block without its ID3v1 block is not a tag.
        byte[] lone = java.util.Arrays.copyOf(enhancedId3v1(), 227);
        assertEquals(audio.length + 227, AudioStreamDecoder.mpegEnd(concat(audio, lone), 20));
    }

    /** A trailing tag whose declared size reaches into the audio must not cut any of it. */
    @Test
    void keepsAudioWhenAnApeFooterOverstatesItsSize() throws IOException {
        byte[] audio = fixture("authored-tone.mp3");
        for (boolean header : new boolean[] {true, false}) {
            byte[] tagged = concat(audio, apev2(header, 1000));
            assertEquals(
                    tagged.length,
                    AudioStreamDecoder.mpegEnd(tagged, 20),
                    header ? "with header" : "without header");
        }
    }

    /**
     * Some taggers leave zero padding after a leading ID3v2 tag that its size does not count, or
     * write a second tag after the first. The first frame follows both.
     */
    @Test
    void skipsUncountedPaddingAndConsecutiveLeadingTags() throws IOException {
        byte[] audio = fixture("authored-tone.mp3");
        byte[] expected = decode(audio);
        byte[] frames = Arrays.copyOfRange(audio, 20, audio.length);
        var leaders =
                java.util.Map.of(
                        "uncounted padding", concat(id3v2(3, false, UTF16_ALBUM), new byte[37]),
                        "two tags",
                                concat(id3v2(3, false, UTF16_ALBUM), id3v2(4, true, UTF16_ALBUM)),
                        "two tags and padding",
                                concat(
                                        id3v2(4, true, UTF16_ALBUM),
                                        new byte[5],
                                        id3v2(3, false, UTF16_ALBUM),
                                        new byte[3]));
        for (var leader : leaders.entrySet()) {
            byte[] tagged = concat(leader.getValue(), frames);
            assertEquals(AudioStreamDecoder.Codec.MPEG, AudioStreamDecoder.codec(tagged));
            assertEquals(
                    leader.getValue().length,
                    AudioStreamDecoder.mpegStart(tagged),
                    leader.getKey());
            assertArrayEquals(expected, decode(tagged), leader.getKey());
        }
        // Only padding after a tag is skipped: zeros alone are still not MPEG audio.
        assertEquals(
                AudioStreamDecoder.Codec.UNSUPPORTED,
                AudioStreamDecoder.codec(concat(new byte[8], frames)));
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

    /**
     * The stream reports that its decoder finished while the decoded tail is still buffered, so a
     * player can open what follows before the audio runs out. Nothing is read while waiting, so the
     * decoder can only finish if the whole fixture fits the stream's queue; that size assumption is
     * checked first, so a larger fixture fails with that reason, not a timeout.
     */
    @Test
    void reportsDecodingFinishedWhileTheTailIsStillBuffered() throws Exception {
        int queued = PcmStream.QUEUED_CHUNKS * PcmStream.CHUNK_BYTES;
        int whole = decode(fixture("authored-tone.ogg")).length;
        assertTrue(
                whole <= queued,
                "fixture PCM (" + whole + " bytes) must fit the " + queued + "-byte queue");
        try (var stream = AudioStreamDecoder.open(fixture("authored-tone.ogg"), () -> false)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!stream.decoded() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(stream.decoded(), "decoder never finished");
            byte[] tail = drain(stream);
            assertTrue(tail.length > 0, "nothing was left to play");
            assertTrue(audible(tail));
        }
    }
}
