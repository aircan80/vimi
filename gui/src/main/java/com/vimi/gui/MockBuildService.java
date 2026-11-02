package com.vimi.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Authored, deterministic fixtures. No network, file storage, or backend dependencies. */
public final class MockBuildService implements BuildService {
    private final List<BuildInfo> builds = List.of(
            new BuildInfo("wow", "Retail", "12.1.5.70688", "US, EU, KR, TW", "0149337da1d526fc64bf43982c650577", BuildInfo.BuildSource.CURRENT, true, 118.4, 4.2),
            new BuildInfo("wow", "Retail", "12.1.5.70448", "US, EU", "662f83eb39ce48a78cffc781a021fe32", BuildInfo.BuildSource.SAVED, true, 116.8, 18.6),
            new BuildInfo("wowt", "PTR", "12.1.7.70668", "US, EU, KR, TW", "836f257793105d352ca88e53a713df16", BuildInfo.BuildSource.CURRENT, false, 121.6, 0),
            new BuildInfo("wowxptr", "PTR 2", "12.1.5.70340", "US, EU", "ffa04114db7c368002ca019f70c54be3", BuildInfo.BuildSource.CURRENT, false, null, 0),
            new BuildInfo("wow_beta", "Beta", "12.0.1.66220", "US, EU", "230b59a5feae2cbda770de108a8da933", BuildInfo.BuildSource.CURRENT, true, null, 0.1),
            new BuildInfo("wow_classic", "Classic Progression", "5.5.4.70631", "US, EU, KR, TW", "71ed60623bf7a726eec47774bf003ca0", BuildInfo.BuildSource.CURRENT, true, 42.6, 12.8),
            new BuildInfo("wow_classic_era", "Classic Era", "1.15.9.70576", "US, EU, KR, TW", "8e3220be8ed0e4bcbec563426d507e65", BuildInfo.BuildSource.CURRENT, false, 8.7, 0),
            new BuildInfo("wow", "Retail", "11.0.2.56421", "", "a48b507c0e1d2f639b74281ec0975da6", BuildInfo.BuildSource.COMMUNITY, false, null, 0));

    @Override
    public CompletableFuture<List<BuildInfo>> listBuilds() {
        return CompletableFuture.completedFuture(builds);
    }

    @Override
    public CompletableFuture<BuildInfo> openBuild(BuildInfo build) {
        if (build.source() == BuildInfo.BuildSource.COMMUNITY) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Community history does not establish a verified download source."));
        }
        return CompletableFuture.completedFuture(new BuildInfo(
                build.product(), build.edition(), build.version(), build.regions(), build.config(),
                build.source(), true, build.totalGiB(), build.storedGiB()));
    }
}
