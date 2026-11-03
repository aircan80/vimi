package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonService;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SealedCandidateInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.inputs.FilesInventoryInput;
import com.vibeminer.concepts.comparison.inputs.PreparedTableRowsSource;
import com.vibeminer.concepts.comparison.inputs.TableInventoryInput;
import com.vibeminer.concepts.comparison.items.ItemsSnapshot;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.comparison.items.ItemsStageCodec;
import com.vibeminer.concepts.items.PreparedItemTypes;
import com.vibeminer.files.keys.TactKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures local-only input capabilities. No provider refresh, missing common-table preparation or
 * uncached-schema scan.
 */
final class LocalComparisonFactory implements ComparisonCoordinator.Factory {
    private final NativePreparationContexts contexts;
    private final DataSourcesService sources;
    private final java.nio.file.Path temporaryParent;

    LocalComparisonFactory(
            NativePreparationContexts contexts,
            DataSourcesService sources,
            java.nio.file.Path temporaryParent) {
        this.contexts = contexts;
        this.sources = sources;
        this.temporaryParent = java.util.Objects.requireNonNull(temporaryParent);
    }

    @Override
    public ComparisonCoordinator.EndpointOpener capture(
            BuildComparisons.Request request, Cancellation token) {
        token.check();
        // These are already-loaded immutable keys; tactKeysForBuild may refresh and is forbidden
        // here.
        TactKeys keys = sources == null ? TactKeys.EMPTY : sources.tactKeys();
        boolean definitions =
                sources == null || sources.policy(DataSourcesService.Id.DEFINITIONS).enabled();
        var receipts = contexts.comparisonReceipts();
        boolean items = request.area().equals("Items");
        boolean effects = request.area().equals("Item effects");
        var budget = request.tableId() == null && !items && !effects ? null : new TempBudget();
        final TempBudget.Reservation effectsPendingMetadata;
        try {
            effectsPendingMetadata = effects ? budget.reserveMemory("effects-pending-metadata", 1L << 20, token) : null;
        } catch (java.io.IOException failure) {
            budget.close();
            throw new java.util.concurrent.CompletionException(failure);
        }
        final ItemsSnapshot snapshot;
        try {
            snapshot =
                    items
                            ? new ItemsSnapshot(
                                    budget, PreparedItemTypes.Selection.defaults(), token)
                            : null;
        } catch (java.io.IOException failure) {
            if (effectsPendingMetadata != null) effectsPendingMetadata.close();
            budget.close();
            throw new java.util.concurrent.CompletionException(failure);
        }
        return new ComparisonCoordinator.EndpointOpener() {
            private int opened;
            private EffectsSnapshot effectsSnapshot;

            @Override
            public ComparisonCoordinator.Opened open(
                    BuildInfo build, String locale, String area, Cancellation cancellation) {
                return LocalComparisonFactory.this.open(
                        build,
                        locale,
                        area,
                        request.tableId(),
                        snapshot,
                        opened++ == 0 ? ItemsSnapshot.Side.A : ItemsSnapshot.Side.B,
                        budget,
                        keys,
                        definitions,
                        receipts,
                        cancellation);
            }

            @Override
            public void prepare(
                    ComparisonCoordinator.Opened before,
                    ComparisonCoordinator.Opened after,
                    Cancellation cancellation)
                    throws Exception {
                if (effects) {
                    var a = (PreparedEffectsComparisonInput) before.input();
                    var b = (PreparedEffectsComparisonInput) after.input();
                    effectsSnapshot = beginEffectsSnapshot(budget, effectsPendingMetadata, a.endpoint(), b.endpoint(), cancellation);
                    a.capture(effectsSnapshot, EffectsSnapshot.Side.A, cancellation);
                    b.capture(effectsSnapshot, EffectsSnapshot.Side.B, cancellation);
                    finishEffectsInputs(effectsSnapshot, a, b);
                } else if (snapshot != null) {
                    var a = (PreparedItemsComparisonInput) before.input();
                    var b = (PreparedItemsComparisonInput) after.input();
                    a.capture(cancellation);
                    b.capture(cancellation);
                    snapshot.sealPairScope();
                    if (snapshot.admittedFieldCount() == 0)
                        throw new IllegalStateException(
                                "No compatible recorded Items fields are available. "
                                        + String.join("; ", snapshot.reasons()));
                    var codec = new ItemsStageCodec(snapshot);
                    a.finish(
                            file ->
                                    SealedCandidateInput.adopt(
                                            file,
                                            itemsDescriptor(
                                                    request.before(),
                                                    request.beforeLocale(),
                                                    snapshot,
                                                    ItemsSnapshot.Side.A),
                                            budget,
                                            codec::projectCandidate));
                    b.finish(
                            file ->
                                    SealedCandidateInput.adopt(
                                            file,
                                            itemsDescriptor(
                                                    request.after(),
                                                    request.afterLocale(),
                                                    snapshot,
                                                    ItemsSnapshot.Side.B),
                                            budget,
                                            codec::projectCandidate));
                } else if (request.tableId() != null) {
                    ((PreparedRawComparisonInput) before.input()).prepare(cancellation);
                    ((PreparedRawComparisonInput) after.input()).prepare(cancellation);
                }
            }

            @Override
            public TempBudget budget() {
                return budget;
            }

            @Override
            public ComparisonService.MemoryPlan memoryPlan() {
                return snapshot == null && !effects
                        ? ComparisonService.MemoryPlan.standard()
                        : ComparisonService.MemoryPlan.withSnapshotMetadata();
            }

            @Override
            public TempBudget.Reservation retainedMetadata() {
                return snapshot != null ? snapshot.metadataReservation()
                        : effectsSnapshot == null ? null : effectsSnapshot.metadataReservation();
            }

            @Override
            public void close() {
                if (snapshot != null) snapshot.close();
                if (effectsSnapshot != null) effectsSnapshot.close();
                if (effectsPendingMetadata != null) effectsPendingMetadata.close();
                if (budget != null) budget.close();
            }
        };
    }

    static EffectsSnapshot beginEffectsSnapshot(TempBudget budget, TempBudget.Reservation pending,
            EffectsSnapshot.Endpoint a, EffectsSnapshot.Endpoint b, Cancellation token) throws java.io.IOException {
        pending.requireMemory(budget, EffectsSnapshot.METADATA_BYTES);
        var snapshot = new EffectsSnapshot(budget, a, b, token);
        pending.close();
        return snapshot;
    }

    static void finishEffectsInputs(EffectsSnapshot snapshot, PreparedEffectsComparisonInput a,
            PreparedEffectsComparisonInput b) throws Exception {
        var scope = snapshot.sealScope();
        if (scope.identity() != com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission.ADMITTED
                || scope.assessed().isEmpty()) {
            throw new IllegalStateException("No compatible recorded item-effect relation fields are available. "
                    + String.join("; ", scope.reasons()));
        }
        a.finish(snapshot, EffectsSnapshot.Side.A);
        b.finish(snapshot, EffectsSnapshot.Side.B);
    }

    private ComparisonCoordinator.Opened open(
            BuildInfo build,
            String locale,
            String area,
            Long tableId,
            ItemsSnapshot snapshot,
            ItemsSnapshot.Side side,
            TempBudget budget,
            TactKeys keys,
            boolean definitions,
            java.util.List<PreparationHistory.Receipt> receipts,
            Cancellation token) {
        var evidence = evidence(build, locale, receipts);
        NativePreparationWork work =
                contexts.createLocalComparison(build, evidence, locale, definitions, keys);
        ComparisonInput input = null;
        try {
            var metadata = work.metadata(token);
            work.files(ignored -> {}, token);
            token.check();
            var dependencies = new LinkedHashMap<String, String>();
            dependencies.put("build.identity", build.identity());
            dependencies.put("build.version", build.version());
            dependencies.put("build.config", build.config());
            dependencies.put("source.region", evidence.region());
            dependencies.put("source.cdnConfig", evidence.cdnConfig());
            dependencies.put("source.root", metadata.rootContentKey().hex());
            dependencies.put("source.encoding", metadata.encodingContentKey().hex());
            dependencies.put("source.communityTactFingerprint", keys.fingerprint());
            dependencies.put("comparison.policy", "local-inventory-v1");
            dependencies.put("comparison.results", "findings-only-session");
            dependencies.put("comparison.acquisition", "local-only");
            dependencies.put("comparison.names", "unassessed");
            dependencies.put("comparison.storage", "unassessed");
            if (area.equals("Items")) {
                if (!definitions)
                    throw new IllegalStateException(
                            "Table definitions are disabled; Items comparison is unavailable.");
                var issues = work.keysPreparedLocal(token);
                dependencies.put(
                        "items.bootstrap.key-issues",
                        String.join("; ", issues.stream().limit(16).toList()));
                dependencies.put("comparison.policy", "items-increment-A-v1");
                dependencies.put("comparison.names", "recorded-source-qualified");
                dependencies.put(
                        "scope.description",
                        "Recorded Item and ItemSparse facts; source-qualified conflicts retained;"
                                + " membership unknown; effects and assets unassessed");
                snapshot.registerEndpoint(side, dependencies);
                input =
                        new PreparedItemsComparisonInput(
                                descriptor(
                                        build,
                                        locale,
                                        "items.ItemID.v1",
                                        "items-increment-A-pending",
                                        dependencies),
                                cancellation ->
                                        LocalItemsComparison.capture(
                                                work,
                                                locale,
                                                dependencies,
                                                side,
                                                snapshot,
                                                temporaryParent,
                                                budget,
                                                cancellation));
            } else if (area.equals("Item effects")) {
                if (!definitions) {
                    throw new IllegalStateException("Table definitions are disabled; item effects comparison is unavailable.");
                }
                var issues = work.keysPreparedLocal(token);
                dependencies.put("effects.bootstrap.key-issues", String.join("; ", issues.stream().limit(16)
                        .map(issue -> issue.substring(0, Math.min(1024, issue.length()))).toList()));
                dependencies.put("comparison.policy", "item-effects-increment-B-v1");
                dependencies.put("scope.description", "Recorded item-effect links and individually verified numeric inputs; "
                        + "membership unknown; spell names, descriptions, formulas and assets unassessed");
                var endpoint = new EffectsSnapshot.Endpoint(work.filesHandle().build(), locale,
                        java.util.UUID.randomUUID().toString(), dependencies);
                input = new PreparedEffectsComparisonInput(descriptor(build, locale, EffectsSnapshot.NAMESPACE,
                        "item-effects-increment-B-pending", dependencies), endpoint,
                        (effectsSnapshot, effectsSide, cancellation) -> LocalEffectsComparison.capture(work,
                                locale, effectsSide, effectsSnapshot, temporaryParent, budget, cancellation));
            } else if (area.equals("Files")) {
                dependencies.put(
                        "scope.description",
                        "Selected-locale file content-key and decoded-size metadata; names and live"
                                + " storage unassessed");
                var descriptor =
                        descriptor(
                                build,
                                locale,
                                FilesInventoryInput.NAMESPACE,
                                FilesInventoryInput.SCOPE,
                                dependencies);
                input = new FilesInventoryInput(work.filesHandle(), descriptor, null);
            } else if (tableId != null) {
                if (!definitions)
                    throw new IllegalStateException(
                            "Table definitions are disabled. Files can still be compared.");
                var issues = work.keysPreparedLocal(token);
                dependencies.put(
                        "raw.bootstrap.key-issues",
                        String.join("; ", issues.stream().limit(16).toList()));
                input = rawInput(work, locale, tableId, dependencies, budget, token);
            } else {
                if (!definitions)
                    throw new IllegalStateException(
                            "Table definitions are disabled. Files can still be compared.");
                // Only cached key-table bytes may be read; work and its opener are both local-only.
                work.keys(token);
                var cached = work.cachedTableInventory(token);
                if (cached == null)
                    throw new IllegalStateException(
                            "No exact local prepared table inventory is available for this build,"
                                    + " locale and key snapshot. Open/prepare the build first, then"
                                    + " compare again.");
                dependencies.put(
                        "scope.description",
                        "Known prepared definition inventory, not all game tables; full schemas"
                                + " unassessed");
                dependencies.put(
                        "tables.preparation.identity", digest(cached.preparationIdentity()));
                var descriptor =
                        descriptor(
                                build,
                                locale,
                                TableInventoryInput.NAMESPACE,
                                TableInventoryInput.SCOPE,
                                dependencies);
                input =
                        new TableInventoryInput(
                                descriptor,
                                new TableInventoryInput.KnownInventory(
                                        cached.result(),
                                        cached.preparationIdentity(),
                                        cached.definitionIdentity()),
                                Map.of());
            }
            token.check();
            return new ComparisonCoordinator.Opened(input, work::close);
        } catch (Throwable failure) {
            if (input != null)
                try {
                    input.closeAsync().join();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            try {
                work.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new java.util.concurrent.CompletionException(failure);
        }
    }

    private ComparisonInput rawInput(
            NativePreparationWork work,
            String locale,
            long tableId,
            Map<String, String> dependencies,
            TempBudget budget,
            Cancellation token) {
        var tables = work.openLocalTablesForComparison(token);
        com.vibeminer.formats.tables.CapturedRows held = null;
        PreparedTableRowsSource source = null;
        try {
            var capture =
                    tables.capturePreparedLocal(
                                    com.vibeminer.formats.tables.TableTypes.Ref.id(tableId), token)
                            .join();
            if (capture.state()
                    != com.vibeminer.formats.tables.CapturedRowsTypes.CaptureState.CAPTURED) {
                throw new IllegalStateException(
                        "Prepared raw table is unavailable: "
                                + capture.state()
                                + " ("
                                + capture.code()
                                + "). Open/prepare this build first; comparison does not rebuild"
                                + " it.");
            }
            held = capture.rows();
            var receipt = held.receipt();
            if (!receipt.build().equals(work.filesHandle().build())
                    || !receipt.locale().equals(locale)
                    || receipt.table().fileDataId() != tableId) {
                throw new IllegalStateException(
                        "Prepared table receipt does not match the requested build, locale and"
                                + " table.");
            }
            source = new PreparedTableRowsSource(held, dependencies, budget, token);
            held = null;
            return new PreparedRawComparisonInput(source, tables, temporaryParent, budget);
        } catch (Throwable failure) {
            try {
                if (source != null) source.closeAsync().join();
                else if (held != null) held.closeAsync().join();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                tables.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new java.util.concurrent.CompletionException(failure);
        }
    }

    private static ComparisonTypes.Descriptor itemsDescriptor(
            BuildInfo build, String locale, ItemsSnapshot snapshot, ItemsSnapshot.Side side) {
        var dependencies = new LinkedHashMap<>(snapshot.dependencies(side));
        dependencies.put("items.scope.reasons", String.join("; ", snapshot.reasons()));
        return descriptor(build, locale, "items.ItemID.v1", snapshot.fieldScope(), dependencies);
    }

    private static ComparisonTypes.Descriptor descriptor(
            BuildInfo build,
            String locale,
            String namespace,
            String scope,
            Map<String, String> dependencies) {
        var ordered = new java.util.TreeMap<>(dependencies);
        String identity = digest(namespace + "\n" + scope + "\n" + locale + "\n" + ordered);
        return new ComparisonTypes.Descriptor(
                identity, build.product(), locale, namespace, scope, dependencies);
    }

    static BuildInfo.RegionEvidence evidence(
            BuildInfo build, String locale, java.util.List<PreparationHistory.Receipt> receipts) {
        // A historical receipt selects a source context only; cache validity is checked
        // independently.
        for (var receipt : receipts) {
            if (java.util.Objects.equals(receipt.identity(), build.identity())
                    && java.util.Objects.equals(receipt.product(), build.product())
                    && java.util.Objects.equals(receipt.config(), build.config())
                    && java.util.Objects.equals(receipt.locale(), locale)
                    && receipt.region() != null
                    && receipt.cdnConfig() != null
                    && receipt.cdnConfig().matches("[0-9a-fA-F]{32}")) {
                return new BuildInfo.RegionEvidence(
                        receipt.region(), receipt.cdnConfig(), false, null, null);
            }
        }
        return build.observations().stream()
                .filter(
                        value ->
                                value.cdnConfig() != null
                                        && value.cdnConfig().matches("[0-9a-fA-F]{32}"))
                .sorted(
                        java.util.Comparator.comparing(BuildInfo.RegionEvidence::current)
                                .reversed()
                                .thenComparing(value -> !value.region().equals("us"))
                                .thenComparing(BuildInfo.RegionEvidence::region))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "This build has no observed CDN configuration. Choose an"
                                                + " observed, locally prepared build."));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
