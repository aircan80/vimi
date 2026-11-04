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
        RowSource row) {
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
