package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.Builds;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.effects.BuildItemEffects;
import com.vibeminer.concepts.items.BuildItems;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TablesService;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resource publication contract; reflection supplies staged fixtures without native I/O or assets.
 */
public final class NativeWorkspaceCommitTest {
    private NativeWorkspaceCommitTest() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T resource(Class<T> type, AtomicInteger closed) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                closed.incrementAndGet();
                                return null;
                            }
                            if (method.getName().equals("closeAsync")) {
                                closed.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            }
                            if (method.getName().equals("equals")) return proxy == args[0];
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(proxy);
                            throw new AssertionError("Unexpected resource operation " + method);
                        }));
    }

    private static Object workspace(
            BuildTables tables,
            BuildItems items,
            BuildCreatures creatures,
            BuildItemEffects effects,
            long revision)
            throws Exception {
        var type = Class.forName("com.vimi.gui.NativePreparationWork$Workspace");
        var constructor =
                type.getDeclaredConstructor(
                        BuildTables.class,
                        TablesService.class,
                        BuildItems.class,
                        BuildCreatures.class,
                        BuildItemEffects.class,
                        long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tables, null, items, creatures, effects, revision);
    }

    private static void set(NativePreparationWork work, String field, Object value)
            throws Exception {
        var member = NativePreparationWork.class.getDeclaredField(field);
        member.setAccessible(true);
        member.set(work, value);
    }

    public static void main(String[] args) throws Exception {
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3.45678",
                        "us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        var evidence = new BuildInfo.RegionEvidence("us", "b".repeat(32), true, null, null);
        ObjectSource online =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, values) -> {
                                    throw new AssertionError(
                                            "No network/storage work in publication");
                                });
        ObjectSource local =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, values) -> {
                                    throw new AssertionError("No read work in publication");
                                });
        Builds builds =
                (build, token) ->
                        CompletableFuture.failedFuture(
                                new AssertionError("No opening during publication"));
        var work =
                new NativePreparationWork(
                        info, evidence, "enUS", builds, online, null, null, local);
        var oldClosed = new AtomicInteger();
        var abandonedClosed = new AtomicInteger();
        var nextClosed = new AtomicInteger();
        var oldTable = resource(BuildTables.class, oldClosed);
        var oldItem = resource(BuildItems.class, oldClosed);
        set(work, "published", workspace(oldTable, oldItem, null, null, 5));
        var abandoned = resource(BuildTables.class, abandonedClosed);
        set(work, "pending", workspace(abandoned, null, null, null, 0));
        set(work, "pendingWarmed", true);
        var canceled = new Cancellation();
        canceled.cancel();
        try {
            work.commit(canceled);
            throw new AssertionError("Canceled generation committed");
        } catch (java.util.concurrent.CancellationException expected) {
        }
        work.abortPending();
        require(
                work.tablesHandle() == oldTable
                        && work.itemsHandle() == oldItem
                        && work.revision() == 5,
                "Canceled preparation preserves all published handles and revision");
        require(
                oldClosed.get() == 0 && abandonedClosed.get() == 1,
                "Abort closes staged resources only");
        set(
                work,
                "pending",
                workspace(resource(BuildTables.class, abandonedClosed), null, null, null, 0));
        set(work, "pendingWarmed", false);
        try {
            work.commit(Cancellation.NONE);
            throw new AssertionError("Unwarmed generation committed");
        } catch (IllegalStateException expected) {
        }
        work.abortPending();
        require(
                work.tablesHandle() == oldTable
                        && work.itemsHandle() == oldItem
                        && oldClosed.get() == 0,
                "Failed lookup preparation cannot replace or close the published generation");
        require(abandonedClosed.get() == 2, "Unwarmed pending table is cleaned up");
        var nextTable = resource(BuildTables.class, nextClosed);
        var nextItem = resource(BuildItems.class, nextClosed);
        set(work, "pending", workspace(nextTable, nextItem, null, null, 0));
        set(work, "pendingWarmed", true);
        work.commit(Cancellation.NONE);
        require(
                work.tablesHandle() == nextTable
                        && work.itemsHandle() == nextItem
                        && work.revision() == 6,
                "One commit publishes table and concept generation together");
        require(oldClosed.get() == 0 && nextClosed.get() == 0, "Commit does no resource I/O");
        work.retireCommitted();
        work.close();
        require(
                oldClosed.get() == 2 && nextClosed.get() == 2,
                "Close waits for retirement and closes the live generation");
        metadataListingDoesNotReadPayloads(info, evidence, online, local);
        System.out.println(
                "Passed atomic workspace commit, cancellation, abort, retirement and metadata-only Files contracts");
    }

    private static void metadataListingDoesNotReadPayloads(
            BuildInfo info,
            BuildInfo.RegionEvidence evidence,
            ObjectSource online,
            ObjectSource local)
            throws Exception {
        var ref =
                new com.vibeminer.builds.BuildTypes.BuildRef(
                        info.product(), info.config(), evidence.cdnConfig(), evidence.region());
        var build =
                (com.vibeminer.builds.Build)
                        Proxy.newProxyInstance(
                                com.vibeminer.builds.Build.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.builds.Build.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" -> ref;
                                            case "fileDataIds" -> java.util.List.of(1L);
                                            default ->
                                                    throw new AssertionError(
                                                            "Metadata preparation cannot resolve/read payloads: "
                                                                    + method);
                                        });
        Builds loader =
                (wanted, token) ->
                        CompletableFuture.completedFuture(
                                new com.vibeminer.builds.BuildTypes.BuildOpenResult(
                                        ref,
                                        com.vibeminer.builds.BuildTypes.BuildOpenState.OPEN,
                                        build,
                                        null,
                                        null,
                                        null,
                                        null));
        var work =
                new NativePreparationWork(
                        info, evidence, "enUS", loader, online, null, null, local);
        var pages = new AtomicInteger();
        var cursor =
                new com.vibeminer.files.AsyncCursor<com.vibeminer.files.FileTypes.BuildFile>() {
                    public CompletableFuture<
                                    java.util.List<com.vibeminer.files.FileTypes.BuildFile>>
                            next(int limit, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                pages.getAndIncrement() == 0
                                        ? java.util.List.of(
                                                new com.vibeminer.files.FileTypes.BuildFile(
                                                        1,
                                                        "cached-model.m2",
                                                        com.vibeminer.files.FileTypes.FilePathSource
                                                                .LISTFILE,
                                                        128L,
                                                        "c".repeat(32),
                                                        com.vibeminer.files.FileTypes.FileFormat
                                                                .UNKNOWN,
                                                        com.vibeminer.files.FileTypes
                                                                .FileFormatSource.NONE,
                                                        com.vibeminer.files.FileTypes.FileStorage
                                                                .STORED,
                                                        false))
                                        : java.util.List.of());
                    }

                    public void close() {}
                };
        var files =
                (com.vibeminer.files.BuildFiles)
                        Proxy.newProxyInstance(
                                com.vibeminer.files.BuildFiles.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("listMetadata")) return cursor;
                                    if (method.getName().equals("close")) return null;
                                    throw new AssertionError(
                                            "Files preparation touched media/content path: "
                                                    + method);
                                });
        set(work, "fileHandle", files);
        try {
            var summary = work.files(counter -> {}, Cancellation.NONE);
            require(
                    summary.files() == 1,
                    "Metadata-only Files summary must include the cached media entry");
        } finally {
            work.close();
        }
    }
}
