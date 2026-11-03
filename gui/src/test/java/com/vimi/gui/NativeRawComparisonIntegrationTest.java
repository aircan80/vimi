package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.MetadataFixtures;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.generated.GeneratedTableContent;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.generated.TactKeyStatus;
import com.vibeminer.generated.PackGeneratedStore;
import com.vibeminer.network.CdnClient;
import com.vibeminer.network.ListfileClient;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Authored rows in a real SQLite/pack graph; the cached original DB2 cannot be decoded. */
public final class NativeRawComparisonIntegrationTest {
    private static final long TABLE = 841626;
    private static final String VERSION = "1.60.1.70450";
    private NativeRawComparisonIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-native-raw-comparison-");
        try {
            var fixture = FullPreparationLibraryIntegrationTest.seed(directory);
            var library = ObjectStore.openLibrary(directory.resolve("library")).join();
            try (var catalog = LibraryCatalog.open(library.databasePath()).join();
                    var registry = BuildRegistry.open(library.databasePath()).join()) {
                var forbidden = new AtomicInteger();
                var history = PreparationHistory.forLibrary(library.databasePath());
                history.load();
                var contexts = new NativePreparationContexts(library, catalog, registry,
                        forbidden(CdnClient.class, forbidden), forbidden(ListfileClient.class, forbidden),
                        null, history, null, directory.resolve("grids"), ignored -> {});
                try {
                    String cdn = new SyntheticBuild().ref.cdnConfig();
                    var source = new BuildInfo.RegionEvidence("us", cdn, true, null, null);
                    var info = new BuildInfo("wow", "Authored fixture", VERSION, "us",
                            fixture.buildConfig().hex(), BuildInfo.BuildSource.CURRENT, true,
                            null, 0, "wow:" + fixture.buildConfig().hex(), null, null, List.of(source));
                    Files.createDirectories(directory.resolve("sort"));
                    var manager = new ComparisonCoordinator(new LocalComparisonFactory(contexts, null,
                            directory.resolve("sort")), directory.resolve("results"));
                    try {
                        var request = new BuildComparisons.Request(info, "enUS", info, "enUS", "Tables", TABLE);
                        boolean rejected = false;
                        try {
                            manager.start(request, new Cancellation()).get(20, TimeUnit.SECONDS);
                        } catch (java.util.concurrent.ExecutionException expected) {
                            rejected = true;
                        }
                        require(rejected, "Missing prepared graph fails without ordinary preparation");
                        require(forbidden.get() == 0, "Missing graph does not acquire bytes or names");
                        seedGraph(library, info, cdn);
                        var storedBefore = library.stats().join();
                        var before = registry.get(info.identity(), com.vibeminer.store.Cancellation.NONE).join();
                        var session = manager.start(request, new Cancellation()).get(20, TimeUnit.SECONDS);
                        try {
                            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                            while (session.progress().state() == ComparisonTypes.RunState.RUNNING) {
                                require(System.nanoTime() < end, "Native raw scan terminates");
                                Thread.sleep(5);
                            }
                            require(session.progress().state() == ComparisonTypes.RunState.COMPLETED,
                                    "Cached raw scan completes: " + session.progress().message());
                            require(session.progress().observed() == 2, "Both authored row identities scanned");
                            require(session.progress().a().completeness() == ComparisonTypes.Completeness.UNKNOWN
                                            && session.progress().b().completeness() == ComparisonTypes.Completeness.UNKNOWN,
                                    "Readable prepared rows do not certify global membership completeness");
                            require(session.page(0, 10).join().rows().isEmpty(),
                                    "Equal typed authored rows produce no false findings");
                            require(session.before().locale().equals("enUS"), "Requested locale pinned");
                            var dependencies = session.before().dependencies();
                            require(Long.toString(TABLE).equals(dependencies.get("raw.table.id"))
                                            && "9A2A4834".equals(dependencies.get("raw.layout")),
                                    "Native request pins the selected table and prepared layout");
                            require("prepared-local-only".equals(dependencies.get("comparison.acquisition"))
                                            && dependencies.get("raw.schema.fingerprint").matches("[0-9a-f]{64}")
                                            && dependencies.get("raw.graph.identity").matches("[0-9a-f]{32}"),
                                    "Native source records exact graph and interpretation fingerprints");
                            require(session.before().namespace().equals(session.after().namespace()),
                                    "Both prepared inputs use the same typed raw namespace");
                        } finally {
                            session.closeAsync().get(20, TimeUnit.SECONDS);
                        }
                        require(java.util.Objects.equals(before,
                                registry.get(info.identity(), com.vibeminer.store.Cancellation.NONE).join()),
                                "Comparison leaves build readiness unchanged");
                        require(forbidden.get() == 0, "No CDN/listfile/network capability invoked");
                        var storedAfter = library.stats().join();
                        require(storedBefore.objects() == storedAfter.objects()
                                        && storedBefore.liveBytes() == storedAfter.liveBytes()
                                        && storedBefore.packBytes() == storedAfter.packBytes(),
                                "Prepared comparison performs no object writes, repairs or graph rebuilding");
                    } finally {
                        manager.closeAsync().get(20, TimeUnit.SECONDS);
                    }
                } finally {
                    contexts.closeAsync().get(20, TimeUnit.SECONDS);
                }
            } finally {
                library.closeAsync().join();
            }
            System.out.println("Native raw comparison: authored SQLite/pack graph, missing-graph rejection, cached rows and forbidden acquisition passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void seedGraph(ObjectStore library, BuildInfo info, String cdn) {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        var manifest = provider.tables().stream().filter(t -> Long.toString(TABLE).equals(t.db2FileDataId())).findFirst().orElseThrow();
        var document = provider.tryGet(manifest.tableId()).definition().document();
        var selected = document.selectFile(VERSION, null, Cancellation.NONE);
        require(selected.blocks().size() == 1, "Fixture uses one exact bundled build block");
        var block = selected.blocks().getFirst();
        require(!block.layoutHashes().isEmpty(), "Fixture block has an explicit layout");
        var columns = new ArrayList<Db2Types.Column>();
        var values = new ArrayList<String>();
        for (var field : block.fields()) {
            for (int index = 0; index < field.arrayLength(); index++) {
                String name = field.column().name() + (field.arrayLength() == 1 ? "" : "[" + index + "]");
                String kind = switch (field.column().family()) {
                    case "int" -> "integer";
                    case "float" -> "float";
                    default -> "string";
                };
                columns.add(new Db2Types.Column(name, kind, field.isId(), null, null));
                values.add(field.isId() ? "7" : kind.equals("string") ? "Authored fixture text" : "0");
            }
        }
        var other = new ArrayList<>(values);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).isId()) { other.set(i, "8"); }
        }
        var rows = List.of(new Db2Types.Row(7, false, null, values, List.of()),
                new Db2Types.Row(8, false, null, other, List.of()));
        byte[] original = new byte[128];
        System.arraycopy(MetadataFixtures.text("WDC5"), 0, original, 0, 4);
        String ckey = java.util.HexFormat.of().formatHex(MetadataFixtures.md5(original));
        var base = new Db2Types.Limits();
        var limits = new Db2Types.Limits(base.maxDecodedBytes(), base.maxRows(), base.maxFields(),
                base.maxSections(), base.maxRecordBytes(), base.maxAuxiliaryBytes(), base.maxStringBytes(),
                base.maxIndexBytes(), base.maxColumns(), base.maxCopyDepth(), true, true,
                base.maxRawTextEvidenceBytes(), base.maxRelationshipBytes(), base.maxRelationshipEntries(),
                base.definitionSha256(), base.verifiedRawIntegerRules());
        var source = GeneratedTableSource.forBlock(block, "enUS", ckey, limits,
                info.product() + " " + info.config() + " " + cdn);
        var generated = new GeneratedTables(new PackGeneratedStore(library,
                library.forOwner(info.identity(), LibraryCatalog.Scope.BUILD, "workspace-derived", LibraryCatalog.Role.DERIVED)));
        var content = new GeneratedTableContent(new Db2Types.Header(1, manifest.tableId(),
                block.layoutHashes().getFirst(), 2, 0, 4, columns, "WDC5"), List.of(),
                new Db2Types.Completion(2, 2, 0, 0, 0, 0, "complete", 128, 0), List.of(),
                (first, count, token) -> CompletableFuture.completedFuture(rows.subList((int) first, Math.min(rows.size(), (int) first + count))),
                () -> {}, List.of(), List.of());
        generated.store(source, new TactKeyStatus(Map.of()), content, Cancellation.NONE).join();
    }

    @SuppressWarnings("unchecked")
    private static <T> T forbidden(Class<T> type, AtomicInteger calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("Forbidden acquisition: " + method.getName()));
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
