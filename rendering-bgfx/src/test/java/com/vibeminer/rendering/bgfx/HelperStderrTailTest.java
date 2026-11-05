package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelperStderrTailTest {
    private static void feed(HelperStderrTail tail, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
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
        assertEquals("a [2J b\n? c\n", tail.snapshot());
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

    /** Every fragment that names the install, the account or the machine. */
    private static final List<String> SENSITIVE =
            List.of(
                    "Program Files",
                    "Files",
                    "x86",
                    "World of Warcraft",
                    "Warcraft",
                    "_retail_",
                    "_classic_",
                    "John Smith",
                    "John",
                    "Smith",
                    "Account",
                    "ACCT",
                    "jsmith",
                    "jürgen",
                    "Jürgen",
                    "j??rgen",
                    "rgen",
                    "fileserver",
                    "AppData",
                    "secret",
                    "ss@",
                    "Applications",
                    "Games");

    private static void assertNoSensitive(String redacted) {
        for (var fragment : SENSITIVE) {
            assertFalse(
                    redacted.toLowerCase(java.util.Locale.ROOT)
                            .contains(fragment.toLowerCase(java.util.Locale.ROOT)),
                    "'" + fragment + "' survived in: " + redacted);
        }
    }

    private static final HelperStderrTail.Sensitive NONE =
            HelperStderrTail.Sensitive.of(List.of(), List.of());

    @Test
    void pathsWithSpacesAreRedactedWhole() {
        for (var input :
                List.of(
                        "C:\\Program Files (x86)\\World of Warcraft\\_retail_\\Data",
                        "\"C:\\Users\\John Smith\\Games\\WoW\\Wow.exe\"",
                        "\\\\fileserver\\share\\John Smith\\wow",
                        "/Applications/World of Warcraft/_retail_/Data",
                        "D:\\Games\\World of Warcraft\\_classic_\\WTF\\Account\\ACCT\\x",
                        "file:/home/john smith/x.jar",
                        "jar:file:/home/John Smith/vimi.jar!/a",
                        "open '/Users/John Smith/Games/World of Warcraft/_retail_' failed",
                        "C:/Users/John Smith/AppData/Local/vimi")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNoSensitive(redacted);
            assertTrue(redacted.contains("<path>"), redacted);
        }
        assertEquals(
                "\"<path>\" missing",
                HelperStderrTail.redact(
                        "\"C:\\Users\\John Smith\\Games\\WoW\\Wow.exe\" missing", NONE));
        assertEquals(
                "open '<path>' failed",
                HelperStderrTail.redact("open '/Users/John Smith/x' failed", NONE));
        assertEquals(
                "jar:file:<path>",
                HelperStderrTail.redact("jar:file:/home/John Smith/v.jar!/a", NONE));
    }

    @Test
    void longPathAndDevicePrefixesAreRedacted() {
        for (var input :
                List.of(
                        "\\\\?\\C:\\Users\\jsmith\\AppData\\Local\\x.dll",
                        "load \\\\.\\C:\\Users\\jsmith\\x failed",
                        "\\\\?\\UNC\\fileserver\\share\\jsmith\\wow")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNoSensitive(redacted);
            assertTrue(redacted.contains("<path>"), redacted);
        }
    }

    @Test
    void nonAsciiAccountNamesAreRedactedBeforeAsciiSubstitution() {
        var tail = new HelperStderrTail();
        feed(tail, "open /home/jürgen/wow failed\nC:\\Users\\Jürgen\\wow\n");
        String snapshot = tail.snapshot();
        assertNoSensitive(snapshot);
        assertEquals("open <path>\n<path>\n", snapshot);
        // Names are matched literally, case-insensitively, with Unicode word boundaries.
        var names = HelperStderrTail.Sensitive.of(List.of(), List.of("jürgen"));
        assertEquals(
                "owner <redacted> denied", HelperStderrTail.redact("owner JÜRGEN denied", names));
    }

    @Test
    void pathsAfterColonOrEqualsAreRedactedButUrlsStay() {
        for (var input :
                List.of(
                        "cannot read path:/home/jsmith/wow/Data",
                        "dir=/home/jsmith/Games",
                        "dir=C:\\Users\\jsmith\\Games",
                        "home:~/Games/World of Warcraft")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNoSensitive(redacted);
            assertTrue(redacted.contains("<path>"), redacted);
        }
        assertEquals(
                "cannot read path:<path>",
                HelperStderrTail.redact("cannot read path:/home/jsmith/wow/Data", NONE));
        assertEquals(
                "GET https://cdn.invalid/a/b.idx ok",
                HelperStderrTail.redact("GET https://cdn.invalid/a/b.idx ok", NONE));
        // Relative names, ratios and ordinary words are not paths.
        assertEquals(
                "World/maps/Azeroth 1/2 read/write",
                HelperStderrTail.redact("World/maps/Azeroth 1/2 read/write", NONE));
    }

    @Test
    void accountRealmAndCharacterFoldersAreRedactedInRelativePathsAndBothSlashStyles() {
        var fragments = List.of("ACCT2", "CCT2", "12345678#1", "Silvermoon", "Thrall", "x.lua");
        for (var input :
                List.of(
                        "load _retail_\\WTF\\Account\\ACCT2\\x.lua failed",
                        "load _retail_/WTF/Account/ACCT2/Silvermoon/Thrall/x.lua failed",
                        "Games/World of Warcraft/WTF/Account/12345678#1/SavedVariables/x.lua",
                        "wtf\\account/ACCT2\\Silvermoon",
                        "WTF\\\\Account\\\\ACCT2\\\\x.lua",
                        "(_classic_\\WTF\\Account\\ACCT2\\Silvermoon\\Thrall) missing",
                        "D:\\Games\\WoW\\WTF\\Account\\ACCT2\\Silvermoon\\Thrall\\x.lua")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            for (var fragment : fragments) {
                assertFalse(
                        redacted.toLowerCase(java.util.Locale.ROOT)
                                .contains(fragment.toLowerCase(java.util.Locale.ROOT)),
                        "'" + fragment + "' survived in: " + redacted);
            }
        }
        assertEquals(
                "load _retail_\\WTF\\Account\\<redacted>",
                HelperStderrTail.redact("load _retail_\\WTF\\Account\\ACCT2\\x.lua failed", NONE));
        assertEquals(
                "(_classic_/WTF/Account/<redacted>) missing",
                HelperStderrTail.redact("(_classic_/WTF/Account/ACCT2/Realm) missing", NONE));
        // Realm folders may hold spaces, so the span runs on like a path span.
        assertEquals(
                "'_retail_/WTF/Account/<redacted>' failed",
                HelperStderrTail.redact("'_retail_/WTF/Account/ACCT2/Argent Dawn' failed", NONE));
        // Absolute ones still become a single path.
        assertEquals(
                "<path>",
                HelperStderrTail.redact("D:\\Games\\WoW\\WTF\\Account\\ACCT2\\x.lua", NONE));
        // The folder name alone, without an account below it, is not redacted.
        assertEquals(
                "WTF/Account/ is empty", HelperStderrTail.redact("WTF/Account/ is empty", NONE));
    }

    @Test
    void absolutePathsAfterBracesPipesArrowsPlusAndHashAreRedacted() {
        for (var input :
                List.of(
                        "Map{/home/jsmith/secret}",
                        "Map{C:\\Users\\jsmith\\secret}",
                        "a|/home/jsmith/secret",
                        "a|C:\\Users\\jsmith\\secret",
                        "link->/home/jsmith/secret",
                        "a+/home/jsmith/secret",
                        "#/home/jsmith/secret",
                        "item#/Users/John Smith/secret")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNoSensitive(redacted);
            assertFalse(redacted.contains("home"), redacted);
            assertTrue(redacted.contains("<path>"), redacted);
        }
        assertEquals("Map{<path>}", HelperStderrTail.redact("Map{/home/jsmith/secret}", NONE));
        assertEquals(
                "Map{a=<path>, b=<path>}",
                HelperStderrTail.redact("Map{a=/home/jsmith/secret, b=/home/x/y}", NONE));
        assertEquals("link-><path>", HelperStderrTail.redact("link->/home/jsmith/secret", NONE));
        assertEquals("a|<path>", HelperStderrTail.redact("a|/home/jsmith/secret", NONE));
    }

    @Test
    void encodedPathsInNonFileUrlsAreDecodedBeforeMatching() {
        var names = HelperStderrTail.Sensitive.of(List.of(), List.of("jsmith"));
        for (var input :
                List.of(
                        "GET http://cdn.invalid/get/C%3A%5CUsers%5Cjsmith%5Cx failed",
                        "GET http://cdn.invalid/get/c%3a%5cusers%5cjsmith%5cx failed",
                        "GET https://cdn.invalid/get/%2Fhome%2Fjsmith%2Fsecret failed")) {
            for (var values : List.of(NONE, names)) {
                String redacted = HelperStderrTail.redact(input, values);
                assertNoSensitive(redacted);
                for (var fragment : List.of("%5C", "%2F", "%3A", "ACCT2", "CCT2", "users")) {
                    assertFalse(
                            redacted.toLowerCase(java.util.Locale.ROOT)
                                    .contains(fragment.toLowerCase(java.util.Locale.ROOT)),
                            "'" + fragment + "' survived in: " + redacted);
                }
                assertTrue(redacted.startsWith("GET http"), redacted);
            }
        }
        assertEquals(
                "GET http://cdn.invalid/get/<path>",
                HelperStderrTail.redact(
                        "GET http://cdn.invalid/get/C%3A%5CUsers%5Cjsmith%5Cx", NONE));
        assertEquals(
                "GET https://cdn.invalid/a/<path> failed",
                HelperStderrTail.redact(
                        "GET https://cdn.invalid/a/WTF%2FAccount%2FACCT2%2Fsecret failed", NONE));
        // A segment that held an encoded separator becomes a path whole.
        assertEquals(
                "GET http://cdn.invalid/<path>",
                HelperStderrTail.redact("GET http://cdn.invalid/%5Cjsmith/x", names));
        // Text outside URLs is left alone.
        assertEquals("100%2F of 5", HelperStderrTail.redact("100%2F of 5", NONE));
    }

    @Test
    void literalSensitiveValuesComeFirstInBothSlashStyles() {
        var values =
                HelperStderrTail.Sensitive.of(
                        List.of("C:\\Users\\John Smith", "/srv/games/World of Warcraft"),
                        List.of("jsmith", "jsmith-desktop.example", "world", "abc"));
        String redacted =
                HelperStderrTail.redact(
                        "copy (C:/Users/John Smith) to [/srv/games/World of Warcraft] by jsmith on"
                                + " jsmith-desktop.example",
                        values);
        assertNoSensitive(redacted);
        assertEquals("copy (<path>) to [<path>] by <redacted> on <redacted>", redacted);
        // Common words and names under four characters are never replaced as names.
        assertEquals(
                "World streaming failed: abc",
                HelperStderrTail.redact("World streaming failed: abc", values));
        // A name inside a longer word is not that name.
        assertEquals("jsmithson", HelperStderrTail.redact("jsmithson", values));
    }

    @Test
    void urlUserInfoIsRedactedUpToTheLastAt() {
        String redacted =
                HelperStderrTail.redact(
                        "https://user:p@ss@host.invalid/a and https://tester:secret@cdn.invalid/b",
                        NONE);
        assertNoSensitive(redacted);
        assertEquals(
                "https://<redacted>@host.invalid/a and https://<redacted>@cdn.invalid/b", redacted);
    }

    @Test
    void redactionCostStaysBoundedOnAdversarialText() {
        for (var unit : List.of("a.", "a-", "a/", "\\\\", "C:\\", "(", "<path>/")) {
            String text = unit.repeat(1 << 16);
            long start = System.nanoTime();
            String redacted = HelperStderrTail.redact(text);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 1_000, unit + " took " + millis + " ms");
            assertTrue(redacted.length() <= HelperStderrTail.MAX_REDACT_CHARS + 16, unit);
        }
    }

    @Test
    void thisMachinesHomeAndUserNameAreRedacted() {
        String home = System.getProperty("user.home");
        String user = System.getProperty("user.name");
        String redacted = HelperStderrTail.redact("cache in " + home + "/x; by " + user + " done");
        assertFalse(redacted.contains(home), redacted);
        if (user.length() >= 4 && !List.of("root", "user", "test", "guest").contains(user)) {
            assertFalse(redacted.contains(user), redacted);
        }
    }

    @Test
    void emptyTailReportsEmpty() {
        var tail = new HelperStderrTail();
        assertTrue(tail.isEmpty());
        assertEquals("", tail.snapshot());
        feed(tail, "\n");
        assertFalse(tail.isEmpty());
    }

    private static void assertNone(String redacted, List<String> fragments) {
        for (var fragment : fragments) {
            assertFalse(
                    redacted.toLowerCase(java.util.Locale.ROOT)
                            .contains(fragment.toLowerCase(java.util.Locale.ROOT)),
                    "'" + fragment + "' survived in: " + redacted);
        }
    }

    @Test
    void anEncodedPathAfterOrdinaryTextInTheSameSegmentIsRedacted() {
        for (var input :
                List.of(
                        "GET http://h.invalid/files/foo%2Fhome%2Fbob%2Fsecret failed",
                        "GET http://h.invalid/files/name%20C%3A%5CUsers%5Cbob%5Cx failed",
                        "GET http://h.invalid/files/a%3DC%3A%5CUsers%5Cbob failed")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNone(redacted, List.of("bob", "secret", "Users", "home", "%5C", "%2F"));
            assertEquals("GET http://h.invalid/files/<path> failed", redacted);
        }
    }

    @Test
    void doubleAndTripleEncodedPathsAndTheUserNameAreRedacted() {
        var names = HelperStderrTail.Sensitive.of(List.of(), List.of("jsmith"));
        for (var input :
                List.of(
                        "GET http://h.invalid/get/C%253A%255CUsers%255Cjsmith%255Cx failed",
                        "GET http://h.invalid/get/C%25253A%25255CUsers%25255Cjsmith failed",
                        "GET http://h.invalid/get/%252Fhome%252Fjsmith%252Fx failed")) {
            for (var values : List.of(NONE, names)) {
                String redacted = HelperStderrTail.redact(input, values);
                assertNone(redacted, List.of("jsmith", "Users", "home", "%25"));
                assertEquals("GET http://h.invalid/get/<path> failed", redacted);
            }
        }
        // Outside a URL, the name still matches right after an encoded separator.
        for (var input : List.of("owner C%5Cjsmith", "owner x%255Cjsmith", "owner a%2fjsmith")) {
            String redacted = HelperStderrTail.redact(input, names);
            assertNone(redacted, List.of("jsmith"));
            assertTrue(redacted.endsWith("<redacted>"), redacted);
        }
        // Still not inside a longer word.
        assertEquals("ajsmith", HelperStderrTail.redact("ajsmith", names));
    }

    @Test
    void anyDelimiterStartsTheAccountAndCommasOrSemicolonsNeverEndIt() {
        for (var input :
                List.of(
                        "WTF\\Account\\ACCT2\\Realm, Name\\Thrall\\x.lua",
                        "WTF/Account/ACCT2/Realm; Name/Thrall/x.lua",
                        "WTF/Account/;ACCT2/Silvermoon",
                        "WTF/Account/>ACCT2/Silvermoon",
                        "WTF/Account/}ACCT2/Silvermoon",
                        "WTF/Account/'ACCT2'/Silvermoon",
                        "WTF/Account/\"ACCT2\"/Silvermoon",
                        "WTF/Account/`ACCT2`/Silvermoon")) {
            String redacted = HelperStderrTail.redact(input, NONE);
            assertNone(redacted, List.of("ACCT2", "CCT2", "Name", "Thrall", "Silvermoon"));
            assertTrue(redacted.contains("<redacted>"), redacted);
        }
        // A quote opened before the path closes it, even with an apostrophe realm inside.
        assertEquals(
                "'WTF/Account/<redacted>' and more text",
                HelperStderrTail.redact(
                        "'WTF/Account/ACCT2/Kel'Thuzad/Thrall' and more text", NONE));
    }

    @Test
    void closingBracketsBeforeAPathAndApostrophesInsideAQuotedPath() {
        String braces = HelperStderrTail.redact("}}}/home/bob {{{", NONE);
        assertNone(braces, List.of("bob", "home"));
        assertTrue(braces.contains("<path>"), braces);
        assertEquals("a)<path>", HelperStderrTail.redact("a)/home/bob", NONE));
        assertEquals("a]<path>", HelperStderrTail.redact("a]/home/bob", NONE));
        assertEquals(
                "open '<path>' failed",
                HelperStderrTail.redact(
                        "open 'C:\\Users\\Other Person\\it's here\\Wow.exe' failed", NONE));
    }
}
