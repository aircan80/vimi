package com.vimi.gui;

import com.vibeminer.builds.BuildTypes;
import com.vibeminer.files.FileLocales;
import com.vibeminer.formats.internal.Json;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historical assessments for the Builds list; never grants readiness to a new workspace. */
public final class PreparationHistory {
    private static final String FORMAT = "vimi-preparation-history-v1";
    private static final GeneratedKey KEY =
            GeneratedKey.hash(FORMAT.getBytes(StandardCharsets.UTF_8));
    private static final int MAX_ENTRIES = 1024;
    private final GeneratedStore store;
    private volatile Map<String, Receipt> receipts = Map.of();

    public record Receipt(
            String identity,
            String product,
            String config,
            String cdnConfig,
            String region,
            String locale,
            String ready) {
        BuildPreparation.Ready milestone() {
            return BuildPreparation.Ready.valueOf(ready);
        }
    }

    PreparationHistory(GeneratedStore store) {
        this.store = store;
    }

    synchronized void load() {
        try {
            var read = store.read(KEY).join();
            if (read.state() != GeneratedTypes.ReadState.PRESENT) return;
            var root = Json.object(Json.parse(read.bytes(), 1 << 20));
            if (!FORMAT.equals(root.get("Format"))) return;
            var entries = Json.array(root.get("Builds"));
            if (entries.size() > MAX_ENTRIES) return;
            var loaded = new LinkedHashMap<String, Receipt>();
            for (var entry : entries) {
                var receipt = Json.convert(entry, Receipt.class);
                FileLocales.bitOf(receipt.locale());
                if (receipt.identity() == null
                        || receipt.product() == null
                        || receipt.config() == null
                        || receipt.ready() == null
                        || receipt.milestone() == BuildPreparation.Ready.NOT_PREPARED) return;
                loaded.put(receipt.identity(), receipt);
            }
            receipts = java.util.Collections.unmodifiableMap(loaded);
        } catch (RuntimeException invalid) {
            warn(invalid);
        }
    }

    String summary(BuildInfo build) {
        var receipt = receipts.get(build.identity());
        if (receipt == null
                || !receipt.product().equals(build.product())
                || !receipt.config().equals(build.config())) return null;
        return "Previously: "
                + PreparationStatus.readiness(receipt.milestone())
                + " · "
                + receipt.locale();
    }

    synchronized void record(
            BuildInfo build,
            BuildTypes.BuildRef source,
            String locale,
            BuildPreparation.Ready ready) {
        if (ready == BuildPreparation.Ready.NOT_PREPARED) return;
        var previous = receipts.get(build.identity());
        var receipt =
                new Receipt(
                        build.identity(),
                        build.product(),
                        build.config(),
                        source.cdnConfig(),
                        source.region(),
                        locale,
                        ready.name());
        if (previous != null
                && previous.product().equals(receipt.product())
                && previous.config().equals(receipt.config())
                && java.util.Objects.equals(previous.cdnConfig(), receipt.cdnConfig())
                && java.util.Objects.equals(previous.region(), receipt.region())
                && previous.locale().equals(locale)
                && previous.milestone().ordinal() >= ready.ordinal()) return;
        var next = new LinkedHashMap<>(receipts);
        next.remove(build.identity());
        next.put(build.identity(), receipt);
        while (next.size() > MAX_ENTRIES) next.remove(next.keySet().iterator().next());
        try {
            byte[] bytes =
                    Json.bytes(Map.of("Format", FORMAT, "Builds", List.copyOf(next.values())));
            // Replace only this small application-history pointer; a crash gap loses history,
            // never the prepared file/table graphs or a workspace readiness check.
            store.delete(List.of(KEY)).join();
            store.put(List.of(new GeneratedTypes.Put(KEY, bytes))).join();
            receipts = java.util.Collections.unmodifiableMap(next);
        } catch (RuntimeException failure) {
            warn(failure);
        }
    }

    private static void warn(RuntimeException failure) {
        System.getLogger(PreparationHistory.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Could not restore/save historical preparation status",
                        failure);
    }
}
