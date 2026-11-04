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
    void emptyTailReportsEmpty() {
        var tail = new HelperStderrTail();
        assertTrue(tail.isEmpty());
        assertEquals("", tail.snapshot());
        feed(tail, "\n");
        assertFalse(tail.isEmpty());
    }
}
