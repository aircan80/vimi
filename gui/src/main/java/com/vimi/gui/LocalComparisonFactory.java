package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.inputs.FilesInventoryInput;
import com.vibeminer.concepts.comparison.inputs.PreparedTableRowsSource;
import com.vibeminer.concepts.comparison.inputs.TableInventoryInput;
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
        var budget = request.tableId() == null ? null : new TempBudget();
        return new ComparisonCoordinator.EndpointOpener() {
            @Override
            public ComparisonCoordinator.Opened open(
                    BuildInfo build, String locale, String area, Cancellation cancellation) {
                return LocalComparisonFactory.this.open(
                        build,
                        locale,
                        area,
                        request.tableId(),
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
                    Cancellation cancellation) {
                if (request.tableId() != null) {
                    ((PreparedRawComparisonInput) before.input()).prepare(cancellation);
                    ((PreparedRawComparisonInput) after.input()).prepare(cancellation);
                }
            }

            @Override
            public TempBudget budget() {
                return budget;
            }

            @Override
            public void close() {
                if (budget != null) budget.close();
            }
        };
    }

    private ComparisonCoordinator.Opened open(
            BuildInfo build,
            String locale,
            String area,
            Long tableId,
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
            if (area.equals("Files")) {
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
