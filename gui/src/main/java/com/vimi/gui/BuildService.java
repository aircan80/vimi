package com.vimi.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Future adapter boundary. Implementations must never block the JavaFX thread. */
public interface BuildService {
    CompletableFuture<List<BuildInfo>> listBuilds();

    CompletableFuture<BuildInfo> openBuild(BuildInfo build);

    default CompletableFuture<List<BuildInfo>> refreshBuilds() {
        return listBuilds();
    }

    default BuildLoadSnapshot loadSnapshot(BuildInfo build) { return null; }

    default CompletableFuture<BuildLoadSnapshot> refreshLoadSnapshot(BuildInfo build) {
        return CompletableFuture.completedFuture(loadSnapshot(build));
    }

    default String historicalPreparation(BuildInfo build) { return null; }

    default BuildDownloads downloads() { return null; }

    default BuildPreparation preparation() { return null; }

    default BuildOpening opening() { return null; }

    default boolean mockMode() { return true; }

    default List<String> problems() { return List.of(); }

    default String status() { return "Mock snapshot · No network requests"; }

    default CompletableFuture<Void> closeAsync() {
        return CompletableFuture.completedFuture(null);
    }
}
