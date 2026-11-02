package com.vimi.gui;

import com.vibeminer.accounting.LibraryUsage;
import com.vibeminer.accounting.UsageIndex;
import com.vibeminer.files.names.NameCoverage;

/** Independent reporting snapshot; never grants build or table readiness. */
public record BuildAccounting(
        UsageIndex.Figures usage,
        NameCoverage.Result names,
        String nameRevision,
        String problem,
        boolean measuring,
        LibraryUsage.Totals library) {}
