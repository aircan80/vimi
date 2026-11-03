package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.NativeIssueIdentity;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Source-free application composition; forbidden ordinary table methods fail immediately. */
final class LocalEffectsComparisonTest {
    private LocalEffectsComparisonTest() {}
    static void run(Path directory) throws Exception {
        directCaptureOnceAndPreparedCore(directory);
        unsupportedTargetRetainsQualifiedBridge(directory);
        incompatibleModesRefuseBeforeCore(directory);
        pendingMetadataTurnoverAndFailedPreparation(directory);
        coordinatorPreparedEffectsAdmission(directory, false);
        coordinatorPreparedEffectsAdmission(directory, true);
        canceledCaptureDeliveryIsClosed(directory);
        cancellationDrainsHeldPreparedRead(directory);
        System.out.println("Item effects composition: capture-once profiles, reference-only scope, forbidden-source and cancellation checks passed");
    }
    private static void require(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
    private static BuildRef build(String key) { return new BuildRef("wowf", key.repeat(32), "c".repeat(32), "us"); }
    private static EffectsSnapshot.Endpoint endpoint(BuildRef build, String id) {
        return new EffectsSnapshot.Endpoint(build, "enUS", id, Map.of("comparison.acquisition", "local-only"));
    }
    private static ComparisonTypes.Descriptor pending(EffectsSnapshot.Endpoint endpoint) {
        return new ComparisonTypes.Descriptor(endpoint.sessionId(), endpoint.build().product(), endpoint.locale(),
                EffectsSnapshot.NAMESPACE, "effects-pending", endpoint.dependencies());
    }
    private static Db2Types.Row row(long id, String... rest) {
        var values = new ArrayList<String>();
        values.add(Long.toString(id));
        values.addAll(List.of(rest));
        return new Db2Types.Row(id, false, null, values, List.of());
    }
    private static final class Capture implements CapturedRows {
        final CapturedRowsTypes.Receipt receipt;
        final List<Db2Types.Row> rows;
        int closes;
        boolean held;
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<CapturedRowsTypes.Page> waiting = new CompletableFuture<>();
        Capture(BuildRef build, SourceKind kind, List<Db2Types.Row> rows, boolean unsupported) throws Exception {
            this.rows = rows;
            var p = kind.profile();
            var selected = BundledDefinitions.open(Cancellation.NONE).join()
                    .require(kind.tableHash().toLowerCase(java.util.Locale.ROOT)).document()
                    .selectFile(kind == SourceKind.DIRECT_EFFECT ? "1.15.9.70003" : "12.1.5.70448", p.layout(), Cancellation.NONE);
            var schema = TableSchema.fromBlock(selected.blocks().getFirst(), TableSchema.SchemaMatch.BUILD, WowLocale.EN_US);
            var columns = new ArrayList<Db2Types.Column>();
            for (int i = 0; i < p.columns().size(); i++) {
                columns.add(new Db2Types.Column(p.columns().get(i), "integer", i == 0, null, null));
            }
            if (unsupported) {
                var fields = new ArrayList<>(schema.columns());
                fields.set(1, new TableSchema.SchemaColumn("FutureName_lang", TableSchema.ColumnKind.LOCSTRING,
                        null, null, 1, null, TableSchema.KeyStorage.NONE, TableSchema.KeyStorage.NONE,
                        true, WowLocale.EN_US, null, null));
                schema = new TableSchema(fields, List.of("BADCAFE0"), TableSchema.SchemaMatch.BUILD, WowLocale.EN_US, null);
                columns.set(1, new Db2Types.Column("FutureName_lang", "string", false, null, null));
            }
            String content = hash(rows.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            String graph = hash((kind + content).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            String options = "a".repeat(64), block = "b".repeat(64);
            String layout = unsupported ? "BADCAFE0" : p.layout();
            byte[] tuple = NativeIssueIdentity.tuple(GeneratedTableSource.NATIVE_IDENTITY_FORMAT,
                    "native-row-issues-v1", "java-db2-native-producer-v1", unsupported ? "enUS" : null, content, block, options,
                    GeneratedTableSource.READER_REVISION, GeneratedTableSource.ROW_ENCODING, null);
            receipt = new CapturedRowsTypes.Receipt(build, "enUS",
                    new TableTypes.Entry(p.fileDataId(), p.table(), TableTypes.State.PARTIAL, true, null,
                            new TableTypes.Counts(rows.size() + 1, rows.size(), 1, 1, "WDC5", layout), content),
                    schema, columns, "d".repeat(64), block, graph, "native-tuple-hex=" + HexFormat.of().formatHex(tuple),
                    options, GeneratedTableSource.READER_REVISION, GeneratedTableSource.ROW_ENCODING, null,
                    rows.size(), new Db2Types.Header(1, kind.tableHash(), layout, rows.size() + 1, 0, 40, columns, "WDC5"),
                    new Db2Types.Completion(rows.size() + 1, rows.size(), 0, 1, 1, 0, "partial", 100, 0),
                    List.of(), List.of(), List.of(), CapturedRowsTypes.Retention.BEST_EFFORT_INVALIDATING,
                    CapturedRowsTypes.Membership.NOT_CERTIFIED,
                    new CapturedRowsTypes.IssueChannelEvidence(CapturedRowsTypes.IssueChannelState.VERIFIED_NATIVE,
                            "native-row-issues-v1", "java-db2-native-producer-v1", graph, hash(tuple), block, options,
                            NativeIssueIdentity.columns(columns)));
        }
        @Override public CapturedRowsTypes.Receipt receipt() { return receipt; }
        CapturedRowsTypes.Page page(long at) {
            return new CapturedRowsTypes.Page(receipt.graphIdentity(), at, CapturedRowsTypes.ReadState.READ,
                    List.of(new TableTypes.Row(at, rows.get((int) at))), at + 1 == rows.size() ? null : at + 1, null, null);
        }
        @Override public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(long at, int max, Cancellation token) {
            token.check();
            require(closes == 0 && max == 1, "Capture read lifetime and bound");
            entered.countDown();
            return held ? waiting : CompletableFuture.completedFuture(page(at));
        }
        @Override public CompletableFuture<Void> closeAsync() { closes++; return CompletableFuture.completedFuture(null); }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static final class Tables {
        final BuildRef build;
        final Map<Long, Capture> sources = new LinkedHashMap<>();
        final Map<Long, Integer> captures = new LinkedHashMap<>();
        int closes;
        Long cancelOnCapture;
        final BuildTables tables;
        Tables(BuildRef build) {
            this.build = build;
            tables = (BuildTables) Proxy.newProxyInstance(BuildTables.class.getClassLoader(), new Class<?>[] {BuildTables.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "build" -> build;
                        case "locale" -> "enUS";
                        case "capturePreparedLocal" -> {
                            var id = ((TableTypes.Ref) args[0]).fileDataId();
                            captures.merge(id, 1, Integer::sum);
                            if (id == 3177687 && sources.containsKey(969941L)) {
                                require(sources.get(969941L).closes == 1, "Heavy effect capture drains before bridge successor");
                            }
                            var source = sources.get(id);
                            if (cancelOnCapture != null && cancelOnCapture.equals(id)) { ((Cancellation) args[1]).cancel(); }
                            yield CompletableFuture.completedFuture(source == null
                                    ? new CapturedRowsTypes.Capture(CapturedRowsTypes.CaptureState.NOT_PREPARED, null, "MISSING", "authored")
                                    : new CapturedRowsTypes.Capture(CapturedRowsTypes.CaptureState.CAPTURED, source, null, null));
                        }
                        case "closeAsync" -> { closes++; yield CompletableFuture.completedFuture(null); }
                        case "close" -> { closes++; yield null; }
                        case "toString" -> "Forbidden-source authored tables";
                        default -> throw new AssertionError("Forbidden ordinary/acquisition API: " + method.getName());
                    });
        }
        Tables add(Capture capture) { sources.put(capture.receipt.table().fileDataId(), capture); return this; }
    }
    private static PreparedEffectsComparisonInput input(Tables tables, EffectsSnapshot.Endpoint endpoint,
            Path directory, TempBudget budget) {
        return new PreparedEffectsComparisonInput(pending(endpoint), endpoint,
                (snapshot, side, token) -> LocalEffectsComparison.capture(tables.tables, side, snapshot, directory, budget, token));
    }

    private static void directCaptureOnceAndPreparedCore(Path directory) throws Exception {
        var aTables = new Tables(build("a")).add(new Capture(build("a"), SourceKind.DIRECT_EFFECT,
                List.of(row(1, "1", "2", "-1", "4", "5", "6", "77", "8", "9", "123")), false));
        var bTables = new Tables(build("b")).add(new Capture(build("b"), SourceKind.DIRECT_EFFECT,
                List.of(row(1, "1", "2", "-1", "4", "5", "6", "78", "8", "9", "123")), false));
        try (var budget = new TempBudget()) {
            var pendingMetadata = budget.reserveMemory("effects-pending-metadata", 1L << 20, Cancellation.NONE);
            var aEndpoint = endpoint(aTables.build, "direct-A");
            var bEndpoint = endpoint(bTables.build, "direct-B");
            var a = input(aTables, aEndpoint, directory, budget);
            var b = input(bTables, bEndpoint, directory, budget);
            try (var snapshot = LocalComparisonFactory.beginEffectsSnapshot(budget, pendingMetadata, aEndpoint, bEndpoint, Cancellation.NONE)) {
                require(budget.snapshot().memory() == 1L << 20 && budget.snapshot().peakMemory() == 2L << 20,
                        "Pending-to-snapshot turnover reserves both before releasing pending metadata");
                a.capture(snapshot, EffectsSnapshot.Side.A, Cancellation.NONE);
                b.capture(snapshot, EffectsSnapshot.Side.B, Cancellation.NONE);
                require(snapshot.sealScope().identity() == IdentityAdmission.ADMITTED, "Direct paired mode admitted");
                LocalComparisonFactory.finishEffectsInputs(snapshot, a, b);
                require(a.descriptor().namespace().equals(EffectsSnapshot.NAMESPACE), "Namespace stable through prepare");
                try (var service = new ComparisonService(directory, budget, ComparisonService.MemoryPlan.withSnapshotMetadata(), snapshot.metadataReservation())) {
                    require(service.start(a, b).join().state() == ComparisonTypes.RunState.COMPLETED, "Effects scan completes");
                    var page = service.page(0, 100).join();
                    require(page.rows().size() == 1 && page.rows().getFirst().fields().stream().anyMatch(f ->
                            f.key().equals("effect.DIRECT_EFFECT.SpellID") && f.difference() == ComparisonTypes.Difference.CHANGED),
                            "Recorded SpellID compared through real twelve MiB sealed inputs");
                    require(snapshot.metadataReservation().size() == 1L << 20, "Snapshot stays held during paging");
                }
                a.closeAsync().join(); b.closeAsync().join();
                require(budget.snapshot().memory() == 1L << 20 && budget.snapshot().disk() == 0, "Input cleanup leaves metadata only");
            } finally { a.closeAsync().join(); b.closeAsync().join(); }
        }
        require(aTables.captures.equals(Map.of(969941L, 1)) && bTables.captures.equals(Map.of(969941L, 1)),
                "Direct captures ItemEffect once and never probes bridge");
        require(aTables.closes == 0 && bTables.closes == 0, "Borrowed tables remain caller owned");
    }

    private static void unsupportedTargetRetainsQualifiedBridge(Path directory) throws Exception {
        try (var budget = new TempBudget()) {
            var aTables = new Tables(build("a")).add(new Capture(build("a"), SourceKind.RETAIL_EFFECT, List.of(), true))
                    .add(new Capture(build("a"), SourceKind.BRIDGE, List.of(row(1, "77", "123")), false));
            var bTables = new Tables(build("b")).add(new Capture(build("b"), SourceKind.BRIDGE, List.of(row(1, "78", "123")), false));
            var aEndpoint = endpoint(aTables.build, "bridge-A"); var bEndpoint = endpoint(bTables.build, "bridge-B");
            var a = input(aTables, aEndpoint, directory, budget); var b = input(bTables, bEndpoint, directory, budget);
            try (var snapshot = new EffectsSnapshot(budget, aEndpoint, bEndpoint, Cancellation.NONE)) {
                try {
                    a.capture(snapshot, EffectsSnapshot.Side.A, Cancellation.NONE); b.capture(snapshot, EffectsSnapshot.Side.B, Cancellation.NONE);
                    var scope = snapshot.sealScope();
                    require(scope.identity() == IdentityAdmission.ADMITTED && !scope.targetAssessed(), "Qualified reference-only bridge remains useful");
                    a.finish(snapshot, EffectsSnapshot.Side.A); b.finish(snapshot, EffectsSnapshot.Side.B);
                    require(a.descriptor().dependencies().get("effects.scope.targetAssessed").equals("false")
                            && a.descriptor().dependencies().get("effects.scope.reasons").contains("globally unassessed"),
                            "Reference-only scope exposes omitted target families once in captured details");
                    try (var service = new ComparisonService(directory, budget, ComparisonService.MemoryPlan.withSnapshotMetadata(), snapshot.metadataReservation())) {
                        service.start(a, b).join();
                        var result = service.page(0, 100).join().rows().getFirst();
                        require(result.fields().size() == 2 && result.fields().stream().anyMatch(f ->
                                f.key().equals("effect.BRIDGE.ItemEffectID") && f.difference() == ComparisonTypes.Difference.CHANGED),
                                "Reference change does not fabricate unsupported effect-input family");
                    }
                    require(aTables.captures.equals(Map.of(969941L, 1, 3177687L, 1)), "Unsupported direct/retail probes use SAME one held capture");
                    require(aTables.sources.get(969941L).closes == 1, "Unsupported target capture closes exactly once");
                } finally { a.closeAsync().join(); b.closeAsync().join(); }
            }
        }
    }

    private static void incompatibleModesRefuseBeforeCore(Path directory) throws Exception {
        try (var budget = new TempBudget()) {
            var direct = new Tables(build("a")).add(new Capture(build("a"), SourceKind.DIRECT_EFFECT,
                    List.of(row(1, "1", "2", "3", "4", "5", "6", "77", "8", "9", "123")), false));
            var bridge = new Tables(build("b")).add(new Capture(build("b"), SourceKind.BRIDGE, List.of(row(1, "77", "123")), false));
            var aEndpoint = endpoint(direct.build, "mixed-A"); var bEndpoint = endpoint(bridge.build, "mixed-B");
            var a = input(direct, aEndpoint, directory, budget); var b = input(bridge, bEndpoint, directory, budget);
            try (var snapshot = new EffectsSnapshot(budget, aEndpoint, bEndpoint, Cancellation.NONE)) {
                try {
                    a.capture(snapshot, EffectsSnapshot.Side.A, Cancellation.NONE); b.capture(snapshot, EffectsSnapshot.Side.B, Cancellation.NONE);
                    require(snapshot.sealScope().identity() != IdentityAdmission.ADMITTED, "Mixed relation profiles refuse capability");
                    try { LocalComparisonFactory.finishEffectsInputs(snapshot, a, b); throw new AssertionError("Mixed modes accepted"); }
                    catch (IllegalStateException expected) { }
                } finally { a.closeAsync().join(); b.closeAsync().join(); }
                require(budget.snapshot().disk() == 0, "Rejected candidates deleted before metadata closes");
            }
        }
    }

    private static void canceledCaptureDeliveryIsClosed(Path directory) throws Exception {
        try (var budget = new TempBudget()) {
            var tables = new Tables(build("a")).add(new Capture(build("a"), SourceKind.DIRECT_EFFECT, List.of(), false));
            tables.cancelOnCapture = 969941L;
            var aEndpoint = endpoint(tables.build, "cancel-delivery");
            try (var snapshot = new EffectsSnapshot(budget, aEndpoint, endpoint(build("b"), "other"), Cancellation.NONE)) {
                try {
                    LocalEffectsComparison.capture(tables.tables, EffectsSnapshot.Side.A, snapshot, directory, budget, new Cancellation());
                    throw new AssertionError("Expected cancellation after capture delivery");
                } catch (java.util.concurrent.CancellationException expected) { }
                require(tables.sources.get(969941L).closes == 1 && tables.captures.size() == 1,
                        "Delivered capture has an owner before token check");
                require(budget.snapshot().disk() == 0, "Canceled capture leaves no staged files");
            }
        }
    }

    private static void cancellationDrainsHeldPreparedRead(Path directory) throws Exception {
        try (var budget = new TempBudget()) {
            var captured = new Capture(build("a"), SourceKind.DIRECT_EFFECT,
                    List.of(row(1, "1", "2", "3", "4", "5", "6", "77", "8", "9", "123")), false);
            captured.held = true;
            var tables = new Tables(build("a")).add(captured);
            var aEndpoint = endpoint(tables.build, "held-A");
            try (var snapshot = new EffectsSnapshot(budget, aEndpoint, endpoint(build("b"), "other"), Cancellation.NONE)) {
                var token = new Cancellation();
                var running = CompletableFuture.runAsync(() -> {
                    try { LocalEffectsComparison.capture(tables.tables, EffectsSnapshot.Side.A, snapshot, directory, budget, token); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                require(captured.entered.await(5, TimeUnit.SECONDS), "Prepared read entered");
                token.cancel();
                require(!running.isDone() && captured.closes == 0, "Cancellation waits actual prepared read drain");
                captured.waiting.complete(captured.page(0));
                try { running.get(5, TimeUnit.SECONDS); throw new AssertionError("Expected canceled capture"); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expected) { }
                require(captured.closes == 1 && tables.closes == 0, "Capture drains while borrowed endpoint remains open");
                require(budget.snapshot().disk() == 0 && budget.snapshot().memory() == 1L << 20, "Cancellation leaves metadata only");
            }
        }
    }
    private static void pendingMetadataTurnoverAndFailedPreparation(Path directory) throws Exception {
        try (var budget = new TempBudget()) {
            var pending = budget.reserveMemory("effects-pending-metadata", 1L << 20, Cancellation.NONE);
            var aTables = new Tables(build("a")); var bTables = new Tables(build("b"));
            var aEndpoint = endpoint(aTables.build, "unavailable-A"); var bEndpoint = endpoint(bTables.build, "unavailable-B");
            var a = input(aTables, aEndpoint, directory, budget); var b = input(bTables, bEndpoint, directory, budget);
            try (var snapshot = LocalComparisonFactory.beginEffectsSnapshot(budget, pending, aEndpoint, bEndpoint, Cancellation.NONE)) {
                try {
                    a.capture(snapshot, EffectsSnapshot.Side.A, Cancellation.NONE); b.capture(snapshot, EffectsSnapshot.Side.B, Cancellation.NONE);
                    try { LocalComparisonFactory.finishEffectsInputs(snapshot, a, b); throw new AssertionError("Empty unqualified scope accepted"); }
                    catch (IllegalStateException expected) {
                        require(expected.getMessage().contains("CAPTURE_NOT_PREPARED"), "Failed capability keeps concrete source outcomes");
                    }
                    require(budget.snapshot().memory() == 1L << 20, "Failed prepare retains exactly its live snapshot lease");
                } finally { a.closeAsync().join(); b.closeAsync().join(); }
                require(budget.snapshot().disk() == 0, "Failed prepare deletes all candidate spools");
            }
            require(budget.snapshot().memory() == 0, "Failed preparation releases pending and final metadata owners");
        }
        try (var budget = new TempBudget()) {
            var pending = budget.reserveMemory("effects-pending-metadata", 1L << 20, Cancellation.NONE);
            var incompatible = new EffectsSnapshot.Endpoint(new BuildRef("wow", "a".repeat(32), "c".repeat(32)),
                    "enUS", "wrong-product", Map.of());
            try {
                LocalComparisonFactory.beginEffectsSnapshot(budget, pending, endpoint(build("a"), "A"), incompatible, Cancellation.NONE);
                throw new AssertionError("Incompatible endpoint metadata admitted");
            } catch (IllegalArgumentException expected) { }
            require(budget.snapshot().memory() == 1L << 20, "Failed snapshot admission leaves pending owner available for cleanup");
            pending.close();
        }
    }

    private static BuildInfo buildInfo(BuildRef ref) {
        return new BuildInfo(ref.product(), "Forever", "1.60.1.70755", "us", ref.buildConfig(),
                BuildInfo.BuildSource.SAVED, true, null, 0);
    }
    private static void coordinatorPreparedEffectsAdmission(Path directory, boolean mixed) throws Exception {
        var aTables = new Tables(build("a")).add(new Capture(build("a"), SourceKind.DIRECT_EFFECT,
                List.of(row(1, "1", "2", "3", "4", "5", "6", "77", "8", "9", "123")), false));
        var bTables = mixed
                ? new Tables(build("b")).add(new Capture(build("b"), SourceKind.BRIDGE, List.of(row(1, "77", "123")), false))
                : new Tables(build("b")).add(new Capture(build("b"), SourceKind.DIRECT_EFFECT,
                        List.of(row(1, "1", "2", "3", "4", "5", "6", "78", "8", "9", "123")), false));
        var ownerBudget = new java.util.concurrent.atomic.AtomicReference<TempBudget>();
        var createdCore = new java.util.concurrent.atomic.AtomicInteger();
        var coordinator = new ComparisonCoordinator((request, token) -> {
            var budget = new TempBudget(); ownerBudget.set(budget);
            var pending = budget.reserveMemory("effects-pending-metadata", 1L << 20, token);
            return new ComparisonCoordinator.EndpointOpener() {
                private EffectsSnapshot snapshot;
                public ComparisonCoordinator.Opened open(BuildInfo build, String locale, String area, Cancellation cancellation) {
                    var tables = build.config().equals(aTables.build.buildConfig()) ? aTables : bTables;
                    var endpoint = endpoint(tables.build, "coordinator-" + tables.build.buildConfig());
                    return new ComparisonCoordinator.Opened(input(tables, endpoint, directory, budget), () -> {
                        require(tables.sources.values().stream().allMatch(c -> c.closes == 1), "Inputs drain before borrowed endpoint closes");
                        tables.tables.closeAsync().join();
                    });
                }
                public void prepare(ComparisonCoordinator.Opened before, ComparisonCoordinator.Opened after, Cancellation cancellation) throws Exception {
                    var a = (PreparedEffectsComparisonInput) before.input(); var b = (PreparedEffectsComparisonInput) after.input();
                    snapshot = LocalComparisonFactory.beginEffectsSnapshot(budget, pending, a.endpoint(), b.endpoint(), cancellation);
                    a.capture(snapshot, EffectsSnapshot.Side.A, cancellation); b.capture(snapshot, EffectsSnapshot.Side.B, cancellation);
                    LocalComparisonFactory.finishEffectsInputs(snapshot, a, b);
                }
                public TempBudget budget() { return budget; }
                public ComparisonService.MemoryPlan memoryPlan() { return ComparisonService.MemoryPlan.withSnapshotMetadata(); }
                public TempBudget.Reservation retainedMetadata() { return snapshot.metadataReservation(); }
                public void close() { if (snapshot != null) snapshot.close(); pending.close(); budget.close(); }
            };
        }, directory, (parent, budget, plan, metadata) -> {
            createdCore.incrementAndGet();
            return new ComparisonService(parent, budget, plan, metadata);
        });
        try {
            var opening = coordinator.start(new BuildComparisons.Request(buildInfo(aTables.build), "enUS",
                    buildInfo(bTables.build), "enUS", "Item effects"), Cancellation.NONE);
            if (mixed) {
                try { opening.get(5, TimeUnit.SECONDS); throw new AssertionError("Mixed modes admitted to core"); }
                catch (java.util.concurrent.ExecutionException expected) { }
                require(createdCore.get() == 0, "Failed paired mode stops before core admission");
            } else {
                var session = opening.get(5, TimeUnit.SECONDS);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (session.progress().state() == ComparisonTypes.RunState.RUNNING) {
                    if (System.nanoTime() > until) throw new AssertionError("Effects coordinator scan stalled");
                    Thread.sleep(5);
                }
                require(session.progress().state() == ComparisonTypes.RunState.COMPLETED, "Actual coordinator accepts effects mode");
                require(session.page(0, 100).join().rows().size() == 1, "Coordinator exposes sourced effect finding");
                session.closeAsync().join();
                require(createdCore.get() == 1, "One actual comparison core");
            }
        } finally { coordinator.closeAsync().join(); }
        require(aTables.closes == 1 && bTables.closes == 1, "Dedicated endpoints close exactly once");
        require(ownerBudget.get().snapshot().memory() == 0 && ownerBudget.get().snapshot().disk() == 0,
                "Coordinator drains both snapshot owners and files on success/rejection");
    }

}
