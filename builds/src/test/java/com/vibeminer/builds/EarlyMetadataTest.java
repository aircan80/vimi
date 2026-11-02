package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.store.ObjectKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class EarlyMetadataTest {
    @Test
    void earlyMetadataNeverAcquiresIndexesAndFullOpeningReusesAuthenticatedContext() {
        var fixture = new SyntheticBuild();
        var keys = ConcurrentHashMap.<ObjectKey>newKeySet();
        var encodings = new AtomicInteger();
        ObjectSource source =
                proxy(
                        fixture,
                        (method, args) -> {
                            if (method.equals("getKey")) {
                                var key = ((SourceTypes.ObjectRequest) args[0]).key();
                                keys.add(key);
                                if (key.equals(fixture.encodingKey)) {
                                    encodings.incrementAndGet();
                                }
                            }
                            if (method.equals("getKeys")) {
                                for (Object request : (List<?>) args[0]) {
                                    keys.add(((SourceTypes.ObjectRequest) request).key());
                                }
                            }
                        });
        var opener = new BuildOpener(source);
        try {
            var early = opener.metadata(fixture.ref, Cancellation.NONE).join();
            assertEquals(BuildOpenState.OPEN, early.state());
            assertEquals(2, early.metadata().fileCount());
            assertFalse(keys.contains(fixture.archiveIndexKey));
            assertFalse(keys.contains(fixture.fileIndexKey));
            int count = fixture.reads.get();
            assertEquals(early, opener.metadata(fixture.ref, Cancellation.NONE).join());
            assertEquals(count, fixture.reads.get());
            var full = opener.open(fixture.ref, Cancellation.NONE).join();
            assertEquals(BuildOpenState.OPEN, full.state());
            assertEquals(early.metadata(), full.opened().metadata());
            assertTrue(keys.contains(fixture.archiveIndexKey));
            assertTrue(keys.contains(fixture.fileIndexKey));
            assertEquals(1, encodings.get(), "ENCODING must not be acquired/decoded twice");
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void earlySuccessDoesNotHideLaterIndexFailure() {
        var fixture = new SyntheticBuild();
        fixture.failures.put(
                fixture.archiveIndexKey,
                new SourceTypes.ObjectResult(
                        fixture.archiveIndexKey,
                        SourceTypes.ObjectState.NOT_FOUND,
                        null,
                        null,
                        null,
                        null,
                        "INDEX_MISSING",
                        "Missing index",
                        null));
        var opener = new BuildOpener(fixture);
        try {
            assertEquals(
                    BuildOpenState.OPEN,
                    opener.metadata(fixture.ref, Cancellation.NONE).join().state());
            var full = opener.open(fixture.ref, Cancellation.NONE).join();
            assertEquals(BuildOpenState.MISSING, full.state());
            assertEquals(BuildTypes.BuildPart.ARCHIVE_INDEX, full.part());
            assertEquals("INDEX_MISSING", full.code());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void earlyEncryptedRootHasTypedFailureAndCanBeRetried() {
        var fixture = new SyntheticBuild('E', false);
        var opener = new BuildOpener(fixture);
        try {
            var first = opener.metadata(fixture.ref, Cancellation.NONE).join();
            assertEquals(BuildOpenState.ENCRYPTED, first.state());
            assertEquals(BuildTypes.BuildPart.ROOT, first.part());
            assertEquals(null, first.metadata());
            int before = fixture.reads.get();
            assertEquals(
                    first.state(), opener.metadata(fixture.ref, Cancellation.NONE).join().state());
            assertTrue(
                    fixture.reads.get() > before,
                    "Failed metadata must not be retained as successful context");
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void canceledMetadataWaiterDoesNotCancelSurvivingFullOpen() throws Exception {
        var fixture = new SyntheticBuild();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        ObjectSource source =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getKey")
                                            && ((SourceTypes.ObjectRequest) args[0])
                                                    .key()
                                                    .equals(fixture.encodingKey)) {
                                        entered.countDown();
                                        return release.thenCompose(
                                                ignored ->
                                                        fixture.getKey(
                                                                (SourceTypes.ObjectRequest) args[0],
                                                                (SourceTypes.CdnContext) args[1],
                                                                (SourceTypes.FetchPriority) args[2],
                                                                (Cancellation) args[3]));
                                    }
                                    return invoke(fixture, method, args);
                                });
        var opener = new BuildOpener(source);
        var firstToken = new Cancellation();
        try {
            var first = opener.metadata(fixture.ref, firstToken);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = opener.metadata(fixture.ref, Cancellation.NONE);
            firstToken.cancel();
            assertThrows(java.util.concurrent.CompletionException.class, first::join);
            var full = opener.open(fixture.ref, Cancellation.NONE);
            release.complete(null);
            assertEquals(BuildOpenState.OPEN, second.join().state());
            assertEquals(BuildOpenState.OPEN, full.join().state());
        } finally {
            release.complete(null);
            opener.closeAsync().join();
        }
    }

    @Test
    void closeDrainsMetadataAndRejectsNewCalls() throws Exception {
        var fixture = new SyntheticBuild();
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        ObjectSource source =
                proxy(
                        fixture,
                        (method, args) -> {
                            if (method.equals("getKey")
                                    && ((SourceTypes.ObjectRequest) args[0])
                                            .key()
                                            .equals(fixture.encodingKey)) {
                                entered.countDown();
                                release.join();
                            }
                        });
        var opener = new BuildOpener(source);
        var result = opener.metadata(fixture.ref, Cancellation.NONE);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var observer = opener.closeAsync();
        observer.cancel(false);
        var close = opener.closeAsync();
        try {
            assertFalse(close.isDone());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> opener.metadata(fixture.ref, Cancellation.NONE).join());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> opener.open(fixture.ref, Cancellation.NONE).join());
        } finally {
            release.complete(null);
        }
        assertEquals(BuildOpenState.OPEN, result.join().state());
        close.get(5, TimeUnit.SECONDS);
    }

    @Test
    void defaultMetadataFallbackPreservesExistingBuildProviders() {
        var fixture = new SyntheticBuild();
        var opener = new BuildOpener(fixture);
        Builds legacy = (build, token) -> opener.open(build, token);
        try {
            assertNotNull(legacy.metadata(fixture.ref, Cancellation.NONE).join().metadata());
        } finally {
            opener.closeAsync().join();
        }
    }

    @FunctionalInterface
    private interface Probe {
        void check(String method, Object[] args);
    }

    private static ObjectSource proxy(ObjectSource source, Probe probe) {
        return (ObjectSource)
                Proxy.newProxyInstance(
                        ObjectSource.class.getClassLoader(),
                        new Class<?>[] {ObjectSource.class},
                        (proxy, method, args) -> {
                            probe.check(method.getName(), args);
                            return invoke(source, method, args);
                        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
