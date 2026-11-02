package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ListfileParserTest {
    @Test
    void chunksSplitEveryByteAndCleanDirectionMarks() {
        List<String> found = new ArrayList<>();
        ListfileParser p =
                new ListfileParser(
                        (id, name) ->
                                found.add(id + ":" + new String(name, StandardCharsets.UTF_8)));
        byte[] csv = "\r\n0;A\u200e/é.blp\r\n4294967295;Z".getBytes(StandardCharsets.UTF_8);
        for (byte b : csv) {
            p.feed(new byte[] {b});
        }
        assertEquals(2, p.finish());
        assertEquals(2, p.finish());
        assertEquals(List.of("0:A/é.blp", "4294967295:Z"), found);
        assertThrows(IllegalStateException.class, () -> p.feed(new byte[0]));
    }

    @Test
    void rejectsNoncanonicalUnsortedControlAndInvalidUtf8() {
        for (String csv :
                List.of(
                        "01;a",
                        "4294967296;a",
                        "2;a\n1;b",
                        "1;a\n1;b",
                        "1;\u0085",
                        "1;\u200e",
                        "1;",
                        "-1;a",
                        "1;a\tb")) {
            ListfileParser p = new ListfileParser((i, n) -> {});
            assertThrows(
                    ListfileFormatException.class,
                    () -> {
                        p.feed(csv.getBytes(StandardCharsets.UTF_8));
                        p.finish();
                    },
                    csv);
            assertThrows(IllegalStateException.class, p::finish);
        }
        ListfileParser p = new ListfileParser((i, n) -> {});
        assertThrows(
                ListfileFormatException.class,
                () -> p.feed(new byte[] {'1', ';', (byte) 0xc0, (byte) 0xaf, 10}));
    }

    @Test
    void capsLinesNamesAndBlob() {
        ListfileParser p = new ListfileParser((i, n) -> {}, 1, 2);
        p.feed("1;ab\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ListfileFormatException.class,
                () -> p.feed("2;c\n".getBytes(StandardCharsets.UTF_8)));
        ListfileParser tooLong = new ListfileParser((i, n) -> {});
        assertThrows(ListfileFormatException.class, () -> tooLong.feed(new byte[1037]));
    }

    @Test
    void foldOnlyAsciiAndSlash() {
        assertTrue(NameFolding.hasPrefix("Ab/C.blp", "aB\\c"));
        assertFalse(NameFolding.hasPrefix("é", "É"));
    }
}
