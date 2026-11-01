package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectState;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CachingObjectSourceTest extends ObjectSourceContracts {
    @Override
    protected AccessScenario create() {
        return AccessScenario.realMemory();
    }

    @Test
    void indexOnlyBatchPresencePreservesNullableMetadataAndRequestDeclaration() {
        try (var scenario = AccessScenario.realMemory()) {
            byte[] bytes = bytes("indexed config");
            var key = key(bytes);
            scenario.local(key, bytes);
            var unknown =
                    scenario.source
                            .getKeys(
                                    List.of(new ObjectRequest(key)),
                                    null,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join()
                            .getFirst();
            assertEquals(ObjectState.PRESENT, unknown.state());
            assertNull(unknown.length());
            assertNull(unknown.origin());
            assertNull(unknown.bytes());
            var declared =
                    scenario.source
                            .getKeys(
                                    List.of(new ObjectRequest(key, 123L)),
                                    null,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join()
                            .getFirst();
            assertEquals(123L, declared.length());
            assertNull(declared.origin());
            var measured =
                    scenario.source
                            .getKey(
                                    new ObjectRequest(key),
                                    null,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals((long) bytes.length, measured.length());
        }
    }
}
