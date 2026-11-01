package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.Reply;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HostRankingTest {
    private static final String FIRST = "level3.blizzard.com",
            SECOND = "us.cdn.blizzard.com",
            THIRD = "eu.cdn.blizzard.com";
    private static final CdnRoute ROUTE = new CdnRoute(List.of(FIRST, SECOND, THIRD), "tpr/wow");

    private static final class Time extends Clock {
        Instant now = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Attempt attempt(String host, Failure failure) {
        return new Attempt(
                host,
                URI.create("https://" + host + "/path"),
                failure,
                null,
                Instant.MIN,
                "scripted",
                null);
    }

    private static Reply reply(String host, String version, List<Attempt> failed) {
        return new Reply(
                host,
                URI.create("https://" + host + "/path"),
                version,
                1,
                null,
                new byte[] {1},
                Instant.EPOCH,
                Duration.ZERO,
                Duration.ZERO,
                failed);
    }

    @Test
    void backgroundPrefersKnownHttp2WhileInteractiveKeepsAdvertisedOrder() {
        HostRanking hosts = new HostRanking(new Time(), Duration.ofSeconds(30));
        assertSame(ROUTE, hosts.order(ROUTE, FetchPriority.BACKGROUND));
        hosts.observe(reply(SECOND, "h2", List.of()));
        assertEquals(
                List.of(SECOND, FIRST, THIRD),
                hosts.order(ROUTE, FetchPriority.BACKGROUND).hosts());
        assertSame(ROUTE, hosts.order(ROUTE, FetchPriority.INTERACTIVE));
        hosts.observe(reply(THIRD, "http/2.0", List.of()));
        assertEquals(
                List.of(SECOND, THIRD, FIRST),
                hosts.order(ROUTE, FetchPriority.BACKGROUND).hosts());
    }

    @Test
    void coolingAlwaysOverridesHttp2AndExpiresAtTheExactBoundary() {
        Time time = new Time();
        HostRanking hosts = new HostRanking(time, Duration.ofSeconds(30));
        hosts.observe(reply(SECOND, "h2", List.of()));
        hosts.sentBadBytes(SECOND);
        assertEquals(
                List.of(FIRST, THIRD, SECOND),
                hosts.order(ROUTE, FetchPriority.BACKGROUND).hosts());
        time.now = time.now.plusSeconds(29);
        assertEquals(
                List.of(FIRST, THIRD, SECOND),
                hosts.order(ROUTE, FetchPriority.INTERACTIVE).hosts());
        time.now = time.now.plusSeconds(1);
        assertEquals(
                List.of(SECOND, FIRST, THIRD),
                hosts.order(ROUTE, FetchPriority.BACKGROUND).hosts());
        assertSame(ROUTE, hosts.order(ROUTE, FetchPriority.INTERACTIVE));
    }

    @Test
    void failedAttemptsAndLaterSuccessUpdateHostKnowledgeIndependently() {
        HostRanking hosts = new HostRanking(new Time(), Duration.ofSeconds(30));
        hosts.observe(reply(SECOND, "h2", List.of(attempt(FIRST, Failure.TIMEOUT))));
        assertEquals(
                List.of(SECOND, THIRD, FIRST),
                hosts.order(ROUTE, FetchPriority.INTERACTIVE).hosts());
        hosts.observe(reply(FIRST, "http/1.1", List.of()));
        assertSame(ROUTE, hosts.order(ROUTE, FetchPriority.INTERACTIVE));
    }

    @Test
    void onlyReferencePassingFailuresAndBadBytesTriggerCooldown() {
        for (Failure failure : Failure.values()) {
            HostRanking hosts = new HostRanking(new Time(), Duration.ofSeconds(30));
            hosts.observe(new CdnException(List.of(attempt(FIRST, failure))));
            boolean cooling =
                    List.of(
                                    Failure.OFFLINE,
                                    Failure.TIMEOUT,
                                    Failure.NETWORK,
                                    Failure.HTTP_STATUS,
                                    Failure.RANGE_IGNORED,
                                    Failure.VERIFICATION_FAILED)
                            .contains(failure);
            assertEquals(
                    cooling ? List.of(SECOND, THIRD, FIRST) : ROUTE.hosts(),
                    hosts.order(ROUTE, FetchPriority.INTERACTIVE).hosts(),
                    failure.name());
        }
    }

    @Test
    void zeroCooldownAndBoundedMetadataDoNotRetainAnUnlimitedHostHistory() {
        HostRanking zero = new HostRanking(new Time(), Duration.ZERO);
        zero.sentBadBytes(FIRST);
        assertSame(ROUTE, zero.order(ROUTE, FetchPriority.INTERACTIVE));
        HostRanking hosts = new HostRanking(new Time(), Duration.ofDays(1));
        for (int i = 0; i < 257; i++) {
            hosts.sentBadBytes(
                    "" + (char) ('a' + i / 26) + (char) ('a' + i % 26) + ".cdn.blizzard.com");
        }
        CdnRoute route =
                new CdnRoute(List.of("aa.cdn.blizzard.com", "zz.cdn.blizzard.com"), "tpr/wow");
        assertSame(route, hosts.order(route, FetchPriority.INTERACTIVE));
    }
}
