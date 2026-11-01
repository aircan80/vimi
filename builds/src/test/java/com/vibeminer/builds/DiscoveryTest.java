package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DiscoveryTest {
    @Test
    void identityMatchesPinnedCsharpEngineGoldenVector() {
        var id =
                BuildIdentity.create(
                        "wow",
                        Map.of(
                                "Region",
                                "us",
                                "BuildConfig",
                                "A".repeat(32),
                                "CDNConfig",
                                "b".repeat(32),
                                "KeyRing",
                                "",
                                "BuildId",
                                "1000",
                                "VersionsName",
                                "1.0.0.1000",
                                "ProductConfig",
                                "D".repeat(32)));
        assertEquals("1f154ffdac5b9bc96002d6bf48a9959da1b30bdeaa0029ad87bc702715378c77", id.id());
        assertEquals(
                "27:vibeminer-build-identity-v23:wow9:Published11:BuildConfig32:"
                        + "a".repeat(32)
                        + "7:BuildId4:10007:KeyRing0:13:ProductConfig32:"
                        + "d".repeat(32)
                        + "12:VersionsName10:1.0.0.1000",
                id.canonical());
    }

    @Test
    void utf8FramingMatchesSecondCsharpGoldenVector() {
        var identity =
                BuildIdentity.fromFields(
                        "wow_classic_era",
                        Map.of(
                                "BuildConfig",
                                "e".repeat(32),
                                "VersionsName",
                                "1.15.7.61582 é",
                                "BuildId",
                                "61582",
                                "ProductConfig",
                                ""));
        assertEquals(
                "ffa49e6c9d4ce8d072d24fb0ee62d6a0572d349f0366ff7bfbd564f2e62bbc2b", identity.id());
        assertTrue(identity.canonical().endsWith("12:VersionsName15:1.15.7.61582 é"));
    }

    @Test
    void identityExcludesCdnAndRegionButPreservesUnknownAndEmptyFields() {
        var fields = Map.of("BuildConfig", "a".repeat(32), "VersionsName", "one");
        var original = BuildIdentity.fromFields("wow", fields);
        var withEmpty = new java.util.HashMap<>(fields);
        withEmpty.put("ProductConfig", "");
        assertNotEquals(original.id(), BuildIdentity.fromFields("wow", withEmpty).id());
        assertEquals(
                "INVALID_PRODUCT",
                assertThrows(
                                BuildIdentityException.class,
                                () -> BuildIdentity.fromFields("other", fields))
                        .code());
        var withRegion = new java.util.HashMap<>(fields);
        withRegion.put("Region", "us");
        assertThrows(
                BuildIdentityException.class, () -> BuildIdentity.fromFields("wow", withRegion));
    }

    @Test
    void productsUseNaturalOrderAndBoundUnreviewedIds() {
        assertEquals(14, Products.REVIEWED.size());
        assertEquals("Forever", Products.label("wowf"));
        var list =
                Products.listed(
                        List.of("wowdev10", "wowdev2", "wowdev02", "wowdev2", "other", "wow"));
        assertEquals(
                List.of("wowdev2", "wowdev02", "wowdev10"),
                list.subList(14, list.size()).stream().map(Products.Listed::id).toList());
        assertEquals(
                78,
                Products.listed(
                                java.util.stream.IntStream.range(0, 100)
                                        .mapToObj(i -> "wowdev" + i)
                                        .toList())
                        .size());
    }

    @Test
    void tableParserPreservesHugeSequenceTextAndRefusesMalformedInput() {
        String huge = "9".repeat(200);
        var table =
                VersionsTable.parse(
                        ("Name!STRING:0\n## seqn = " + huge + "\na\n")
                                .getBytes(StandardCharsets.UTF_8));
        assertEquals(huge, table.sequence());
        for (String value :
                List.of(
                        "",
                        "Name!STRING:0\ra",
                        "A!STRING:0|A!STRING:0\na|b",
                        "Name!STRING:0\na|b",
                        "Name!STRING:0\nA\u0000B",
                        "## seqn = 1\n## seqn = 01\nName!STRING:0")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VersionsTable.parse(value.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionsTable.parse(new byte[] {(byte) 0xc0, (byte) 0xaf}));
        assertThrows(
                IllegalArgumentException.class, () -> VersionsTable.parse(new byte[(1 << 20) + 1]));
    }

    @Test
    void summaryProblemOutputIsBounded() {
        var observation =
                new com.vibeminer.access.SourceTypes.Observation(
                        com.vibeminer.access.SourceTypes.ObservationKind.SUMMARY,
                        "",
                        "us",
                        java.net.URI.create("https://us.version.battle.net/v2/summary"),
                        java.time.Instant.EPOCH,
                        ("Product!STRING:0\n" + "INVALID!\n".repeat(100))
                                .getBytes(StandardCharsets.UTF_8));
        var result =
                Summaries.read(
                        new com.vibeminer.access.SourceTypes.ObservationResult(
                                com.vibeminer.access.SourceTypes.ObjectState.PRESENT,
                                observation,
                                true,
                                null,
                                null,
                                null));
        assertEquals(51, result.problems().size());
        assertEquals("CATALOG_PROBLEMS_TRUNCATED", result.problems().getLast().code());
    }
}
