package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface GeneratedTableStore {
    CompletableFuture<GeneratedTable> open(
            GeneratedTableSource source, KeyStatus keys, Cancellation token);

    CompletableFuture<GeneratedTable> store(
            GeneratedTableSource source,
            KeyStatus keys,
            GeneratedTableContent content,
            Cancellation token);

    CompletableFuture<GeneratedTable> getOrBuild(
            GeneratedTableSource source,
            KeyStatus keys,
            Function<Cancellation, CompletableFuture<GeneratedTableContent>> build,
            Cancellation token);

    CompletableFuture<String> layoutOf(String contentKey, Cancellation token);

    String identityOf(
            GeneratedTableSource source, KeyStatus keys, List<String> used, boolean encrypted);
}
