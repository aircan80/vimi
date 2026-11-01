package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.Reply;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * C# host policy: HTTP2 first for background, advertised order for interactive, cooling hosts last.
 */
final class HostRanking {
    static final int MAX_HOSTS = 256;

    private static final class State {
        boolean http2;
        Instant failedAt;
    }

    private record Ranked(String host, int index, boolean cooling, boolean preferred) {}

    private final Clock clock;
    private final Duration cooldown;
    private final LinkedHashMap<String, State> hosts = new LinkedHashMap<>(16, 0.75f, true);

    HostRanking(Clock clock, Duration cooldown) {
        this.clock = Objects.requireNonNull(clock);
        this.cooldown = Objects.requireNonNull(cooldown);
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("Host cooldown cannot be negative");
        }
    }

    synchronized CdnRoute order(CdnRoute route, FetchPriority priority) {
        Objects.requireNonNull(priority);
        Instant now = clock.instant();
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < route.hosts().size(); i++) {
            String host = route.hosts().get(i);
            State state = hosts.get(host);
            boolean cooling =
                    state != null
                            && state.failedAt != null
                            && Duration.between(state.failedAt, now).compareTo(cooldown) < 0;
            ranked.add(
                    new Ranked(
                            host,
                            i,
                            cooling,
                            priority == FetchPriority.BACKGROUND && state != null && state.http2));
        }
        ranked.sort(
                Comparator.comparing(Ranked::cooling)
                        .thenComparing(r -> !r.preferred())
                        .thenComparingInt(Ranked::index));
        List<String> order = ranked.stream().map(Ranked::host).toList();
        return order.equals(route.hosts()) ? route : route.withHosts(order);
    }

    synchronized void observe(Reply reply) {
        for (Attempt attempt : reply.failed()) {
            failed(attempt);
        }
        State state = state(reply.host());
        String version =
                reply.httpVersion() == null ? "" : reply.httpVersion().toLowerCase(Locale.ROOT);
        if (version.equals("h2")
                || version.startsWith("h2_")
                || version.equals("h3")
                || version.matches("(?:http/)?[2-9](?:\\.[0-9]+)?")) {
            state.http2 = true;
        }
        state.failedAt = null;
    }

    synchronized void observe(CdnException failure) {
        for (Attempt attempt : failure.attempts()) {
            failed(attempt);
        }
    }

    synchronized void sentBadBytes(String host) {
        state(host).failedAt = clock.instant();
    }

    private void failed(Attempt attempt) {
        Failure failure = attempt.failure();
        if (failure == Failure.OFFLINE
                || failure == Failure.TIMEOUT
                || failure == Failure.NETWORK
                || failure == Failure.HTTP_STATUS
                || failure == Failure.RANGE_IGNORED
                || failure == Failure.VERIFICATION_FAILED) {
            state(attempt.host()).failedAt = clock.instant();
        }
    }

    private State state(String host) {
        Objects.requireNonNull(host);
        State state = hosts.computeIfAbsent(host, ignored -> new State());
        while (hosts.size() > MAX_HOSTS) {
            hosts.remove(hosts.keySet().iterator().next());
        }
        return state;
    }
}
