package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Helper stdout: strict protocol records, bounded unrelated noise that can never forge one. */
class WorldStreamStdoutTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String CLOSED = "WS2 " + NONCE + " CLOSED\n";
    private static final String CREDIT = "WS2 " + NONCE + " CREDIT 1 0 1\n";

    private final List<String> warnings = new ArrayList<>();
    private final WorldStreamStdout stdout = new WorldStreamStdout(NONCE, warnings::add);

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private List<WorldStreamStatus.Message> readAll(InputStream input) throws IOException {
        var messages = new ArrayList<WorldStreamStatus.Message>();
        WorldStreamStatus.Message message;
        while ((message = stdout.read(input)) != null) {
            messages.add(message);
        }
        return messages;
    }

    @Test
    void jfrStartupTextInterleavedWithRecordsIsIgnored() throws Exception {
        var input =
                stream(
                        "[0.012s][info][jfr,startup] Started recording 1. No limit specified,"
                                + " using maxsize=250MB as default.\r\n"
                                + "[0.013s][info][jfr,startup] Use jcmd 4242 JFR.dump"
                                + " filename=/home/tester/rec.jfr to copy recording data to"
                                + " file.\r\n"
                                + CREDIT
                                + "\n"
                                + "Picked up JAVA_TOOL_OPTIONS: -Xmx1g\n"
                                + CLOSED);
        var messages = readAll(input);
        assertEquals(2, messages.size());
        assertInstanceOf(WorldStreamStatus.Credit.class, messages.get(0));
        assertInstanceOf(WorldStreamStatus.Closed.class, messages.get(1));
        assertEquals(4, stdout.noiseLines());
        // One warning per session, for the first line only, redacted.
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("[0.012s][info][jfr,startup] Started recording 1."));
        String suffix = stdout.suffix();
        assertTrue(suffix.startsWith("; unrelated stdout ignored: 4 lines, "), suffix);
        assertTrue(suffix.contains("filename=<path>"), suffix);
        assertFalse(suffix.contains("tester"), suffix);
        assertFalse(suffix.endsWith("\n"), suffix);
    }

    @Test
    void noNoiseLeavesNoSuffixOrWarning() throws Exception {
        assertEquals(1, readAll(stream(CLOSED)).size());
        assertEquals("", stdout.suffix());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void malformedPrefixedRecordsStillFail() {
        for (String line :
                List.of(
                        "WS2 " + NONCE + " BOGUS\n",
                        "WS2 " + NONCE + " CLOSED extra\n",
                        "WS2 nothex CLOSED\n",
                        "WS2 \n",
                        "WS2  " + NONCE + " CLOSED\n",
                        "WS2 " + NONCE + " CLOSED\r\n",
                        "WS2 " + NONCE + " CLOSÉD\n",
                        "WS2 " + "x".repeat(300) + "\n")) {
            var reader = new WorldStreamStdout(NONCE, warnings::add);
            assertThrows(IOException.class, () -> reader.read(stream("JFR noise\n" + line)), line);
        }
        assertThrows(EOFException.class, () -> stdout.read(stream("WS2 " + NONCE + " CLO")));
    }

    @Test
    void nearMissesWithoutProtocolTextStayNoise() throws Exception {
        var input =
                stream(
                        "WS2X starting\n"
                                + "WS2\n"
                                + "WS\n"
                                + "ws2 lower case\n"
                                + "WS2_"
                                + NONCE.substring(0, 31)
                                + "_CLOSED\n"
                                + "n".repeat(1000)
                                + " WS2"
                                + "\n"
                                + CREDIT);
        var messages = readAll(input);
        // Only the final line, which starts with the exact prefix, is a record.
        assertEquals(1, messages.size(), messages.toString());
        assertInstanceOf(WorldStreamStatus.Credit.class, messages.get(0));
        assertEquals(6, stdout.noiseLines());
    }

    @Test
    void mangledRecordsFailFastInsteadOfBecomingNoise() {
        String mangled = "Mangled World status";
        for (String line :
                List.of(
                        // A spliced byte inside the prefix, or before it.
                        "W\u0001S2 " + NONCE + " CREDIT 1 0 1\n",
                        "xWS2 " + NONCE + " CLOSED\n",
                        // A byte-order mark (UTF-8 bytes) before the prefix.
                        "ï»¿WS2 " + NONCE + " CLOSED\n",
                        // Prefix text or the nonce anywhere in an unrelated line.
                        "JFR: " + CLOSED,
                        "x\r" + CLOSED,
                        " WS2 " + NONCE + " CLOSED\n",
                        "\u0000WS2 " + NONCE + " CLOSED\n",
                        "ws2 " + NONCE + " CLOSED\n",
                        "WS2_" + NONCE + "_CLOSED\n",
                        "lost prefix " + NONCE + " CREDIT 1 0 1\n",
                        // Past the record length, and split across scan chunks.
                        "n".repeat(1000) + " " + CLOSED,
                        "n".repeat(254) + "WS2 tail\n",
                        "n".repeat(240) + NONCE + "\n")) {
            var reader = new WorldStreamStdout(NONCE, warnings::add);
            var failure =
                    assertThrows(
                            IOException.class,
                            () -> readAll(reader, stream("[jfr] started\n" + line + CREDIT)),
                            line);
            assertTrue(failure.getMessage().startsWith(mangled), line + ": " + failure);
            // The line reaches diagnostics only through the redacted sample.
            assertFalse(failure.getMessage().contains(NONCE), failure.getMessage());
        }
    }

    @Test
    void anotherSessionsNonceInNoiseIsNotThisSessionsRecord() throws Exception {
        String other = "fedcba9876543210fedcba9876543210";
        var messages = readAll(stream("trace " + other + "\n" + CLOSED));
        assertEquals(1, messages.size());
        assertEquals(1, stdout.noiseLines());
    }

    private static List<WorldStreamStatus.Message> readAll(
            WorldStreamStdout reader, InputStream input) throws IOException {
        var messages = new ArrayList<WorldStreamStatus.Message>();
        WorldStreamStatus.Message message;
        while ((message = reader.read(input)) != null) {
            messages.add(message);
        }
        return messages;
    }

    @Test
    void streamEndingInsideNoiseIsAnOrdinaryEnd() throws Exception {
        assertNull(stdout.read(stream("Picked up options without a newline")));
        assertEquals(1, stdout.noiseLines());
        var partial = new WorldStreamStdout(NONCE, warnings::add);
        assertNull(partial.read(stream("WS2")));
        assertEquals(1, partial.noiseLines());
    }

    @Test
    void floodBeyondTheCapEndsTheSession() throws Exception {
        String line = "x".repeat(99) + "\n";
        var text = new StringBuilder(CREDIT);
        while (text.length() <= WorldStreamStdout.MAX_NOISE_BYTES + 200) {
            text.append(line);
        }
        text.append(CLOSED);
        var input = stream(text.toString());
        assertInstanceOf(WorldStreamStatus.Credit.class, stdout.read(input));
        var failure = assertThrows(IOException.class, () -> stdout.read(input));
        assertTrue(
                failure.getMessage().startsWith("World renderer flooded stdout"),
                failure.getMessage());
        assertTrue(stdout.noiseBytes() > WorldStreamStdout.MAX_NOISE_BYTES);
        // The sample stays bounded however much was read.
        assertTrue(
                stdout.suffix().length()
                        < HelperStderrTail.MAX_LINES * (HelperStderrTail.MAX_LINE_CHARS + 4) + 256);
    }

    @Test
    void oneEndlessNoiseLineAlsoTripsTheCap() {
        InputStream endless =
                new InputStream() {
                    @Override
                    public int read() {
                        return 'z';
                    }
                };
        var failure = assertThrows(IOException.class, () -> stdout.read(endless));
        assertTrue(failure.getMessage().startsWith("World renderer flooded stdout"));
        assertEquals(0, stdout.noiseLines());
    }

    @Test
    void noiseUpToTheCapIsAccepted() throws Exception {
        String noise = "y".repeat((int) WorldStreamStdout.MAX_NOISE_BYTES - 1) + "\n";
        var messages = readAll(stream(noise + CLOSED));
        assertEquals(1, messages.size());
        assertEquals(WorldStreamStdout.MAX_NOISE_BYTES, stdout.noiseBytes());
    }
}
