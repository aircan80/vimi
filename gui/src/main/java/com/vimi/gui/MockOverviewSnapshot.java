package com.vimi.gui;

/** Illustrative scope totals, separate from the small curated research-browser samples. */
public record MockOverviewSnapshot(
        Long fileIds, Long namedFileIds, Long tableDefinitions, Long readableTables,
        Long itemRecords, Long creatureDeclarations) {
    public static MockOverviewSnapshot forBuild(BuildInfo build) {
        if (build.product().equals("wow") && build.totalGiB() != null) {
            return new MockOverviewSnapshot(1_284_630L, 1_102_418L, 812L, 608L, 89_412L, 42_781L);
        }
        return new MockOverviewSnapshot(null, null, null, null, null, null);
    }
}
