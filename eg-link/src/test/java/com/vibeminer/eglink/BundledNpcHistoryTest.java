package com.vibeminer.eglink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BundledNpcHistoryTest {
    private static final String COMMIT = "a".repeat(40);

    private static byte[] text(String... lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample(String npcs, String... entries) {
        var lines =
                new java.util.ArrayList<>(
                        List.of(
                                "#format\t" + BundledNpcHistory.FORMAT,
                                "#upstream\thttps://github.com/JIVESCORP/eg-link-output-wowf",
                                "#product\twowf",
                                "#locale\tenUS",
                                "#range\t" + COMMIT + "\t" + COMMIT,
                                "#npcs\t" + npcs,
                                "#source\t0\t1.60.1.70862\t"
                                        + COMMIT
                                        + "\t2026-11-11T11:27:56Z\t2026-11-10"));
        lines.addAll(List.of(entries));
        return text(lines.toArray(String[]::new));
    }

    @Test
    void theShippedHistoryVerifiesAndKeepsAnNpcOnlyAnOlderBuildRecorded() {
        var history = BundledNpcHistory.bundled();
        assertTrue(history.size() > 4000, "bundled NPCs: " + history.size());
        assertEquals("wowf", history.product());
        // Edwin VanCleef: only the 70862 snapshot records him (display 2029).
        var vanCleef = history.displays(639).orElseThrow();
        assertEquals(2029, vanCleef.displays().getFirst().displayId());
        assertEquals("1.60.1.70862", vanCleef.source().build());
        assertTrue(vanCleef.provenance().endsWith("(bundled)"), vanCleef.provenance());
        var attribution = BundledNpcHistory.attribution();
        assertEquals(history.size(), attribution.npcs());
        assertTrue(attribution.notice().contains("CC BY 4.0"));
        assertTrue(attribution.builds().contains("1.60.1.70862"));
    }

    @Test
    void parsesEntriesWithTheirSource() throws IOException {
        var history =
                BundledNpcHistory.parse(
                        sample("2", "3\t0\t987:1.0:1.0", "38\t0\t129962:25.0:1.0,129963:75.0:1.5"),
                        "notice");
        var entry = history.displays(38).orElseThrow();
        assertEquals(2, entry.displays().size());
        assertEquals(1.5, entry.displays().get(1).scale());
        assertEquals("2026-11-10", entry.source().collectedOn());
        assertTrue(history.displays(4).isEmpty());
    }

    @Test
    void refusesInconsistentOrMalformedFiles() {
        assertThrows(
                IOException.class,
                () -> BundledNpcHistory.parse(sample("2", "3\t0\t987:1.0:1.0"), "n"));
        assertThrows(
                IOException.class,
                () -> BundledNpcHistory.parse(sample("1", "3\t1\t987:1.0:1.0"), "n"));
        assertThrows(
                IOException.class,
                () -> BundledNpcHistory.parse(sample("1", "3\t0\t0:1.0:1.0"), "n"));
        assertThrows(
                IOException.class,
                () -> BundledNpcHistory.parse(sample("2", "3\t0\t1:1:1", "3\t0\t2:1:1"), "n"));
        byte[] truncated = sample("1", "3\t0\t987:1.0:1.0");
        assertThrows(
                IOException.class,
                () ->
                        BundledNpcHistory.parse(
                                java.util.Arrays.copyOf(truncated, truncated.length - 1), "n"));
    }
}
