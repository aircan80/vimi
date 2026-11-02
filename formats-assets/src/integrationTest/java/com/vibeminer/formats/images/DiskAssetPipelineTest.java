package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileSystemOptions;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FilesScenario;
import com.vibeminer.formats.images.BlpImageTypes.Cache;
import com.vibeminer.formats.images.BlpImageTypes.State;
import com.vibeminer.generated.DiskGeneratedStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class DiskAssetPipelineTest {
    @TempDir Path temporary;

    @Test
    void imageFlowsFromDiskObjectThroughFilesAndReopensFromDerivedCache() throws Exception {
        byte[] input;
        try (var resource = getClass().getResourceAsStream("/golden/source.blp")) {
            input = java.util.Objects.requireNonNull(resource).readAllBytes();
        }
        byte[] expected = null;
        for (int round = 0; round < 2; round++) {
            ObjectStore objects = ObjectStore.open(temporary.resolve("objects")).join();
            DiskGeneratedStore generated =
                    DiskGeneratedStore.open(temporary.resolve("generated")).join();
            CachingObjectSource access = CachingObjectSource.localOnly(objects, objects);
            try {
                FilesScenario scenario =
                        new FilesScenario(false, FileSystemOptions.defaults(), ignored -> access)
                                .add(1, "test.blp", input, false, List.of());
                var encoded = scenario.encoded.get(1L);
                ObjectKey key = new ObjectKey(ObjectKey.ENCODED, encoded.key().bytes());
                if (round == 0) {
                    objects.put(List.of(new PutObject(key, encoded.bytes()))).join();
                } else {
                    objects.delete(List.of(key)).join();
                }
                try (var files =
                        scenario.files
                                .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                                .join()
                                .files()) {
                    var image =
                            new BlpImages(generated)
                                    .decode(files, FileRef.id(1), 0, Cancellation.NONE)
                                    .join();
                    assertEquals(State.READY, image.state(), image.message());
                    assertEquals(round == 0 ? Cache.STORED : Cache.HIT, image.cache());
                    if (round == 0) {
                        expected = image.image().rgba();
                    } else {
                        assertArrayEquals(expected, image.image().rgba());
                    }
                }
            } finally {
                access.closeAsync().join();
                generated.closeAsync().join();
                objects.closeAsync().join();
            }
        }
    }
}
