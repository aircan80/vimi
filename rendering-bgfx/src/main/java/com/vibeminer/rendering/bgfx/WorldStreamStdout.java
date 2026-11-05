package com.vibeminer.rendering.bgfx;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Helper stdout, split into protocol records and unrelated output. Only a line that starts with the
 * exact {@code "WS2 "} prefix is protocol, and it is held to {@link WorldStreamStatus}'s strict
 * rules: a malformed record or a foreign nonce still ends the session. Any other line (for example
 * JVM or JFR startup text) is noise: it is never decoded, only counted and kept as a bounded,
 * redacted sample for diagnostics. Noise ends only at a newline, so nothing inside a noise line,
 * however long, can begin a record. A session that writes more than {@link #MAX_NOISE_BYTES} of
 * noise is ended as a flood.
 *
 * <p>A noise line that contains {@code "WS2 "} or this session's nonce anywhere is a mangled record
 * (a lost prefix byte, a byte-order mark, a spliced write), not unrelated output: it ends the
 * session at once, so a lost credit or camera record cannot stall it silently. JVM and JFR text
 * never carries the per-session nonce.
 *
 * <p>One reader per session, used by its single status reader thread; the counters and sample may
 * be read from any thread.
 */
final class WorldStreamStdout {
    static final long MAX_NOISE_BYTES = 1L << 20;
    private static final byte[] PREFIX = "WS2 ".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_RECORD = 256;

    private final HelperStderrTail sample = new HelperStderrTail("stdout");
    private final Consumer<String> firstNoise;
    private final Scanner prefix, nonce;
    private volatile long noiseBytes, noiseLines;

    /**
     * @param nonce this session's nonce as its 32 lowercase hex digits, as records carry it
     * @param firstNoise called once, on the reader thread, when the first noise line ends (or the
     *     stream ends inside it), with its redacted text; it must not throw or block for long
     */
    WorldStreamStdout(String nonce, Consumer<String> firstNoise) {
        if (!Objects.requireNonNull(nonce).matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("World status nonce");
        }
        this.prefix = new Scanner(PREFIX);
        this.nonce = new Scanner(nonce.getBytes(StandardCharsets.US_ASCII));
        this.firstNoise = Objects.requireNonNull(firstNoise);
    }

    /** As records carry it: the nonce's high and low halves as 32 lowercase hex digits. */
    static String hex(WorldStreamState.Nonce nonce) {
        return java.util.HexFormat.of().toHexDigits(nonce.high())
                + java.util.HexFormat.of().toHexDigits(nonce.low());
    }

    /** The next protocol record, skipping noise; null on EOF at a line boundary. */
    WorldStreamStatus.Message read(InputStream input) throws IOException {
        Objects.requireNonNull(input);
        byte[] line = new byte[MAX_RECORD];
        while (true) {
            int matched = 0;
            int value = -1;
            while (matched < PREFIX.length) {
                value = input.read();
                if (value == -1) {
                    if (matched > 0) {
                        noise(line, matched, true);
                    }
                    return null;
                }
                line[matched] = (byte) value;
                if (value != PREFIX[matched]) {
                    break;
                }
                matched++;
            }
            if (matched == PREFIX.length) {
                return record(input, line);
            }
            // Bytes read so far, the mismatching one included, belong to this noise line.
            if (value == '\n') {
                noise(line, matched + 1, true);
                continue;
            }
            noise(line, matched + 1, false);
            if (!skipNoise(input, line)) {
                return null;
            }
        }
    }

    /** The rest of a prefixed line, under the same rules as {@link WorldStreamStatus#read}. */
    private static WorldStreamStatus.Message record(InputStream input, byte[] line)
            throws IOException {
        for (int i = PREFIX.length; i < line.length; i++) {
            int value = input.read();
            if (value == -1) {
                throw new EOFException("Truncated World status");
            }
            line[i] = (byte) value;
            if (value == '\n') {
                return WorldStreamStatus.decode(line, i + 1);
            }
            if (value < 32 || value > 126) {
                throw new IOException("Non-ASCII World status");
            }
        }
        throw new IOException("World status exceeds line limit");
    }

    /** Consumes the rest of a noise line; false if the stream ended inside it. */
    private boolean skipNoise(InputStream input, byte[] buffer) throws IOException {
        int length = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                noise(buffer, length, true);
                return false;
            }
            buffer[length++] = (byte) value;
            if (value == '\n') {
                noise(buffer, length, true);
                return true;
            }
            if (length == buffer.length) {
                noise(buffer, length, false);
                length = 0;
            }
        }
    }

    private void noise(byte[] bytes, int length, boolean ends) throws IOException {
        long total = noiseBytes + length;
        if (total > MAX_NOISE_BYTES) {
            noiseBytes = total;
            throw new IOException(
                    "World renderer flooded stdout: more than "
                            + MAX_NOISE_BYTES
                            + " bytes of unrelated output");
        }
        noiseBytes = total;
        sample.accept(bytes, 0, length);
        boolean mangled = false;
        for (int i = 0; i < length; i++) {
            // Both scanners always see every byte, so neither loses its place.
            mangled |= prefix.feed(bytes[i]) | nonce.feed(bytes[i]);
        }
        if (mangled) {
            // The line itself reaches the log only through the redacted sample.
            throw new IOException(
                    "Mangled World status: protocol text inside an unrelated stdout line");
        }
        if (ends) {
            prefix.reset();
            nonce.reset();
            if (length == 0 || bytes[length - 1] != '\n') {
                // A stream that ends inside a noise line: close it in the sample too.
                sample.accept(new byte[] {'\n'}, 0, 1);
            }
            if (++noiseLines == 1) {
                firstNoise.accept(sample.snapshot().strip());
            }
        }
    }

    /** Streaming substring search (Knuth-Morris-Pratt), so a noise line is never buffered. */
    private static final class Scanner {
        private final byte[] pattern;
        private final int[] fallback;
        private int state;

        Scanner(byte[] pattern) {
            this.pattern = pattern;
            fallback = new int[pattern.length];
            for (int i = 1, k = 0; i < pattern.length; i++) {
                while (k > 0 && pattern[i] != pattern[k]) {
                    k = fallback[k - 1];
                }
                if (pattern[i] == pattern[k]) {
                    k++;
                }
                fallback[i] = k;
            }
        }

        boolean feed(byte value) {
            while (state > 0 && pattern[state] != value) {
                state = fallback[state - 1];
            }
            if (pattern[state] == value) {
                state++;
            }
            if (state == pattern.length) {
                state = fallback[state - 1];
                return true;
            }
            return false;
        }

        void reset() {
            state = 0;
        }
    }

    long noiseBytes() {
        return noiseBytes;
    }

    long noiseLines() {
        return noiseLines;
    }

    /** Empty without noise; else a bounded, redacted diagnostic suffix for a log record. */
    String suffix() {
        long lines = noiseLines, bytes = noiseBytes;
        if (lines == 0 && bytes == 0) {
            return "";
        }
        return "; unrelated stdout ignored: "
                + lines
                + (lines == 1 ? " line, " : " lines, ")
                + bytes
                + (bytes == 1 ? " byte" : " bytes")
                + "; tail:\n"
                + sample.snapshot().stripTrailing();
    }
}
