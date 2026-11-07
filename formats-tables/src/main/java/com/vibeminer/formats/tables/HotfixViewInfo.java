package com.vibeminer.formats.tables;

/** Ordinary effective-view provenance; native counts/content keys remain base DB2 facts. */
public record HotfixViewInfo(
        String inputFingerprint,
        String identity,
        String policy,
        long baseReadableRows,
        long effectiveRows,
        int added,
        int replaced,
        int deleted,
        int protectedRows,
        int unknownRows,
        int ignored,
        RowSource row,
        String unavailable) {
    /**
     * Fixed reason when this table's overlay could not be built within its resource bounds. The
     * table then reads base rows only; counts describe the base table and no hotfix applies.
     */
    public static final String TABLE_BUDGET = "TABLE_BUDGET",
            RETAINED_BUDGET = "RETAINED_BUDGET",
            OPERATION_TABLES = "OPERATION_TABLES",
            BASE_UNAVAILABLE = "BASE_UNAVAILABLE";

    /**
     * Fixed row reason when a cache row needs a raw-physical column whose base check stayed
     * inconclusive after {@code HotfixBuildTables.FIT_SCANS_MAX} scans and was given up on: the row
     * stays unresolved, with no stale base substitution, until the base changes.
     */
    public static final String RAW_SCAN_GAVE_UP = "RAW_SCAN_GAVE_UP";

    public HotfixViewInfo(
            String inputFingerprint,
            String identity,
            String policy,
            long baseReadableRows,
            long effectiveRows,
            int added,
            int replaced,
            int deleted,
            int protectedRows,
            int unknownRows,
            int ignored,
            RowSource row) {
        this(
                inputFingerprint,
                identity,
                policy,
                baseReadableRows,
                effectiveRows,
                added,
                replaced,
                deleted,
                protectedRows,
                unknownRows,
                ignored,
                row,
                null);
    }

    public enum Origin {
        BASE,
        HOTFIX,
        DELETED,
        NOT_PUBLIC,
        UNRESOLVED,
        UNKNOWN
    }

    public record RowSource(
            Origin origin,
            Integer ordinal,
            Integer region,
            Integer push,
            Integer uniqueId,
            String state,
            String reason) {
        public RowSource(
                Origin origin,
                Integer ordinal,
                Integer region,
                Integer push,
                Integer uniqueId,
                String state) {
            this(origin, ordinal, region, push, uniqueId, state, null);
        }
    }
}
