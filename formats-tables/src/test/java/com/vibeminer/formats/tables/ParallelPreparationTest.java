package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The bounded-concurrency shallow and deep preload passes must match the sequential passes. */
@Timeout(60)
final class ParallelPreparationTest {
    private static final com.vibeminer.builds.BuildTypes.BuildRef BUILD = TablesContract.BUILD;
    private static final String UNKNOWN_LAYOUT_ID = "5000";

    /** Sequential first, then the production defaults and a few other windows. */
    private static final int[][] WINDOWS = {
        {1, 1},
        {ClientBuildTables.SHALLOW_CONCURRENCY, ClientBuildTables.DEEP_CONCURRENCY},
        {2, 2},
        {3, 7},
        {64, 64}
    };

    private static FileTypes.BuildFile file(long id, byte[] bytes) {
        return new FileTypes.BuildFile(
                id,
                "dbfilesclient/t" + id + ".db2",
                FileTypes.FilePathSource.LISTFILE,
                (long) bytes.length,
                String.format("%032x", id),
                FileTypes.FileFormat.DB2,
                FileTypes.FileFormatSource.EXTENSION,
                FileTypes.FileStorage.STORED,
                false);
    }

    /**
     * Mixed inventory: valid tables of different sizes, corrupt bytes, fully protected payloads,
     * tables missing from the build and one table whose definition has no usable layout.
     */
    private static MemoryFiles mixed(int count) {
        var memory = new MemoryFiles();
        for (int i = 0; i < count; i++) {
            long id = 100 + i;
            if (i % 11 == 3) {
                continue;
            }
            byte[] bytes =
                    i % 5 == 1
                            ? new byte[8]
                            : Db2Fixture.numeric("WDC5", 0, 32, 1 + i, new long[0], i % 2 == 0);
            var holes =
                    i % 7 == 2
                            ? List.of(new FileTypes.FileHole(0, bytes.length, "0123456789ABCDEF"))
                            : List.<FileTypes.FileHole>of();
            memory.add(BUILD, "enUS", file(id, bytes), bytes, holes);
        }
        byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 3, new long[0], false);
        memory.add(BUILD, "enUS", file(Long.parseLong(UNKNOWN_LAYOUT_ID), bytes), bytes, List.of());
        return memory;
    }

    private static MemoryFiles valid(int count) {
        var memory = new MemoryFiles();
        for (int i = 0; i < count; i++) {
            byte[] bytes = Db2Fixture.numeric("WDC5", 0, 32, 1 + i, new long[0], i % 2 == 0);
            memory.add(BUILD, "enUS", file(100 + i, bytes), bytes, List.of());
        }
        return memory;
    }

    private static Tables service(
            com.vibeminer.files.Files files, int count, MemoryGeneratedStore store) {
        var definition =
                Db2Fixture.definition("int ID\nint Value", "$id,noninline$ID<u32>\nValue<u32>");
        var inventory = new ArrayList<BundledDefinitions.DefinitionTable>();
        for (int i = 0; i < count; i++) {
            inventory.add(
                    new BundledDefinitions.DefinitionTable(
                            "11223344", "T" + (100 + i), Long.toString(100 + i), true));
        }
        inventory.add(
                new BundledDefinitions.DefinitionTable(
                        "55667788", "Unknown", UNKNOWN_LAYOUT_ID, true));
        var provider =
                BundledDefinitions.of(
                        inventory,
                        Map.of(
                                "11223344",
                                new BundledDefinitions.Definition(
                                        "a".repeat(64), "fixture", definition)));
        return new TablesService(
                files,
                new TableDefinitions(provider, null, null),
                new GeneratedTables(store),
                new TablesOptions((build, token) -> CompletableFuture.completedFuture("1.2.3.4"))
                        .withLocalDefinitionsOnly());
    }

    private record Run(
            TableTypes.PreloadResult result,
            List<TableTypes.PreloadProgress> progress,
            List<Long> prepared) {}

    private static Run run(Tables source, int shallow, int deep, boolean known) {
        return run(source, shallow, deep, known, Cancellation.NONE, ignored -> {});
    }

    private static Run run(
            Tables source,
            int shallow,
            int deep,
            boolean known,
            Cancellation token,
            java.util.function.Consumer<ClientBuildTables> setup) {
        try (var handle = source.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables()) {
            var client = (ClientBuildTables) handle;
            var progress =
                    Collections.synchronizedList(new ArrayList<TableTypes.PreloadProgress>());
            var prepared = Collections.synchronizedList(new ArrayList<Long>());
            client.shallowConcurrency = shallow;
            client.deepConcurrency = deep;
            client.emittedProgress = progress::add;
            client.deepPrepared = prepared::add;
            setup.accept(client);
            var result =
                    (known ? handle.prepareKnown(null, token) : handle.preload(null, token)).join();
            return new Run(result, List.copyOf(progress), List.copyOf(prepared));
        }
    }

    /**
     * The first report and every report the preparing loop emits. Intermediate download reports are
     * forwarded from the file layer's own asynchronous progress and vary between any two runs.
     */
    private static List<TableTypes.PreloadProgress> ordered(
            List<TableTypes.PreloadProgress> progress) {
        var kept = new ArrayList<TableTypes.PreloadProgress>();
        for (int i = 0; i < progress.size(); i++) {
            if (i == 0 || progress.get(i).stage() != TableTypes.PreloadStage.DOWNLOADING) {
                kept.add(progress.get(i));
            }
        }
        return kept;
    }

    /** A build that outlives a canceled caller closes its source on the store's own thread. */
    private static void awaitClosed(Probe probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (probe.openStreams.get() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, probe.openStreams.get(), "Every source is closed");
    }

    private static Map<TableTypes.State, Long> counts(TableTypes.PreloadResult result) {
        return result.tables().stream()
                .collect(Collectors.groupingBy(TableTypes.Entry::state, Collectors.counting()));
    }

    private static void assertSameRun(Run expected, Run actual, String label) {
        assertEquals(expected.result(), actual.result(), label + ": result");
        assertEquals(
                ordered(expected.progress()),
                ordered(actual.progress()),
                label + ": ordered progress");
        assertEquals(expected.prepared(), actual.prepared(), label + ": full prepare order");
        var want = counts(expected.result());
        var got = counts(actual.result());
        assertEquals(want, got, label + ": state counts");
        assertEquals(
                want.getOrDefault(TableTypes.State.READY, 0L),
                got.getOrDefault(TableTypes.State.READY, 0L),
                label + ": READY");
        assertEquals(
                want.getOrDefault(TableTypes.State.PARTIAL, 0L),
                got.getOrDefault(TableTypes.State.PARTIAL, 0L),
                label + ": PARTIAL");
    }

    /** Cold pass, warm pass, then another warm pass, each on the same fresh store. */
    private static List<Run> passes(
            java.util.function.Supplier<MemoryFiles> files,
            int count,
            int shallow,
            int deep,
            boolean known) {
        var store = new MemoryGeneratedStore();
        var source = service(files.get(), count, store);
        var runs = new ArrayList<Run>();
        for (int i = 0; i < 3; i++) {
            runs.add(run(source, shallow, deep, known));
        }
        return runs;
    }

    private static void assertWindowsMatchSequential(
            java.util.function.Supplier<MemoryFiles> files, int count, boolean known) {
        var sequential = passes(files, count, 1, 1, known);
        for (var window : WINDOWS) {
            var concurrent = passes(files, count, window[0], window[1], known);
            for (int i = 0; i < sequential.size(); i++) {
                assertSameRun(
                        sequential.get(i),
                        concurrent.get(i),
                        (known ? "known" : "preload")
                                + " pass "
                                + i
                                + " window "
                                + window[0]
                                + "/"
                                + window[1]);
            }
        }
    }

    @Test
    void concurrentKnownPassesEqualTheSequentialPass() {
        int count = 41;
        var cold = passes(() -> mixed(count), count, 1, 1, true).getFirst();
        var states = counts(cold.result());
        assertEquals(TableTypes.PreloadOutcome.COMPLETED, cold.result().outcome());
        assertTrue(states.getOrDefault(TableTypes.State.READY, 0L) > 10, states.toString());
        assertTrue(states.getOrDefault(TableTypes.State.ENCRYPTED, 0L) > 0, states.toString());
        assertTrue(states.getOrDefault(TableTypes.State.NOT_IN_BUILD, 0L) > 0, states.toString());
        assertEquals(1L, states.get(TableTypes.State.DEFINITION_UNUSABLE), states.toString());
        assertTrue(
                cold.result().tables().stream()
                        .anyMatch(
                                entry ->
                                        !TableQueries.isReadable(entry.state())
                                                && entry.state() != TableTypes.State.ENCRYPTED
                                                && entry.state() != TableTypes.State.NOT_IN_BUILD
                                                && entry.state()
                                                        != TableTypes.State.DEFINITION_UNUSABLE),
                "fixture has corrupt tables");
        assertWindowsMatchSequential(() -> mixed(count), count, true);
    }

    @Test
    void concurrentPreloadPassesEqualTheSequentialPass() {
        int count = 41;
        assertWindowsMatchSequential(() -> mixed(count), count, false);
    }

    @Test
    void storeFailureOutcomeIsEqualAndIsolatedUnderConcurrency() {
        int count = 30;
        java.util.function.Supplier<MemoryFiles> files =
                () ->
                        mixed(count)
                                .failure(
                                        BUILD,
                                        "enUS",
                                        112,
                                        FileTypes.FileRead.failed(
                                                FileTypes.FileReadState.STORE_FAILED,
                                                0,
                                                "STORE_FAILED",
                                                "fixture",
                                                null));
        var sequential = passes(files, count, 1, 1, true).getFirst();
        assertEquals(TableTypes.PreloadOutcome.FAILED, sequential.result().outcome());
        var concurrent = passes(files, count, 8, 4, true).getFirst();
        assertSameRun(sequential, concurrent, "store failure");
        var healthy = passes(() -> mixed(count), count, 8, 4, true).getFirst();
        for (var entry : concurrent.result().tables()) {
            if (entry.fileDataId() != 112) {
                assertTrue(
                        healthy.result().tables().contains(entry),
                        "Other tables are unaffected: " + entry);
            } else {
                assertEquals(TableTypes.State.STORE_FAILED, entry.state());
            }
        }
        assertWindowsMatchSequential(files, count, true);
    }

    @Test
    void concurrentPassesEqualTheSequentialPassForAPartialTable() {
        byte[] unrelated = Db2Fixture.numeric("WDC5", 0, 32, 9, new long[0], false);
        var results = new ArrayList<TableTypes.PreloadResult>();
        for (var window : WINDOWS) {
            var memory =
                    new MemoryFiles()
                            .add(BUILD, "enUS", file(999, unrelated), unrelated, List.of());
            var borrowed = memory.forBuild(BUILD, "enUS", Cancellation.NONE).join().files();
            try (var fixture = new AuthoredPartialNativeMap(borrowed)) {
                var client = (ClientBuildTables) fixture.tables();
                client.shallowConcurrency = window[0];
                client.deepConcurrency = window[1];
                var cold = client.prepareKnown(null, Cancellation.NONE).join();
                var warm = client.prepareKnown(null, Cancellation.NONE).join();
                assertEquals(TableTypes.State.PARTIAL, cold.tables().getFirst().state());
                assertEquals(cold, warm);
                results.add(cold);
            }
        }
        for (var result : results) {
            assertEquals(results.getFirst(), result);
        }
    }

    /** Files over MemoryFiles that count, track, hold or fail table reads. */
    private static final class Probe implements com.vibeminer.files.Files {
        final MemoryFiles memory;
        final Map<Long, AtomicInteger> reads = new ConcurrentHashMap<>();
        final AtomicInteger openStreams = new AtomicInteger(), peakStreams = new AtomicInteger();
        final AtomicInteger readers = new AtomicInteger(), peakReaders = new AtomicInteger();
        volatile long holdRead = -1, holdStat = -1, failRead = -1;
        volatile RuntimeException readFailure;
        volatile Error readError;
        volatile CountDownLatch rendezvous;
        final CompletableFuture<Void> held = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final AtomicBoolean statHeld = new AtomicBoolean();

        Probe(MemoryFiles memory) {
            this.memory = memory;
        }

        int totalReads() {
            return reads.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        @Override
        public com.vibeminer.files.names.FileNames names() {
            return memory.names();
        }

        @Override
        public CompletableFuture<FileTypes.FilesOpenResult> forBuild(
                com.vibeminer.builds.BuildTypes.BuildRef build, String locale, Cancellation token) {
            return memory.forBuild(build, locale, token)
                    .thenApply(
                            open ->
                                    new FileTypes.FilesOpenResult(
                                            build,
                                            locale,
                                            open.state(),
                                            wrap(open.files()),
                                            null,
                                            null,
                                            null));
        }

        private BuildFiles wrap(BuildFiles target) {
            return (BuildFiles)
                    java.lang.reflect.Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (object, method, args) -> {
                                if (method.getName().equals("openRead")) {
                                    return openRead(target, method, args);
                                }
                                if (method.getName().equals("statContent")) {
                                    long id = ((FileTypes.FileRef) args[0]).fileDataId();
                                    if (id == holdStat && statHeld.compareAndSet(false, true)) {
                                        return hold((Cancellation) args[1])
                                                .thenCompose(ignored -> call(target, method, args));
                                    }
                                }
                                return call(target, method, args);
                            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T call(Object target, java.lang.reflect.Method method, Object[] args) {
            try {
                return (T) method.invoke(target, args);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException failure) {
                    throw failure;
                }
                if (error.getCause() instanceof Error failure) {
                    throw failure;
                }
                throw new IllegalStateException(error.getCause());
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }

        private CompletableFuture<Void> hold(Cancellation token) {
            held.complete(null);
            var gate = new CompletableFuture<Void>();
            release.whenComplete((value, error) -> gate.complete(null));
            var registration =
                    token.onCancel(() -> gate.completeExceptionally(new CancellationException()));
            gate.whenComplete((value, error) -> registration.close());
            return gate;
        }

        private CompletableFuture<FileTypes.FileOpen> openRead(
                BuildFiles target, java.lang.reflect.Method method, Object[] args) {
            long id = ((FileTypes.FileRef) args[0]).fileDataId();
            var token = (Cancellation) args[1];
            reads.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            if (id == failRead && readError != null) {
                throw readError;
            }
            if (id == failRead && readFailure != null) {
                throw readFailure;
            }
            int now = readers.incrementAndGet();
            peakReaders.accumulateAndGet(now, Math::max);
            try {
                var latch = rendezvous;
                if (latch != null) {
                    latch.countDown();
                    latch.await(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                readers.decrementAndGet();
            }
            CompletableFuture<Void> gate =
                    id == holdRead ? hold(token) : CompletableFuture.completedFuture(null);
            return gate.thenCompose(
                    ignored ->
                            Probe.<CompletableFuture<FileTypes.FileOpen>>call(target, method, args)
                                    .thenApply(this::track));
        }

        private FileTypes.FileOpen track(FileTypes.FileOpen open) {
            if (open.stream() == null) {
                return open;
            }
            peakStreams.accumulateAndGet(openStreams.incrementAndGet(), Math::max);
            var inner = open.stream();
            var closed = new AtomicBoolean();
            var tracked =
                    new FileReadStream() {
                        @Override
                        public long length() {
                            return inner.length();
                        }

                        @Override
                        public long position() {
                            return inner.position();
                        }

                        @Override
                        public void seek(long position) {
                            inner.seek(position);
                        }

                        @Override
                        public CompletableFuture<Integer> read(
                                ByteBuffer destination, Cancellation cancellation) {
                            return inner.read(destination, cancellation);
                        }

                        @Override
                        public List<FileTypes.FileHole> holes() {
                            return inner.holes();
                        }

                        @Override
                        public List<String> chunkKeys() {
                            return inner.chunkKeys();
                        }

                        @Override
                        public void close() {
                            if (closed.compareAndSet(false, true)) {
                                openStreams.decrementAndGet();
                            }
                            inner.close();
                        }
                    };
            return new FileTypes.FileOpen(
                    open.state(), tracked, open.code(), open.message(), open.observedAt());
        }
    }

    @Test
    void eachBuiltTableIsReadOnceAndReadyTablesAreNotReadAgain() {
        int count = 12;
        for (var window : WINDOWS) {
            var probe = new Probe(valid(count));
            var store = new MemoryGeneratedStore();
            var source = service(probe, count, store);
            var cold = run(source, window[0], window[1], true);
            assertEquals(TableTypes.PreloadOutcome.COMPLETED, cold.result().outcome());
            for (int i = 0; i < count; i++) {
                assertEquals(
                        1,
                        probe.reads.get(100L + i).get(),
                        "A cold table's build reuses the reader its prepare opened");
            }
            assertEquals(0, probe.openStreams.get(), "Every source is closed after the pass");
            int before = probe.totalReads();
            var warm = run(source, window[0], window[1], true);
            assertEquals(cold.result().tables(), warm.result().tables());
            assertEquals(before, probe.totalReads(), "READY tables are not read again");
        }
    }

    @Test
    void deepWindowBoundsInFlightTablesAndOpenSources() {
        int count = 24;
        for (int limit : new int[] {2, ClientBuildTables.DEEP_CONCURRENCY, 7}) {
            var probe = new Probe(valid(count));
            // Two background slots: make the first two reads meet, so the window demonstrably
            // runs prepares ahead of the consumer.
            probe.rendezvous = new CountDownLatch(2);
            var store = new MemoryGeneratedStore();
            var source = service(probe, count, store);
            var events = Collections.synchronizedList(new ArrayList<long[]>());
            var cold =
                    run(
                            source,
                            1,
                            limit,
                            true,
                            Cancellation.NONE,
                            client -> {
                                client.emittedProgress =
                                        p -> {
                                            if (p.stage() == TableTypes.PreloadStage.PREPARING) {
                                                events.add(new long[] {0, p.tablesDone()});
                                            }
                                        };
                                client.deepPrepared = id -> events.add(new long[] {1, id});
                            });
            assertEquals(TableTypes.PreloadOutcome.COMPLETED, cold.result().outcome());
            assertEquals(0, probe.openStreams.get());
            assertTrue(probe.peakReaders.get() >= 2, "prepares ran ahead: " + probe.peakReaders);
            assertTrue(
                    probe.peakStreams.get() <= limit,
                    "open sources " + probe.peakStreams + " exceed the window " + limit);
            long done = 0;
            int issued = 0;
            for (var event : events) {
                if (event[0] == 0) {
                    done = event[1];
                } else {
                    issued++;
                    assertTrue(
                            issued - done <= limit,
                            "issued " + issued + " with " + done + " consumed, window " + limit);
                }
            }
            assertEquals(count, issued);
            assertEquals(count, done);
        }
    }

    @Test
    void cancelingTheDeepLoopStopsIssuingAndDrainsInFlightWork() throws Exception {
        int count = 30;
        var reference = passes(() -> valid(count), count, 1, 1, true).getFirst().result();
        var probe = new Probe(valid(count));
        long heldId = 110;
        probe.holdRead = heldId;
        var store = new MemoryGeneratedStore();
        var source = service(probe, count, store);
        var token = new Cancellation();
        probe.held.thenRun(token::cancel);
        var canceled = run(source, 8, ClientBuildTables.DEEP_CONCURRENCY, true, token, c -> {});
        assertEquals(TableTypes.PreloadOutcome.CANCELED, canceled.result().outcome());
        awaitClosed(probe);
        assertTrue(
                canceled.prepared().size() <= heldId - 100 + ClientBuildTables.DEEP_CONCURRENCY,
                "issued " + canceled.prepared());
        var byId =
                reference.tables().stream()
                        .collect(Collectors.toMap(TableTypes.Entry::fileDataId, entry -> entry));
        var prepared = new ArrayList<Long>();
        for (var entry : canceled.result().tables()) {
            assertEquals(byId.get(entry.fileDataId()), entry, "Ledger entries are final outcomes");
            if (entry.state() != TableTypes.State.NOT_IN_BUILD) {
                prepared.add(entry.fileDataId());
            }
        }
        assertTrue(prepared.size() <= heldId - 100, "ledger " + prepared);
        for (int i = 0; i < prepared.size(); i++) {
            assertEquals(100L + i, prepared.get(i), "Ledger holds an in-order prefix");
        }
        assertEquals(prepared.size(), canceled.result().progress().tablesDone());
        int reads = probe.totalReads();
        probe.release.complete(null);
        // Nothing keeps running after the canceled pass returned.
        assertEquals(reads, probe.totalReads());
        var resumed = run(source, 8, ClientBuildTables.DEEP_CONCURRENCY, true);
        assertEquals(reference.tables(), resumed.result().tables());
    }

    @Test
    void cancelingTheShallowPassDrainsInFlightWork() {
        int count = 30;
        var reference = passes(() -> valid(count), count, 1, 1, true).getFirst().result();
        var probe = new Probe(valid(count));
        probe.holdStat = 112;
        var store = new MemoryGeneratedStore();
        var source = service(probe, count, store);
        var token = new Cancellation();
        probe.held.thenRun(token::cancel);
        var canceled = run(source, ClientBuildTables.SHALLOW_CONCURRENCY, 4, true, token, c -> {});
        assertEquals(TableTypes.PreloadOutcome.CANCELED, canceled.result().outcome());
        assertTrue(canceled.result().tables().isEmpty());
        assertTrue(canceled.prepared().isEmpty(), "No full prepare starts");
        assertEquals(0, probe.totalReads());
        var resumed = run(source, ClientBuildTables.SHALLOW_CONCURRENCY, 4, true);
        assertEquals(reference.tables(), resumed.result().tables());
    }

    @Test
    void aTableThatFailsToReadIsIsolatedAndEqualToTheSequentialPass() {
        int count = 20;
        Run sequential = null;
        for (var window : WINDOWS) {
            var probe = new Probe(valid(count));
            probe.failRead = 107;
            probe.readFailure = new IllegalStateException("fixture read failure");
            var run =
                    run(
                            service(probe, count, new MemoryGeneratedStore()),
                            window[0],
                            window[1],
                            true);
            assertEquals(TableTypes.PreloadOutcome.COMPLETED, run.result().outcome());
            for (var entry : run.result().tables()) {
                if (entry.fileDataId() >= 100 + count) {
                    continue;
                }
                if (entry.fileDataId() == 107) {
                    assertFalse(TableQueries.isReadable(entry.state()));
                    assertEquals("fixture read failure", entry.reason().message());
                } else {
                    assertEquals(TableTypes.State.READY, entry.state(), entry.toString());
                }
            }
            assertEquals(0, probe.openStreams.get());
            if (sequential == null) {
                sequential = run;
            } else {
                assertSameRun(
                        sequential, run, "read failure window " + window[0] + "/" + window[1]);
            }
        }
    }

    @Test
    void anErrorEndsThePassTheSameWayAndDrainsInFlightWork() throws Exception {
        int count = 20;
        for (var window : WINDOWS) {
            var probe = new Probe(valid(count));
            probe.failRead = 105;
            probe.readError = new AssertionError("fixture error");
            var source = service(probe, count, new MemoryGeneratedStore());
            var handle = source.forBuild(BUILD, "enUS", Cancellation.NONE).join().tables();
            var client = (ClientBuildTables) handle;
            client.shallowConcurrency = window[0];
            client.deepConcurrency = window[1];
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () -> handle.prepareKnown(null, Cancellation.NONE).join());
            var cause = assertInstanceOf(AssertionError.class, failure.getCause());
            assertEquals("fixture error", cause.getMessage());
            awaitClosed(probe);
            handle.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }
}
