package com.vimi.gui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Future adapter boundary. Implementations must never block the JavaFX thread. */
public interface BuildService {
    CompletableFuture<List<BuildInfo>> listBuilds();

    CompletableFuture<BuildInfo> openBuild(BuildInfo build);
}
