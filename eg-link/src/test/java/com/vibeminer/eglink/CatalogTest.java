package com.vibeminer.eglink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CatalogTest {
    @Test
    void exactProductBuildAndLocaleRemainSeparate() throws Exception {
        var index =
                Map.of(
                        "license",
                        EgLinkTypes.LICENSE,
                        "attribution",
                        EgLinkTypes.ATTRIBUTION,
                        "products",
                        Map.of(
                                "wowf",
                                Map.of(
                                        "1.60.1.70707",
                                        Map.of(
                                                "creature_details.enUS",
                                                Map.of(
                                                        "records",
                                                        7,
                                                        "sources",
                                                        2,
                                                        "lastCollectedOn",
                                                        "2026-11-01")))));
        var catalog =
                EgLinkRepository.parseIndex("a".repeat(40), Instant.EPOCH, EgJson.bytes(index));
        assertEquals("wowf", catalog.builds().getFirst().product());
        assertEquals("1.60.1.70707", catalog.builds().getFirst().version());
        assertEquals("enUS", catalog.builds().getFirst().datasets().getFirst().locale());
        assertEquals(7, catalog.builds().getFirst().declaredRecords());
    }

    @Test
    void refusesTraversalSchemaAndAttributionChanges() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EgLinkTypes.Dataset("../../evil", 1, 1, "2026-11-01"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EgLinkTypes.Build("main", "wowf", "1.60.1.70707", java.util.List.of()));
        assertThrows(
                IOException.class,
                () ->
                        EgLinkRepository.parseIndex(
                                "a".repeat(40),
                                Instant.EPOCH,
                                EgJson.bytes(
                                        Map.of(
                                                "license",
                                                "unknown",
                                                "attribution",
                                                "unknown",
                                                "products",
                                                Map.of()))));
        assertThrows(
                IOException.class,
                () ->
                        EgJson.parse(
                                "{\"same\":1,\"same\":2}"
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                100));
    }
}
