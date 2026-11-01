package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import com.vibeminer.network.NetworkTypes.Request;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PrimitivesTest {
    private static CdnKey key(int n) {
        return CdnKey.parse(String.format(java.util.Locale.ROOT, "%032x", n));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keysAreOwnedUnsignedAndHexRoundTrip() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 128;
        CdnKey key = new CdnKey(bytes);
        bytes[0] = 0;
        assertTrue(key.compareTo(new CdnKey(bytes)) > 0);
        assertEquals(key, CdnKey.parse(key.hex().toUpperCase(java.util.Locale.ROOT)));
        byte[] copy = key.bytes();
        copy[0] = 0;
        assertEquals((byte) 128, key.bytes()[0]);
        assertThrows(IllegalArgumentException.class, () -> new CdnKey(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> CdnKey.parse("g".repeat(32)));
    }

    @Test
    void routesFilterDeduplicatePreserveOrderAndBuildExactUrls() {
        CdnRoute route =
                new CdnRoute(
                        List.of(
                                "evil.test",
                                "us.cdn.blizzard.com",
                                "level3.blizzard.com",
                                "us.cdn.blizzard.com"),
                        "tpr/wow");
        assertEquals(List.of("us.cdn.blizzard.com", "level3.blizzard.com"), route.hosts());
        CdnKey key = CdnKey.parse("0123456789abcdef0123456789abcdef");
        assertEquals(
                "https://us.cdn.blizzard.com/tpr/wow/data/01/23/0123456789abcdef0123456789abcdef.index",
                route.urlFor(route.hosts().getFirst(), ObjectKind.INDEX, key).toString());
        assertTrue(
                route.urlFor(route.hosts().getFirst(), ObjectKind.CONFIG, key)
                        .getPath()
                        .contains("/config/"));
        assertThrows(
                IllegalArgumentException.class,
                () -> route.urlFor("evil.test", ObjectKind.DATA, key));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://us.cdn.blizzard.com",
                "US.cdn.blizzard.com",
                "x.cdn.blizzard.com",
                "us.cdn.blizzard.com.evil.test",
                "us.cdn.blizzard.com:443",
                "us.cdn.blizzard.com@evil.test"
            })
    void unofficialHostVariantsAreRefused(String host) {
        assertFalse(CdnRoute.isOfficialHost(host));
        assertThrows(IllegalArgumentException.class, () -> new CdnRoute(List.of(host), "tpr/wow"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tpr/../wow",
                "/tpr/wow",
                "tpr/wow/",
                "tpr//wow",
                "tpr/%2f",
                "tpr/WoW",
                "wow"
            })
    void traversalAndMalformedPathsAreRefused(String path) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CdnRoute(List.of("us.cdn.blizzard.com"), path));
    }

    @Test
    void requestBoundsRejectOverflowAndInvalidExpectedTotals() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Request.range(ObjectKind.DATA, key(1), Long.MAX_VALUE, 2, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> Request.range(ObjectKind.DATA, key(1), -1, 1, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> Request.range(ObjectKind.DATA, key(1), 10, 10, 19L, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> Request.whole(ObjectKind.DATA, key(1), 10L, 9L, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> Request.whole(ObjectKind.DATA, key(1), 0L, null, null));
    }

    @Test
    void versionUrlsAndExternalPathsAreConstrained() {
        assertEquals(
                URI.create("https://eu.version.battle.net/v2/summary"),
                SourceUrls.version("", "eu", NetworkTypes.DocumentKind.SUMMARY));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceUrls.version("wow", "eu", NetworkTypes.DocumentKind.SUMMARY));
        assertThrows(IllegalArgumentException.class, () -> SourceUrls.commits("../Item"));
        assertThrows(IllegalArgumentException.class, () -> SourceUrls.asset("../x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceUrls.definition("a".repeat(39), "Item"));
        assertFalse(
                SourceUrls.assetRedirect(
                        URI.create("https://release-assets.githubusercontent.com.evil.test/x")));
        assertFalse(
                SourceUrls.assetRedirect(
                        URI.create("https://user@release-assets.githubusercontent.com/x")));
        assertFalse(
                SourceUrls.assetRedirect(
                        URI.create("https://release-assets.githubusercontent.com:444/x")));
        assertTrue(
                SourceUrls.assetRedirect(
                        URI.create(
                                "https://release-assets.githubusercontent.com/x?signature=secret")));
    }

    @Test
    void tablesParseStrictUtf8AndPreserveEmptyCellsAndSequence() {
        VersionTable table =
                VersionTable.parse(
                        utf8("## seqn = 123\r\nName!STRING:0|Hosts!STRING:0\r\nus|\r\n"));
        assertEquals(123L, table.sequence());
        assertEquals(Map.of("Name", "us", "Hosts", ""), table.rows().getFirst());
        assertThrows(
                UnsupportedOperationException.class, () -> table.rows().getFirst().put("x", "y"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Name!STRING:0\ra",
                "Name!STRING:0|Name!STRING:0\na|b",
                "Name\na",
                "Name!STRING:0|Hosts!STRING:0\na",
                "## seqn = 1\n## seqn = 2\nName!STRING:0",
                "Name!STRING:0\nA\u0000B"
            })
    void malformedTablesAreRefused(String text) {
        assertThrows(IllegalArgumentException.class, () -> VersionTable.parse(utf8(text)));
    }

    @Test
    void tableBoundsAndBadUtf8AreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionTable.parse(new byte[] {(byte) 0xc0, (byte) 0xaf}));
        assertThrows(
                IllegalArgumentException.class, () -> VersionTable.parse(new byte[(1 << 20) + 1]));
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionTable.parse(utf8("Name!STRING:0\n" + "a".repeat(16385))));
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionTable.parse(utf8("Name!STRING:0\n" + "a\n".repeat(10001))));
    }

    @Test
    void routesReportAmbiguousAndInvalidRegionsIndependently() {
        var table =
                VersionTable.parse(
                        utf8(
                                "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|tpr/wow|evil.test us.cdn.blizzard.com\neu|tpr/wow|eu.cdn.blizzard.com\neu|tpr/wow|eu.cdn.blizzard.com\nkr|../x|kr.cdn.blizzard.com\n"));
        var routes = table.routes();
        assertEquals(1, routes.usable().size());
        assertEquals("ROUTE_AMBIGUOUS", routes.unusable().get("eu"));
        assertEquals("ROUTE_INVALID", routes.unusable().get("kr"));
    }

    private static CdnException exception(Failure... failures) {
        List<Attempt> attempts = new ArrayList<>();
        for (Failure f : failures) {
            attempts.add(
                    new Attempt(
                            "host",
                            URI.create("https://host/path"),
                            f,
                            null,
                            Instant.EPOCH,
                            "test",
                            null));
        }
        return new CdnException(attempts);
    }

    @Test
    void overallFailurePrecedenceMatchesReferenceAndRemainsDated() {
        assertEquals(Failure.OFFLINE, exception(Failure.OFFLINE, Failure.OFFLINE).overall());
        assertEquals(Failure.NOT_FOUND, exception(Failure.OFFLINE, Failure.NOT_FOUND).overall());
        assertEquals(
                Failure.TIMEOUT, exception(Failure.VERIFICATION_FAILED, Failure.TIMEOUT).overall());
        assertEquals(
                Failure.OFFLINE, exception(Failure.VERIFICATION_FAILED, Failure.OFFLINE).overall());
        assertEquals(
                Failure.VERIFICATION_FAILED,
                exception(Failure.SIZE_CONFLICT, Failure.VERIFICATION_FAILED).overall());
        assertFalse(exception(Failure.SIZE_CONFLICT).retryable());
        assertTrue(exception(Failure.NOT_FOUND).retryable());
    }
}
