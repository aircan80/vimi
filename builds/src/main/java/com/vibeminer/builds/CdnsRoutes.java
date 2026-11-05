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
import java.util.Set;
import java.util.TreeSet;
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
        return newestFirst(source, product, 2, token);
    }

    /** Recent distinct versions replies, read offline like {@link #newestFirst}. */
    public static CompletableFuture<List<VersionsTable>> newestVersionsFirst(
            ObjectSource source, String product, Cancellation token) {
        return newestFirst(source, product, 1, token);
    }

    private static CompletableFuture<List<VersionsTable>> newestFirst(
            ObjectSource source, String product, int kind, Cancellation token) {
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
                                                            product, null, kind, after, 1000),
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

    /**
     * How a build reaches the CDN. Only official hosts are ever accepted ({@link CdnRoute}); a
     * region without one may borrow another region's accepted route, but only when that region
     * advertises the same build with the exact same CDN config.
     */
    public record Resolution(CdnRoute route, String requested, String served, String note) {
        public boolean fallback() {
            return route != null && requested != null && !requested.equals(served);
        }
    }

    public static Resolution resolve(
            List<VersionsTable> cdnsNewestFirst, String region, Set<String> sameConfigRegions) {
        CdnRoute direct = route(cdnsNewestFirst, region);
        if (direct != null) {
            return new Resolution(direct, region, region, null);
        }
        if (cdnsNewestFirst.isEmpty()) {
            return new Resolution(
                    null, region, null, "No CDN host list is stored for this product");
        }
        if (region == null) {
            return new Resolution(null, null, null, "No accepted CDN host is listed");
        }
        List<String> candidates =
                sameConfigRegions.stream()
                        .filter(other -> !other.equals(region))
                        .sorted(
                                Comparator.comparing((String other) -> !other.equals("us"))
                                        .thenComparing(Comparator.naturalOrder()))
                        .toList();
        for (String other : candidates) {
            CdnRoute borrowed = route(cdnsNewestFirst, other);
            if (borrowed != null) {
                return new Resolution(
                        borrowed,
                        region,
                        other,
                        "Region "
                                + label(region)
                                + " has no accepted CDN host; files come from "
                                + label(other)
                                + ", which serves the same CDN config");
            }
        }
        return new Resolution(
                null,
                region,
                null,
                "No accepted CDN host for region "
                        + region
                        + (candidates.isEmpty()
                                ? ", and no other region serves the same CDN config"
                                : ", and no region serving the same CDN config has one"));
    }

    /** Regions whose versions rows list this build config with exactly this CDN config. */
    public static Set<String> regionsServing(
            List<VersionsTable> versions, String buildConfig, String cdnConfig) {
        Set<String> regions = new TreeSet<>();
        for (VersionsTable table : versions) {
            for (var row : table.rows()) {
                String region = row.fields().get("Region");
                if (region != null
                        && buildConfig.equalsIgnoreCase(row.fields().get("BuildConfig"))
                        && cdnConfig.equalsIgnoreCase(row.fields().get("CDNConfig"))) {
                    regions.add(region);
                }
            }
        }
        return regions;
    }

    /** Regions with at least one accepted route in these CDN tables. */
    public static Set<String> routedRegions(List<VersionsTable> cdnsNewestFirst) {
        Set<String> regions = new TreeSet<>();
        for (VersionsTable table : cdnsNewestFirst) {
            for (var row : table.rows()) {
                String name = row.fields().get("Name");
                if (name != null) {
                    regions.add(name);
                }
            }
        }
        regions.removeIf(name -> route(cdnsNewestFirst, name) == null);
        return regions;
    }

    private static String label(String region) {
        return region.toUpperCase(java.util.Locale.ROOT);
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
