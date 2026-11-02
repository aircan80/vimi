package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Browser queries exercise metadata-only access and page-boundary/search behavior without JavaFX.
 */
public final class ResearchBrowserDataTest {
    private ResearchBrowserDataTest() {}

    private static FileTypes.BuildFile file(long id, String path) {
        return new FileTypes.BuildFile(
                id,
                path,
                path == null ? FileTypes.FilePathSource.NONE : FileTypes.FilePathSource.LISTFILE,
                null,
                "abc",
                FileTypes.FileFormat.UNKNOWN,
                FileTypes.FileFormatSource.NONE,
                FileTypes.FileStorage.NOT_STORED,
                false);
    }

    public static void main(String[] args) {
        var files = new ArrayList<FileTypes.BuildFile>();
        for (int i = 1; i <= 2250; i++) files.add(file(i, i == 2110 ? "world/needle.m2" : null));
        var closes = new AtomicInteger();
        BuildFiles handle =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, values) -> {
                                    if (method.getName().equals("locale")) return "enUS";
                                    if (!method.getName().equals("listMetadata"))
                                        throw new AssertionError(
                                                "Unexpected payload/other call: "
                                                        + method.getName());
                                    var filter = (FileTypes.FileFilter) values[0];
                                    var selected =
                                            files.stream()
                                                    .filter(
                                                            f ->
                                                                    f.fileDataId()
                                                                                    >= filter
                                                                                            .fromId()
                                                                            && f.fileDataId()
                                                                                    <= filter
                                                                                            .toId())
                                                    .toList();
                                    return new AsyncCursor<FileTypes.BuildFile>() {
                                        int offset;

                                        public CompletableFuture<List<FileTypes.BuildFile>> next(
                                                int limit, Cancellation token) {
                                            token.check();
                                            int end = Math.min(selected.size(), offset + limit);
                                            var page = selected.subList(offset, end);
                                            offset = end;
                                            return CompletableFuture.completedFuture(page);
                                        }

                                        public void close() {
                                            closes.incrementAndGet();
                                        }
                                    };
                                });
        var one = ResearchSource.files(handle, "", null, new Cancellation());
        var two = ResearchSource.files(handle, "", one.next(), new Cancellation());
        require(one.rows().size() == 100 && one.rows().getFirst().id() == 1, "First page");
        require(two.rows().getFirst().id() == 101, "No dropped boundary rows");
        var empty = ResearchSource.files(handle, "needle", null, new Cancellation());
        require(
                empty.rows().isEmpty() && empty.next() != null,
                "Empty search window can have continuation");
        var found = ResearchSource.files(handle, "needle", empty.next(), new Cancellation());
        require(
                found.rows().size() == 1 && found.rows().getFirst().id() == 2110,
                "Search continues beyond first window");
        var exact = ResearchSource.files(handle, "2110", null, new Cancellation());
        require(exact.rows().size() == 1 && exact.next() == null, "Exact ID query");
        require(closes.get() == 5, "Every request closes its metadata cursor");
        require(
                ResearchPresentation.file(file(1, null)).cell(3).equals("Unknown"),
                "Unknown size never zero");
        var token = new Cancellation();
        token.cancel();
        try {
            ResearchSource.files(handle, "", null, token);
            throw new AssertionError("Canceled scan accepted");
        } catch (java.util.concurrent.CancellationException expected) {
            /* expected */
        }
        System.out.println(
                "Passed metadata-only browser paging, search, unknown sizes, cancellation and cursor retirement");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
