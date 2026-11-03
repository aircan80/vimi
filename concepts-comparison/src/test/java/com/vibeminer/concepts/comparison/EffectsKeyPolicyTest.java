package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes.Record;
import com.vibeminer.concepts.comparison.SortedComparisonInput.KeyPolicy;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectsKeyPolicyTest {
    @TempDir Path directory;

    private static String id(long value) {
        return String.format(java.util.Locale.ROOT, "%020d", value);
    }

    private static String link(long parent, long own) {
        return "B" + id(parent) + id(own);
    }

    private static final class Source implements SortedComparisonInput.Source {
        final List<String> keys;
        final KeyPolicy policy;
        boolean bind = true, wrongNamespace, closed;
        String payload = "x";
        Runnable hook = () -> {};

        Source(List<String> keys, KeyPolicy policy) {
            this.keys = keys;
            this.policy = policy;
        }

        @Override
        public ComparisonTypes.Descriptor descriptor() {
            String namespace =
                    wrongNamespace
                            ? "wrong"
                            : policy == KeyPolicy.EFFECT_ROUTE
                                    ? "effects-private-route-v1"
                                    : policy == KeyPolicy.EFFECT_LINK
                                            ? "effects-private-link-v1"
                                            : "legacy-custom";
            return new ComparisonTypes.Descriptor(
                    "authored",
                    "wow",
                    "enUS",
                    namespace,
                    "test-scope",
                    bind && policy != KeyPolicy.UNSIGNED_ID
                            ? Map.of("comparison.keyPolicy", policy.name())
                            : Map.of());
        }

        @Override
        public CompletableFuture<SortedComparisonInput.UnsortedPage> read(
                String cursor, int maxRows, long maxBytes, Cancellation token) {
            token.check();
            hook.run();
            int from = cursor == null ? 0 : Integer.parseInt(cursor), at = from;
            var rows = new ArrayList<Record>();
            long planned = 4096;
            while (at < keys.size() && rows.size() < maxRows) {
                var row =
                        new Record(
                                keys.get(at),
                                Map.of(
                                        "value",
                                        new ComparisonTypes.Field(
                                                "authored",
                                                ComparisonTypes.ValueKind.TEXT,
                                                ComparisonTypes.ValueState.VALUE,
                                                payload,
                                                ComparisonTypes.Aspect.CONTENT,
                                                List.of())),
                                List.of());
                long bytes = SortedComparisonInput.retainedBytes(row);
                if (planned + bytes > maxBytes) {
                    break;
                }
                rows.add(row);
                planned += bytes;
                at++;
            }
            return CompletableFuture.completedFuture(
                    new SortedComparisonInput.UnsortedPage(
                            descriptor().identity(),
                            rows,
                            at == keys.size() ? null : Integer.toString(at),
                            new ComparisonTypes.Coverage(
                                    ComparisonTypes.Completeness.UNKNOWN,
                                    descriptor().scope(),
                                    List.of())));
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed = true;
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void legacyDefaultStillAcceptsUnsignedExtremesAndRejectsEffectsKeysOrContradictoryBinding()
            throws Exception {
        try (var budget = new TempBudget()) {
            var legacy = new Source(List.of(id(0xffffffffL), id(0)), KeyPolicy.UNSIGNED_ID);
            var sorted =
                    SortedComparisonInput.capture(legacy, directory, budget, Cancellation.NONE)
                            .join();
            try {
                assertEquals(
                        List.of(id(0), id(0xffffffffL)),
                        sorted.read(null, 100, Cancellation.NONE).join().rows().stream()
                                .map(Record::key)
                                .toList());
            } finally {
                sorted.closeAsync().join();
            }
            var route = new Source(List.of("1" + id(1) + link(1, 1)), KeyPolicy.EFFECT_ROUTE);
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            route, directory, budget, Cancellation.NONE)
                                    .join());
            assertTrue(route.closed);
            var disguised = new Source(List.of(link(1, 1)), KeyPolicy.EFFECT_LINK);
            disguised.bind = false;
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            disguised,
                                            directory,
                                            budget,
                                            Cancellation.NONE,
                                            KeyPolicy.EFFECT_LINK)
                                    .join());
            var badNamespace = new Source(List.of(link(1, 1)), KeyPolicy.EFFECT_LINK);
            badNamespace.wrongNamespace = true;
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            badNamespace,
                                            directory,
                                            budget,
                                            Cancellation.NONE,
                                            KeyPolicy.EFFECT_LINK)
                                    .join());
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void explicitPoliciesPinEveryComponentRangeAndKeepBiasedTokensOutOfLookupIdentity()
            throws Exception {
        var routeGood =
                List.of(
                        "0" + id(0) + link(1, 0),
                        "0" + id(2147483648L) + link(Integer.MAX_VALUE, 0xffffffffL),
                        "1" + id(Integer.MAX_VALUE) + link(Integer.MAX_VALUE, 0xffffffffL));
        try (var budget = new TempBudget()) {
            var sorted =
                    SortedComparisonInput.capture(
                                    new Source(routeGood, KeyPolicy.EFFECT_ROUTE),
                                    directory,
                                    budget,
                                    Cancellation.NONE,
                                    KeyPolicy.EFFECT_ROUTE)
                            .join();
            sorted.closeAsync().join();
            for (var policy : List.of(KeyPolicy.EFFECT_ROUTE, KeyPolicy.EFFECT_LINK)) {
                var invalid =
                        policy == KeyPolicy.EFFECT_LINK
                                ? List.of(
                                        link(0, 1),
                                        link(2147483648L, 1),
                                        link(1, 4294967296L),
                                        "X" + id(1) + id(1),
                                        "B" + "١".repeat(20) + id(1))
                                : List.of(
                                        "1" + id(0) + link(1, 1),
                                        "1" + id(2147483648L) + link(1, 1),
                                        "0" + id(2147483649L) + link(1, 1),
                                        "0" + id(1) + "D" + id(1) + id(1),
                                        "0" + id(0) + link(0, 1));
                for (var key : invalid) {
                    var source = new Source(List.of(key), policy);
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    SortedComparisonInput.capture(
                                                    source,
                                                    directory,
                                                    budget,
                                                    Cancellation.NONE,
                                                    policy)
                                            .join(),
                            key);
                    assertTrue(source.closed);
                }
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void duplicateCompositeKeysAndCorruptedSortedReadsRemainTerminal() throws Exception {
        try (var budget = new TempBudget()) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            new Source(
                                                    List.of(link(1, 1), link(1, 1)),
                                                    KeyPolicy.EFFECT_LINK),
                                            directory,
                                            budget,
                                            Cancellation.NONE,
                                            KeyPolicy.EFFECT_LINK)
                                    .join());
            var sorted =
                    SortedComparisonInput.capture(
                                    new Source(List.of(link(1, 1)), KeyPolicy.EFFECT_LINK),
                                    directory,
                                    budget,
                                    Cancellation.NONE,
                                    KeyPolicy.EFFECT_LINK)
                            .join();
            try {
                var file = runFile();
                try (var raw = new RandomAccessFile(file.toFile(), "rw")) {
                    raw.seek(9); // frame length4 + record-present1 + string length4
                    raw.write(link(2147483648L, 1).getBytes(StandardCharsets.US_ASCII));
                }
                assertThrows(
                        RuntimeException.class,
                        () -> sorted.read(null, 1, Cancellation.NONE).join());
            } finally {
                sorted.closeAsync().join();
            }
            assertEquals(0, budget.snapshot().memory());
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void realMultiRunMergeRevalidatesPrivateKeysInsteadOfTrustingIngress() throws Exception {
        try (var budget = new TempBudget()) {
            var keys = new ArrayList<String>();
            for (int i = 500; i > 0; i--) {
                keys.add(link(1, i));
            }
            var source = new Source(keys, KeyPolicy.EFFECT_LINK);
            source.payload = "x".repeat(10000);
            var corrupted = new AtomicBoolean();
            source.hook =
                    () -> {
                        if (corrupted.get()) {
                            return;
                        }
                        try {
                            Path file = runFile();
                            if (file == null) {
                                return;
                            }
                            try (var raw = new RandomAccessFile(file.toFile(), "rw")) {
                                if (raw.length() == 0) {
                                    return;
                                }
                                raw.seek(9);
                                raw.write(link(2147483648L, 1).getBytes(StandardCharsets.US_ASCII));
                            }
                            corrupted.set(true);
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    };
            assertThrows(
                    RuntimeException.class,
                    () ->
                            SortedComparisonInput.capture(
                                            source,
                                            directory,
                                            budget,
                                            Cancellation.NONE,
                                            KeyPolicy.EFFECT_LINK)
                                    .join());
            assertTrue(
                    corrupted.get(), "Fixture must mutate an actual sealed first run before merge");
            assertTrue(source.closed);
            assertEquals(0, budget.snapshot().disk());
            assertTrue(budget.snapshot().peakMemory() <= 32L << 20);
        }
    }

    private Path runFile() throws java.io.IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(
                            p ->
                                    p.getFileName().toString().startsWith("run-")
                                            && Files.isRegularFile(p))
                    .findFirst()
                    .orElse(null);
        }
    }
}
