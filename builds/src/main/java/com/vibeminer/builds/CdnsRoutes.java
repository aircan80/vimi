package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Recent distinct CDN replies, ranked by numeric sequence before time, read offline through layer
 * 2.
 */
public final class CdnsRoutes {
    public static final int MAX_REPLIES = 32;
    private static final CatalogTasks TASKS = new CatalogTasks("cdn-routes", 2, 64, () -> {});

    private CdnsRoutes() {}

    public static CompletableFuture<List<VersionsTable>> newestFirst(
            ObjectSource source, String product, Cancellation token) {
        return TASKS.submit(
                token,
                () -> {
                    Map<ObjectKey, History.Entry> latest = new HashMap<>();
                    History.Entry after = null;
                    try {
                        while (true) {
                            token.check();
                            List<History.Entry> page =
                                    source.readHistory(
                                                    new History.Query(
                                                            product, null, 2, after, 1000),
                                                    token)
                                            .join();
                            for (History.Entry entry : page) {
                                History.Entry prior = latest.get(entry.document());
                                if (prior == null
                                        || prior.observedAt().isBefore(entry.observedAt())) {
                                    latest.put(entry.document(), entry);
                                }
                            }
                            if (latest.size() > 2 * MAX_REPLIES) {
                                trim(latest);
                            }
                            if (page.size() < 1000) {
                                break;
                            }
                            after = page.getLast();
                        }
                    } catch (RuntimeException e) {
                        if (CatalogTasks.unwrap(e) instanceof CancellationException canceled) {
                            throw canceled;
                        }
                        return List.of();
                    }
                    trim(latest);
                    List<Ranked> replies = new ArrayList<>();
                    for (History.Entry entry : latest.values()) {
                        try {
                            token.check();
                            var reply =
                                    source.getKey(
                                                    new ObjectRequest(entry.document()),
                                                    null,
                                                    FetchPriority.INTERACTIVE,
                                                    token)
                                            .join();
                            byte[] bytes = reply.bytes();
                            if (!reply.isPresent() || bytes == null) {
                                continue;
                            }
                            VersionsTable table = VersionsTable.parse(bytes);
                            replies.add(
                                    new Ranked(
                                            number(table.sequence()), entry.observedAt(), table));
                        } catch (RuntimeException e) {
                            if (CatalogTasks.unwrap(e) instanceof CancellationException canceled) {
                                throw canceled;
                            }
                        }
                    }
                    replies.sort(
                            Comparator.comparing((Ranked r) -> r.sequence() == null)
                                    .thenComparing(
                                            Ranked::sequence,
                                            Comparator.nullsLast(Comparator.reverseOrder()))
                                    .thenComparing(Ranked::observedAt, Comparator.reverseOrder()));
                    return replies.stream().map(Ranked::table).toList();
                });
    }

    private record Ranked(Long sequence, Instant observedAt, VersionsTable table) {}

    private static Long number(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void trim(Map<ObjectKey, History.Entry> latest) {
        List<History.Entry> ordered =
                latest.values().stream()
                        .sorted(
                                Comparator.comparing(History.Entry::observedAt)
                                        .reversed()
                                        .thenComparing(History.Entry::record))
                        .toList();
        for (int i = MAX_REPLIES; i < ordered.size(); i++) {
            latest.remove(ordered.get(i).document());
        }
    }

    public static CdnRoute route(List<VersionsTable> newestFirst, String region) {
        for (VersionsTable table : newestFirst) {
            if (region != null) {
                CdnRoute found = routeOf(table, region);
                if (found != null) {
                    return found;
                }
                continue;
            }
            CdnRoute us = routeOf(table, "us");
            if (us != null) {
                return us;
            }
            for (var row : table.rows()) {
                CdnRoute found = routeOf(row);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static CdnRoute routeOf(VersionsTable table, String region) {
        return table.rows().stream()
                .filter(r -> region.equals(r.fields().get("Name")))
                .findFirst()
                .map(CdnsRoutes::routeOf)
                .orElse(null);
    }

    private static CdnRoute routeOf(VersionsTable.Row row) {
        String path = row.fields().get("Path"), hosts = row.fields().get("Hosts");
        if (path == null || hosts == null) {
            return null;
        }
        try {
            return new CdnRoute(List.of(hosts.split(" +")), path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
