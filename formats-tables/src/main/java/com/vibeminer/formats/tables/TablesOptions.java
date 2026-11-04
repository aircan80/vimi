package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.generated.KeyStatus;
import com.vibeminer.formats.generated.TactKeyStatus;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

public record TablesOptions(
        BiFunction<BuildRef, Cancellation, CompletableFuture<String>> buildVersion,
        Db2Types.Limits limits,
        Function<BuildRef, KeyStatus> keys,
        TableWorkScheduler scheduler,
        int maxScanRows,
        int maxReferenceSources,
        long maxReferenceScanRows,
        boolean localDefinitionsOnly,
        HotfixBinding hotfix) {
    public TablesOptions(
            BiFunction<BuildRef, Cancellation, CompletableFuture<String>> buildVersion,
            Db2Types.Limits limits,
            Function<BuildRef, KeyStatus> keys,
            TableWorkScheduler scheduler,
            int maxScanRows,
            int maxReferenceSources,
            long maxReferenceScanRows,
            boolean localDefinitionsOnly) {
        this(
                buildVersion,
                limits,
                keys,
                scheduler,
                maxScanRows,
                maxReferenceSources,
                maxReferenceScanRows,
                localDefinitionsOnly,
                null);
    }

    public TablesOptions withHotfix(HotfixBinding binding) {
        return new TablesOptions(
                buildVersion,
                limits,
                keys,
                scheduler,
                maxScanRows,
                maxReferenceSources,
                maxReferenceScanRows,
                localDefinitionsOnly,
                java.util.Objects.requireNonNull(binding));
    }

    public TablesOptions(
            BiFunction<BuildRef, Cancellation, CompletableFuture<String>> buildVersion,
            Db2Types.Limits limits,
            Function<BuildRef, KeyStatus> keys,
            TableWorkScheduler scheduler,
            int maxScanRows,
            int maxReferenceSources,
            long maxReferenceScanRows) {
        this(
                buildVersion,
                limits,
                keys,
                scheduler,
                maxScanRows,
                maxReferenceSources,
                maxReferenceScanRows,
                false);
    }

    /** Keeps prepared reads and bootstrap independent of background community refresh. */
    public TablesOptions withLocalDefinitionsOnly() {
        return new TablesOptions(
                buildVersion,
                limits,
                keys,
                scheduler,
                maxScanRows,
                maxReferenceSources,
                maxReferenceScanRows,
                true,
                hotfix);
    }

    public TablesOptions(BiFunction<BuildRef, Cancellation, CompletableFuture<String>> version) {
        this(
                version,
                new Db2Types.Limits(),
                build -> TactKeyStatus.NONE,
                new TableWorkScheduler(),
                200000,
                256,
                1000000);
    }

    public TablesOptions {
        java.util.Objects.requireNonNull(buildVersion);
        java.util.Objects.requireNonNull(limits);
        java.util.Objects.requireNonNull(keys);
        if (maxScanRows < 1 || maxReferenceSources < 0 || maxReferenceScanRows < 0) {
            throw new IllegalArgumentException("Invalid query bounds");
        }
        if (scheduler == null) {
            scheduler = new TableWorkScheduler();
        }
    }
}
