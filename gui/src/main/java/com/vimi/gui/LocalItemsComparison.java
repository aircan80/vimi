package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.BudgetedTempFile;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.StreamingItemJoin;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.inputs.PreparedItemRowsSource;
import com.vibeminer.concepts.comparison.items.ItemsSnapshot;
import com.vibeminer.concepts.comparison.items.ItemsStageCodec;
import com.vibeminer.concepts.items.PreparedItemTypes;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceKind;
import com.vibeminer.concepts.items.PreparedItemTypes.SourceState;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Explicit local-only Item then ItemSparse capture; no ordinary item service or per-ID lookup. */
final class LocalItemsComparison {
    private LocalItemsComparison() {}

    static BudgetedTempFile capture(
            NativePreparationWork work,
            String locale,
            Map<String, String> dependencies,
            ItemsSnapshot.Side side,
            ItemsSnapshot snapshot,
            Path parent,
            TempBudget budget,
            Cancellation token)
            throws Exception {
        var directory = Files.createTempDirectory(parent, "comparison-items-");
        SortedComparisonInput item = null, sparse = null;
        boolean transferred = false;
        BudgetedTempFile output = null;
        try {
            try (var tables = work.openLocalTablesForComparison(token)) {
                item =
                        dependency(
                                work,
                                tables,
                                locale,
                                dependencies,
                                side,
                                snapshot,
                                parent,
                                budget,
                                SourceKind.ITEM,
                                token);
                sparse =
                        dependency(
                                work,
                                tables,
                                locale,
                                dependencies,
                                side,
                                snapshot,
                                parent,
                                budget,
                                SourceKind.ITEM_SPARSE,
                                token);
                var codec = new ItemsStageCodec(snapshot);
                // The join consumes and closes both dependency files, on success and failure.
                transferred = true;
                output =
                        StreamingItemJoin.join(
                                item,
                                sparse,
                                directory,
                                budget,
                                (left, right) -> codec.candidate(side, left, right),
                                token);
            }
            return output;
        } catch (Exception | Error failure) {
            if (!transferred) {
                try {
                    if (item != null) item.closeAsync().join();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    if (sparse != null) sparse.closeAsync().join();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                if (output != null) output.close();
                Files.deleteIfExists(directory);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static SortedComparisonInput dependency(
            NativePreparationWork work,
            BuildTables tables,
            String locale,
            Map<String, String> dependencies,
            ItemsSnapshot.Side side,
            ItemsSnapshot snapshot,
            Path parent,
            TempBudget budget,
            SourceKind kind,
            Cancellation token)
            throws Exception {
        CapturedRows held = null;
        PreparedItemRowsSource source = null;
        try {
            var result =
                    tables.capturePreparedLocal(TableTypes.Ref.id(kind.fileDataId()), token).join();
            token.check();
            if (result.state() != CapturedRowsTypes.CaptureState.CAPTURED) {
                if (result.state() == CapturedRowsTypes.CaptureState.CANCELED) {
                    throw new java.util.concurrent.CancellationException();
                }
                var stamp =
                        new PreparedItemTypes.SourceStamp(
                                kind,
                                work.filesHandle().build(),
                                locale,
                                kind.fileDataId(),
                                kind.tableName(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);
                var state =
                        result.state() == CapturedRowsTypes.CaptureState.CORRUPT
                                ? SourceState.INVALID
                                : SourceState.UNAVAILABLE;
                var omission =
                        new PreparedItemTypes.Omission(
                                null,
                                null,
                                state == SourceState.INVALID
                                        ? PreparedItemTypes.CellState.INVALID
                                        : PreparedItemTypes.CellState.UNKNOWN,
                                "CAPTURE_" + result.state().name());
                var absentRef =
                        snapshot.register(
                                side,
                                stamp,
                                state,
                                new PreparedItemTypes.Compilation(
                                        PreparedItemTypes.IdentityAdmission.UNKNOWN,
                                        PreparedItemTypes.FieldAdmission.UNSUPPORTED,
                                        null,
                                        List.of(omission)));
                snapshot.registerSourceMetadata(
                        absentRef,
                        Map.of(
                                "captureState",
                                result.state().name(),
                                "captureCode",
                                java.util.Objects.toString(result.code(), "unknown")));
                return empty(stamp, result.state().name(), parent, budget, token);
            }
            held = result.rows();
            var receipt = held.receipt();
            if (!receipt.build().equals(work.filesHandle().build())
                    || !receipt.locale().equals(locale)
                    || receipt.table().fileDataId() != kind.fileDataId())
                throw new IllegalStateException(
                        "Prepared Items receipt differs from requested endpoint");
            try {
                source =
                        new PreparedItemRowsSource(
                                held, side, dependencies, snapshot, budget, token);
            } catch (PreparedItemRowsSource.AdmissionFailure unsupported) {
                held.closeAsync().join();
                held = null;
                var omittedState =
                        switch (unsupported.compilation().identity()) {
                            case INVALID -> SourceState.INVALID;
                            case LIMITED -> SourceState.LIMITED;
                            default -> SourceState.UNSUPPORTED;
                        };
                snapshot.register(
                        side, unsupported.stamp(), omittedState, unsupported.compilation());
                return empty(
                        unsupported.stamp(), "SOURCE_IDENTITY_UNSUPPORTED", parent, budget, token);
            }
            var sourceDependencies = source.descriptor().dependencies();
            var sourceMetadata = new java.util.LinkedHashMap<String, String>();
            for (String key :
                    List.of(
                            "items.readable.rows",
                            "items.excluded.sections",
                            "items.excluded.physical",
                            "items.excluded.copies",
                            "items.encoding.scope",
                            "items.unknown-key-status-count",
                            "items.issueEvidence.state",
                            "items.issueEvidence.policy",
                            "items.issueEvidence.producer",
                            "items.issueEvidence.digest")) {
                sourceMetadata.put(key, sourceDependencies.get(key));
            }
            snapshot.registerSourceMetadata(source.sourceRef(), sourceMetadata);
            held = null;
            var owned = source;
            source = null;
            return SortedComparisonInput.capture(owned, parent, budget, token).join();
        } finally {
            if (source != null) source.closeAsync().join();
            else if (held != null) held.closeAsync().join();
        }
    }

    private static SortedComparisonInput empty(
            PreparedItemTypes.SourceStamp stamp,
            String reason,
            Path parent,
            TempBudget budget,
            Cancellation token) {
        String scope = "items-unavailable:" + stamp.kind().name();
        var descriptor =
                new ComparisonTypes.Descriptor(
                        "unavailable:" + stamp.kind().name(),
                        stamp.build().product(),
                        stamp.locale(),
                        "items.source:" + stamp.kind().name(),
                        scope,
                        Map.of("items.capture", reason));
        return SortedComparisonInput.capture(
                        new SortedComparisonInput.Source() {
                            public ComparisonTypes.Descriptor descriptor() {
                                return descriptor;
                            }

                            public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                                    String cursor,
                                    int limit,
                                    long maxRetained,
                                    Cancellation cancellation) {
                                cancellation.check();
                                return CompletableFuture.completedFuture(
                                        new SortedComparisonInput.UnsortedPage(
                                                descriptor.identity(),
                                                List.of(),
                                                null,
                                                new ComparisonTypes.Coverage(
                                                        ComparisonTypes.Completeness.UNKNOWN,
                                                        scope,
                                                        List.of(reason))));
                            }

                            public CompletableFuture<Void> closeAsync() {
                                return CompletableFuture.completedFuture(null);
                            }
                        },
                        parent,
                        budget,
                        token)
                .join();
    }
}
