package com.vibeminer.builds;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;
import com.vibeminer.builds.BuildTypes.FileResolutionState;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.RangePlanner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small immutable ledger-backed synthetic build for accounting/job consumer tests. */
public final class TrackingBuild implements Build, LedgerSource {
    private final BuildRef build;
    private final ObjectLedger ledger;
    private final CdnContext cdn;
    private final Map<CdnKey, Located> locations = new HashMap<>();

    public TrackingBuild(int identity, long[] sizes, boolean routed) {
        build =
                new BuildRef(
                        "wow",
                        String.format(java.util.Locale.ROOT, "%032x", identity),
                        "b".repeat(32),
                        "us");
        CdnKey[] keys = new CdnKey[sizes.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = key(i + 1);
        }
        ledger = new ObjectLedger(keys, sizes);
        cdn =
                routed
                        ? new CdnContext(
                                new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"),
                                List.of(key(99), key(100)))
                        : null;
    }

    public static CdnKey key(int id) {
        return CdnKey.parse(String.format(java.util.Locale.ROOT, "%032x", id));
    }

    public void locate(int row, int archive, long offset) {
        locations.put(
                ledger.key(row),
                new Located(
                        new RangePlanner.Location(key(99 + archive), offset, ledger.size(row)),
                        archive));
    }

    @Override
    public BuildRef build() {
        return build;
    }

    @Override
    public BuildMetadata metadata() {
        return new BuildMetadata("synthetic", key(1), key(2), key(3), 2, 0, List.of("enUS"));
    }

    @Override
    public CdnContext cdn() {
        return cdn;
    }

    @Override
    public FileResolution resolve(long id) {
        return new FileResolution(FileResolutionState.UNKNOWN_FILE, null, List.of());
    }

    @Override
    public FileResolution resolve(String path) {
        return resolve(0);
    }

    @Override
    public Iterable<Long> fileDataIds(String locale, long from) {
        return List.of();
    }

    @Override
    public ObjectLedger ledger() {
        return ledger;
    }

    @Override
    public Located locate(CdnKey key) {
        return locations.get(key);
    }
}
