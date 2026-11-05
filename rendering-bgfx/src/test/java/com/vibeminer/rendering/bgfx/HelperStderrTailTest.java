package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HelperStderrTailTest {
    private static void feed(HelperStderrTail tail, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        tail.accept(bytes, 0, bytes.length);
    }

    @Test
    void retainsOnlyTheLastBoundedLinesAndCountsOmittedOnes() {
        var tail = new HelperStderrTail();
        for (int i = 0; i < 1000; i++) {
            feed(tail, "line " + i + "\n");
        }
        String snapshot = tail.snapshot();
        assertTrue(snapshot.startsWith("[960 earlier stderr lines omitted]\n"));
        assertTrue(snapshot.contains("line 960\n"));
        assertTrue(snapshot.endsWith("line 999\n"));
        assertFalse(snapshot.contains("line 959\n"));
        assertEquals(HelperStderrTail.MAX_LINES + 1, snapshot.split("\n").length);
    }

    @Test
    void truncatesLongLinesAndKeepsPartialFinalLine() {
        var tail = new HelperStderrTail();
        feed(tail, "x".repeat(100_000) + "\nunterminated");
        String[] lines = tail.snapshot().split("\n");
        assertEquals(HelperStderrTail.MAX_LINE_CHARS + 3, lines[0].length());
        assertTrue(lines[0].endsWith("..."));
        assertEquals("unterminated", lines[1]);
    }

    @Test
    void neutralizesControlAndNonAsciiBytesSoLogLinesCannotBeForged() {
        var tail = new HelperStderrTail();
        byte[] bytes = {'a', 0x1b, '[', '2', 'J', '\t', 'b', '\r', '\n', (byte) 0xff, 0, 'c'};
        tail.accept(bytes, 0, bytes.length);
        assertEquals("a?[2J b\n??c\n", tail.snapshot());
    }

    @Test
    void redactsUrlQueriesInCompleteAndPartialLines() {
        var tail = new HelperStderrTail();
        feed(tail, "GET https://cdn.invalid/a?sig=secret1 failed\nhttp://x.invalid/b#tok=secret2");
        String snapshot = tail.snapshot();
        assertFalse(snapshot.contains("secret"), snapshot);
        assertTrue(snapshot.contains("https://cdn.invalid/a?<redacted> failed"), snapshot);
        assertEquals("a b", HelperStderrTail.redact("a\u2028b"));
    }

    @Test
    void redactsInstallPathsAccountsAndHostnames() {
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        String redacted =
                HelperStderrTail.redact(
                        "open /Users/tester/Games/World/Data/data.000 failed;"
                                + " C:\\Program Files\\World\\Wow.exe and C:/Users/tester/x;"
                                + " share \\\\fileserver\\games\\wow;"
                                + " jar:file:/opt/tester/vimi.jar!/a; home="
                                + home
                                + "/cache; https://tester:secret@cdn.invalid/a/b.idx; by "
                                + user);
        assertFalse(redacted.contains("tester"), redacted);
        assertFalse(redacted.contains("fileserver"), redacted);
        assertFalse(redacted.contains("secret"), redacted);
        assertFalse(redacted.contains(home), redacted);
        assertFalse(redacted.contains("Program"), redacted);
        if (user.length() >= 3) {
            assertFalse(redacted.contains(user), redacted);
        }
        assertTrue(redacted.startsWith("open <path> failed; <path> Files"), redacted);
        assertTrue(redacted.contains("share <path>;"), redacted);
        assertTrue(redacted.contains("jar:file:<path>"), redacted);
        assertTrue(redacted.contains("https://<redacted>@cdn.invalid/a/b.idx"), redacted);
        // Relative names, ratios and ordinary words are not paths.
        assertEquals(
                "World/maps/Azeroth 1/2 read/write",
                HelperStderrTail.redact("World/maps/Azeroth 1/2 read/write"));
    }

    @Test
    void emptyTailReportsEmpty() {
        var tail = new HelperStderrTail();
        assertTrue(tail.isEmpty());
        assertEquals("", tail.snapshot());
        feed(tail, "\n");
        assertFalse(tail.isEmpty());
    }
}
