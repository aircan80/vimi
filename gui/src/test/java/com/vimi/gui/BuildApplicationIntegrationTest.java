package com.vimi.gui;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Native composition with authored persisted observations, no network requests. */
public final class BuildApplicationIntegrationTest {
    private BuildApplicationIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("vimi-gui-builds-");
        try {
            var store = ObjectStore.open(directory.resolve("objects")).join();
            try {
                String versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                        + "us|11111111111111111111111111111111|22222222222222222222222222222222|1.2.3.45678\n";
                store.appendHistory(new History.Observation(1, "wow_future_test", "us",
                        Instant.parse("2026-01-01T00:00:00Z"), "https://example.invalid/versions",
                        versions.getBytes(StandardCharsets.UTF_8)), Cancellation.NONE).join();
            } finally { store.closeAsync().join(); }
            String identity;
            var service = BuildApplication.open(directory).join();
            try {
                require(service.preparation() != null, "Native composition wires the full preparation service");
                var builds = service.listBuilds().get(20, java.util.concurrent.TimeUnit.SECONDS);
                require(builds.size() == 1, "Offline discovery must find persisted future products");
                var build = builds.getFirst();
                require(build.product().equals("wow_future_test"), "Preserve product identity");
                require(build.current() && !build.prepared() && build.totalGiB() == null,
                        "Observation must not establish preparation or download scope");
                require(build.observations().size() == 1 && build.firstSeen() != null,
                        "Carry persisted regional evidence");
                require(service.problems().isEmpty(), "Authored offline history has no problems");
                require(service.loadSnapshot(build) != null
                                && service.loadSnapshot(build).entry().status() == com.vibeminer.builds.BuildRegistry.Status.DISCOVERED,
                        "Build list status comes from persisted registry table");
                identity = build.identity();
            } finally { service.closeAsync().get(20, java.util.concurrent.TimeUnit.SECONDS); }
            var reopened = BuildApplication.open(directory).join();
            try {
                var restored = reopened.listBuilds().join().getFirst();
                require(reopened.loadSnapshot(restored).entry().status() == com.vibeminer.builds.BuildRegistry.Status.DISCOVERED,
                        "Discovery remains distinct from successful metadata loading across restart");
                require(restored.identity().equals(identity),
                        "Reopen must retain exact catalog identity without network");
            } finally { reopened.closeAsync().join(); }
            System.out.println("Passed native GUI catalog startup, persisted discovery, close and reopen");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
