package com.vibeminer.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.files.FileTypes.EnsureOutcome;
import com.vibeminer.files.FileTypes.EnsurePriority;
import com.vibeminer.files.FileTypes.EnsureSelection;
import com.vibeminer.files.FileTypes.FileEnsureState;
import com.vibeminer.files.FileTypes.FileRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EnsureCancellationTest {
    @Test
    void canceledSuccessfulFetchReconcilesCommittedKeysAndReturnsCanceled() {
        var token = new Cancellation();
        var s =
                new FilesScenario(
                                false,
                                FileSystemOptions.defaults(),
                                source ->
                                        (ObjectSource)
                                                Proxy.newProxyInstance(
                                                        ObjectSource.class.getClassLoader(),
                                                        new Class<?>[] {ObjectSource.class},
                                                        (proxy, method, args) -> {
                                                            try {
                                                                Object answer =
                                                                        method.invoke(source, args);
                                                                if (method.getName()
                                                                        .equals("getKeys")) {
                                                                    token.cancel();
                                                                }
                                                                return answer;
                                                            } catch (InvocationTargetException e) {
                                                                throw e.getCause();
                                                            }
                                                        }))
                        .add(1, null, new byte[] {1, 2, 3}, false, List.of());
        try (var h =
                s.files.forBuild(FilesScenario.REF, "enUS", Cancellation.NONE).join().files()) {
            var result =
                    h.ensure(
                                    EnsureSelection.one(FileRef.id(1)),
                                    EnsurePriority.BACKGROUND,
                                    null,
                                    token)
                            .join();
            assertEquals(EnsureOutcome.CANCELED, result.outcome());
            assertEquals(FileEnsureState.STORED, result.files().getFirst().state());
            assertEquals(1, result.progress().filesDone());
        }
    }
}
