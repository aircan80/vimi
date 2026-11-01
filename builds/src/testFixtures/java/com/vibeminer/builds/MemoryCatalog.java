package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.builds.CatalogTypes.BuildList;
import com.vibeminer.builds.CatalogTypes.BuildRegion;
import com.vibeminer.builds.CatalogTypes.CatalogBuild;
import com.vibeminer.builds.CatalogTypes.CatalogProblem;
import com.vibeminer.builds.CatalogTypes.CatalogRefresh;
import com.vibeminer.builds.CatalogTypes.SummaryRefresh;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Independent no-disk projection fake used by layers 4+ and shared catalog contracts. */
public final class MemoryCatalog implements BuildCatalog {
    private record Entry(History.Entry entry, VersionsReply.Read read) {}

    private record Listing(Entry entry, VersionsReply.Sighting sighting) {}

    private record RegionKey(String region, String cdn) {}

    private final ObjectSource source;
    private final String sourceRegion;
    private final Map<ObjectKey, Entry> read = new HashMap<>();
    private final CatalogTasks tasks = new CatalogTasks("memory-catalog", 1, 64, () -> {});

    public MemoryCatalog(ObjectSource source) {
        this(source, "us");
    }

    public MemoryCatalog(ObjectSource source, String sourceRegion) {
        this.source = java.util.Objects.requireNonNull(source);
        if (!BuildIdentity.isValidRegion(sourceRegion)) {
            throw new IllegalArgumentException("Invalid source region");
        }
        this.sourceRegion = sourceRegion;
    }

    @Override
    public CompletableFuture<BuildList> listBuilds(String product, Cancellation token) {
        Catalog.validate(product);
        return tasks.submit(token, () -> list(product, token));
    }

    @Override
    public CompletableFuture<CatalogRefresh> refresh(String product, Cancellation token) {
        Catalog.validate(product);
        return tasks.submit(
                token,
                () ->
                        new CatalogRefresh(
                                product,
                                observe(ObservationKind.VERSIONS, product, token),
                                observe(ObservationKind.CDNS, product, token),
                                list(product, token)));
    }

    @Override
    public CompletableFuture<SummaryRefresh> refreshSummary(Cancellation token) {
        return tasks.submit(
                token, () -> Summaries.read(observe(ObservationKind.SUMMARY, "", token)));
    }

    private ObservationResult observe(ObservationKind kind, String product, Cancellation token) {
        try {
            return source.observe(new ObservationRequest(kind, product, sourceRegion), token)
                    .join();
        } catch (RuntimeException error) {
            Throwable failure = CatalogTasks.unwrap(error);
            if (failure instanceof CancellationException canceled) {
                throw canceled;
            }
            return new ObservationResult(
                    ObjectState.UNAVAILABLE,
                    null,
                    false,
                    "CATALOG_OBSERVE_FAILED",
                    "Observing " + kind + " failed",
                    null);
        }
    }

    private BuildList list(String product, Cancellation token) {
        List<CatalogProblem> transientProblems = new ArrayList<>();
        History.Entry after = null;
        while (true) {
            token.check();
            List<History.Entry> page =
                    source.readHistory(new History.Query(null, null, 1, after, 1000), token).join();
            for (History.Entry entry : page) {
                if (read.containsKey(entry.record())) {
                    continue;
                }
                var got =
                        source.getKey(
                                        new ObjectRequest(entry.document()),
                                        null,
                                        FetchPriority.INTERACTIVE,
                                        token)
                                .join();
                byte[] bytes = got.bytes();
                if (got.isPresent() && bytes != null) {
                    read.put(entry.record(), new Entry(entry, VersionsReply.read(entry, bytes)));
                } else if (entry.product().equals(product)) {
                    transientProblems.add(Catalog.unreadable(entry, got));
                }
            }
            if (page.size() < 1000) {
                break;
            }
            after = page.getLast();
        }
        if (transientProblems.size() > 50) {
            int extra = transientProblems.size() - 50;
            transientProblems = new ArrayList<>(transientProblems.subList(0, 50));
            transientProblems.add(
                    new CatalogProblem(
                            "CATALOG_PROBLEMS_TRUNCATED",
                            extra + " more history entries cannot be read now"));
        }
        List<Entry> mine =
                read.values().stream().filter(e -> e.entry().product().equals(product)).toList();
        Map<String, Entry> currentByHost = new HashMap<>();
        Comparator<Entry> rank =
                Comparator.comparing((Entry e) -> e.read().sequenceNumber() != null)
                        .thenComparing(
                                e -> e.read().sequenceNumber(),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(e -> ms(e.entry().observedAt()))
                        .thenComparing(e -> e.entry().record());
        for (Entry entry : mine) {
            if (entry.read().readable()) {
                currentByHost.merge(
                        entry.entry().region(), entry, (a, b) -> rank.compare(a, b) >= 0 ? a : b);
            }
        }
        Set<ObjectKey> current = new HashSet<>();
        currentByHost.values().forEach(e -> current.add(e.entry().record()));
        Map<String, List<Listing>> builds = new HashMap<>();
        for (Entry entry : mine) {
            for (var sighting : entry.read().sightings()) {
                builds.computeIfAbsent(sighting.identity().id(), ignored -> new ArrayList<>())
                        .add(new Listing(entry, sighting));
            }
        }
        List<CatalogBuild> listed = new ArrayList<>();
        for (var build : builds.entrySet()) {
            Map<RegionKey, List<Listing>> regions = new LinkedHashMap<>();
            for (Listing listing : build.getValue()) {
                regions.computeIfAbsent(
                                new RegionKey(
                                        listing.sighting().region(),
                                        listing.sighting().cdnConfig()),
                                ignored -> new ArrayList<>())
                        .add(listing);
            }
            List<BuildRegion> regionList = new ArrayList<>();
            for (var region : regions.entrySet()) {
                Listing last =
                        region.getValue().stream()
                                .max(
                                        Comparator.comparing(
                                                        (Listing l) ->
                                                                ms(l.entry().entry().observedAt()))
                                                .thenComparing(l -> l.entry().entry().record()))
                                .orElseThrow();
                Instant first =
                        region.getValue().stream()
                                .map(l -> ms(l.entry().entry().observedAt()))
                                .min(Comparator.naturalOrder())
                                .orElseThrow();
                regionList.add(
                        new BuildRegion(
                                region.getKey().region(),
                                region.getKey().cdn(),
                                first,
                                ms(last.entry().entry().observedAt()),
                                region.getValue().stream()
                                        .anyMatch(
                                                l -> current.contains(l.entry().entry().record())),
                                last.entry().entry().record()));
            }
            regionList.sort(
                    Comparator.comparing(BuildRegion::region)
                            .thenComparing(BuildRegion::firstSeen)
                            .thenComparing(BuildRegion::cdnConfig));
            Map<String, String> f = build.getValue().getFirst().sighting().identity().fields();
            String pc = f.get("ProductConfig");
            if (pc != null && pc.isEmpty()) {
                pc = null;
            }
            listed.add(
                    new CatalogBuild(
                            product,
                            build.getKey(),
                            f.get("VersionsName"),
                            f.get("BuildConfig"),
                            pc,
                            f,
                            regionList.stream()
                                    .map(BuildRegion::firstSeen)
                                    .min(Comparator.naturalOrder())
                                    .orElseThrow(),
                            regionList.stream()
                                    .map(BuildRegion::lastSeen)
                                    .max(Comparator.naturalOrder())
                                    .orElseThrow(),
                            regionList.stream().anyMatch(BuildRegion::current),
                            regionList));
        }
        listed.sort(
                Comparator.comparing(CatalogBuild::firstSeen)
                        .reversed()
                        .thenComparing(CatalogBuild::id));
        List<CatalogProblem> problems = new ArrayList<>();
        for (Entry entry :
                mine.stream()
                        .sorted(
                                Comparator.comparing((Entry e) -> ms(e.entry().observedAt()))
                                        .reversed()
                                        .thenComparing(
                                                e -> e.entry().record(), Comparator.reverseOrder()))
                        .toList()) {
            var ordered =
                    entry.read().problems().stream()
                            .sorted(Comparator.comparing(p -> p.row() == null ? -1 : p.row()))
                            .toList();
            for (var problem : ordered) {
                if (problems.size() < CatalogStore.MAX_PROBLEMS) {
                    problems.add(problem);
                }
            }
        }
        problems.addAll(transientProblems);
        Instant newest =
                mine.stream()
                        .map(e -> ms(e.entry().observedAt()))
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        return new BuildList(product, listed, problems, newest);
    }

    private static Instant ms(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    public CompletableFuture<Void> closeAsync() {
        return tasks.closeAsync();
    }
}
