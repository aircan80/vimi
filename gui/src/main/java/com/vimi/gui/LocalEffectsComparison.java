package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.BudgetedTempFile;
import com.vibeminer.concepts.comparison.EffectsStaging;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.comparison.inputs.PreparedEffectRowsSource;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.CellState;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Compilation;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.FieldAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IdentityAdmission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.IssueEvidence;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.Omission;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.RelationMode;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceKind;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceStamp;
import com.vibeminer.concepts.effects.PreparedItemEffectTypes.SourceState;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Prepared-local-only effects capture. No ordinary schemas/lookups, acquisition or relation index. */
final class LocalEffectsComparison {
    private LocalEffectsComparison() {}

    static BudgetedTempFile capture(NativePreparationWork work, String locale,
            EffectsSnapshot.Side side, EffectsSnapshot snapshot, Path parent,
            TempBudget budget, Cancellation token) throws Exception {
        try (var tables = work.openLocalTablesForComparison(token)) {
            if (!tables.locale().equals(locale)) { throw new IllegalArgumentException("Effects table locale differs"); }
            return capture(tables, side, snapshot, parent, budget, token);
        }
    }

    /** Borrowed tables stay caller-owned; every captured handle/private input drains here. */
    static BudgetedTempFile capture(BuildTables tables, EffectsSnapshot.Side side,
            EffectsSnapshot snapshot, Path parent, TempBudget budget, Cancellation token) throws Exception {
        if (snapshot.budget() != budget || !snapshot.endpoint(side).build().equals(tables.build())
                || !snapshot.endpoint(side).locale().equals(tables.locale())) {
            throw new IllegalArgumentException("Effects tables differ from requested endpoint");
        }
        var directory = Files.createTempDirectory(parent, "comparison-effects-");
        CapturedRows held = null;
        PreparedEffectRowsSource source = null;
        SortedComparisonInput target = null, own = null, routed = null, sorted = null;
        BudgetedTempFile routeFile = null, candidates = null, result = null;
        boolean delivered = false;
        Throwable problem = null;
        try {
            // Inspect ItemEffect exactly once. Failed direct admission leaves the SAME handle ours.
            var effectCapture = tables.capturePreparedLocal(TableTypes.Ref.id(969941), token).join();
            if (effectCapture.state() == CapturedRowsTypes.CaptureState.CAPTURED) { held = effectCapture.rows(); }
            token.check();
            if (effectCapture.state() == CapturedRowsTypes.CaptureState.CANCELED) { throw new CancellationException(); }
            if (effectCapture.state() == CapturedRowsTypes.CaptureState.CAPTURED) {
                held = effectCapture.rows();
                try {
                    source = new PreparedEffectRowsSource(held, side, SourceKind.DIRECT_EFFECT, snapshot, budget, token);
                } catch (PreparedEffectRowsSource.AdmissionFailure unsupportedDirect) {
                    // Positive retail qualification is independent; do not choose a mode by default.
                }
                if (source != null) {
                    held = null;
                    var ownedSource = source;
                    source = null;
                    own = SortedComparisonInput.capture(ownedSource, directory, budget, token).join();
                    snapshot.selectMode(side, RelationMode.DIRECT);
                    var owned = own;
                    own = null;
                    candidates = EffectsStaging.directCandidates(owned, side, snapshot, directory, budget, token);
                } else {
                    try {
                        source = new PreparedEffectRowsSource(held, side, SourceKind.RETAIL_EFFECT, snapshot, budget, token);
                    } catch (PreparedEffectRowsSource.AdmissionFailure unsupportedTarget) {
                        snapshot.register(side, unsupportedTarget.stamp(), SourceState.UNSUPPORTED,
                                unsupportedTarget.compilation(), Map.of("outcome", "UNSUPPORTED_EFFECT_PROFILE"));
                    }
                    if (source != null) {
                        held = null;
                        var ownedSource = source;
                        source = null;
                        target = SortedComparisonInput.capture(ownedSource, directory, budget, token).join();
                    } else {
                        held.closeAsync().join();
                        held = null;
                    }
                }
            } else {
                unavailable(tables, side, SourceKind.RETAIL_EFFECT, effectCapture, snapshot);
            }
            if (candidates == null) {
                var bridgeCapture = tables.capturePreparedLocal(TableTypes.Ref.id(3177687), token).join();
                if (bridgeCapture.state() == CapturedRowsTypes.CaptureState.CAPTURED) { held = bridgeCapture.rows(); }
                token.check();
                if (bridgeCapture.state() == CapturedRowsTypes.CaptureState.CANCELED) { throw new CancellationException(); }
                if (bridgeCapture.state() == CapturedRowsTypes.CaptureState.CAPTURED) {
                    held = bridgeCapture.rows();
                    try {
                        source = new PreparedEffectRowsSource(held, side, SourceKind.BRIDGE, snapshot, budget, token);
                    } catch (PreparedEffectRowsSource.AdmissionFailure unsupportedBridge) {
                        snapshot.register(side, unsupportedBridge.stamp(), SourceState.UNSUPPORTED,
                                unsupportedBridge.compilation(), Map.of("outcome", "UNSUPPORTED_BRIDGE_PROFILE"));
                    }
                    if (source != null) {
                        held = null;
                        var ownedSource = source;
                        source = null;
                        own = SortedComparisonInput.capture(ownedSource, directory, budget, token).join();
                        snapshot.selectMode(side, RelationMode.BRIDGE);
                        var owned = own;
                        own = null;
                        routeFile = EffectsStaging.routeBridge(owned, side, snapshot, directory, budget, token);
                        var routedFile = routeFile;
                        routeFile = null;
                        routed = EffectsStaging.sortSpool(routedFile, side, snapshot, directory, budget, token,
                                EffectsStaging.StageOrder.ROUTED);
                        var links = routed;
                        var targets = target;
                        routed = null;
                        target = null;
                        candidates = EffectsStaging.joinBridge(links, targets, side, snapshot, directory, budget, token);
                    } else {
                        held.closeAsync().join();
                        held = null;
                    }
                } else { unavailable(tables, side, SourceKind.BRIDGE, bridgeCapture, snapshot); }
            }
            if (candidates == null) {
                // The paired capability gate will reject this unqualified mode before core start.
                result = BudgetedTempFile.create(directory, "unassessed-", budget, "effects-unassessed", token);
                result.seal();
            } else {
                var unsorted = candidates;
                candidates = null;
                sorted = EffectsStaging.sortSpool(unsorted, side, snapshot, directory, budget, token,
                        EffectsStaging.StageOrder.LINK);
                var owned = sorted;
                sorted = null;
                result = EffectsStaging.sealCandidates(owned, snapshot, directory, budget, token);
            }
            token.check();
            delivered = true;
            return result;
        } catch (Exception | Error failure) {
            problem = failure;
            throw failure;
        } finally {
            Throwable cleanup = null;
            for (var input : new SortedComparisonInput[] {target, own, routed, sorted}) {
                if (input == null) { continue; }
                try { input.closeAsync().join(); } catch (Throwable failure) { cleanup = add(cleanup, failure); }
            }
            try {
                if (source != null) { source.closeAsync().join(); }
                else if (held != null) { held.closeAsync().join(); }
            } catch (Throwable failure) { cleanup = add(cleanup, failure); }
            for (var file : new BudgetedTempFile[] {routeFile, candidates, delivered ? null : result}) {
                if (file == null) { continue; }
                try { file.close(); } catch (Throwable failure) { cleanup = add(cleanup, failure); }
            }
            if (!delivered || cleanup != null) {
                if (delivered && result != null) {
                    try { result.close(); } catch (Throwable failure) { cleanup = add(cleanup, failure); }
                }
                try { Files.deleteIfExists(directory); } catch (Throwable failure) { cleanup = add(cleanup, failure); }
            }
            if (cleanup != null) {
                if (problem != null) { problem.addSuppressed(cleanup); }
                else { throw new java.io.IOException("Effects capture cleanup failed", cleanup); }
            }
        }
    }

    private static Throwable add(Throwable previous, Throwable next) {
        if (previous == null) { return next; }
        previous.addSuppressed(next);
        return previous;
    }
    private static void unavailable(BuildTables tables, EffectsSnapshot.Side side, SourceKind kind,
            CapturedRowsTypes.Capture capture, EffectsSnapshot snapshot) {
        var p = kind.profile();
        var stamp = new SourceStamp(kind, tables.build(), tables.locale(), p.fileDataId(), p.table(),
                null, null, null, null, null, null, null, null, null,
                new IssueEvidence("UNKNOWN", null, null, null));
        var state = capture.state() == CapturedRowsTypes.CaptureState.CORRUPT ? SourceState.INVALID : SourceState.UNAVAILABLE;
        snapshot.register(side, stamp, state,
                new Compilation(IdentityAdmission.UNKNOWN, FieldAdmission.UNSUPPORTED, null,
                        List.of(new Omission(null, state == SourceState.INVALID ? CellState.INVALID : CellState.UNKNOWN,
                                "CAPTURE_" + capture.state().name()))),
                Map.of("captureState", capture.state().name(), "captureCode", java.util.Objects.toString(capture.code(), "unknown")));
    }
}
