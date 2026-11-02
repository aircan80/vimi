package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FilesScenario;
import com.vibeminer.formats.images.BlpImageTypes.Cache;
import com.vibeminer.formats.images.BlpImageTypes.State;
import com.vibeminer.formats.models.BuildModelTypes.ReadState;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same format consumer contract against independent Files fake and real decoded BLTE streams. */
@Timeout(20)
final class AssetFilesConsumerContractTest {
    private static byte[] golden(String name) {
        try (var input =
                AssetFilesConsumerContractTest.class.getResourceAsStream("/golden/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imagesAndModelsConsumeTheSameFileContractIncludingRealStreamMonitor(boolean independent) {
        byte[] image = Blp2DecoderTest.build(3, 8, 0, 2, 2, new byte[16], new byte[] {1, 2, 3, 4});
        FilesScenario scenario = new FilesScenario(independent);
        scenario.add(1, "test.blp", image, true, List.of());
        scenario.add(
                2, "holed.blp", image, true, List.of(new FileHole(148, 16, "ABCDEF0123456789")));
        scenario.add(123, "test.m2", golden("model-274.m2"), true, List.of());
        scenario.add(900, "test.skin", golden("skin-274.skin"), true, List.of());
        scenario.add(700, "texture.blp", image, true, List.of());
        try (var files =
                scenario.files
                        .forBuild(FilesScenario.REF, "enUS", Cancellation.NONE)
                        .join()
                        .files()) {
            BlpImages images = new BlpImages(new MemoryGeneratedStore());
            var decoded = images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join();
            assertEquals(State.READY, decoded.state());
            assertArrayEquals(new byte[] {3, 2, 1, 4}, decoded.image().rgba());
            assertEquals(
                    Cache.HIT,
                    images.decode(files, FileRef.id(1), 1, Cancellation.NONE).join().cache());
            assertEquals(
                    State.READY,
                    images.inspect(files, FileRef.id(2), Cancellation.NONE).join().state());
            assertEquals(
                    State.READY,
                    images.decode(files, FileRef.id(2), 1, Cancellation.NONE)
                            .join()
                            .state()); // shared content hit is intentionally valid even for a
            // protected encoding.
            BlpImages fresh = new BlpImages(new MemoryGeneratedStore());
            assertEquals(
                    State.PROTECTED,
                    fresh.decode(files, FileRef.id(2), 0, Cancellation.NONE).join().state());
            assertEquals(
                    State.READY,
                    fresh.decode(files, FileRef.id(2), 1, Cancellation.NONE).join().state());
            BuildModels models = new BuildModels(files);
            var model = models.readModel(FileRef.id(123), Cancellation.NONE).join();
            assertEquals(ReadState.READ, model.state());
            assertEquals(
                    ReadState.READ, models.readSkin(model, 0, Cancellation.NONE).join().state());
            assertEquals(
                    ReadState.READ,
                    models.readGeometrySkin(model, 0, Cancellation.NONE).join().state());
        }
    }
}
