package com.vibeminer.builds;

import com.vibeminer.network.CdnKey;
import com.vibeminer.network.RangePlanner;

interface LedgerSource {
    record Located(RangePlanner.Location location, int archiveRank) {}

    ObjectLedger ledger();

    default Located locate(CdnKey key) {
        return null;
    }
}
