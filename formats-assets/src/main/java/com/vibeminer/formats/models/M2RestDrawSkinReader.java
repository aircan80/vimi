package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Raw bounded draw references, without evaluating external skeletons, tracks or material shaders.
 */
public final class M2RestDrawSkinReader {
    public static final String REVISION = "m2-rest-draw-v1";

    private M2RestDrawSkinReader() {}

    public static CompletableFuture<M2Types.RestDrawSkin> read(
            AssetSource source, M2Types.Model model, M2Types.Limits limits, Cancellation token) {
        Objects.requireNonNull(model);
        return AssetTasks.run(
                () -> {
                    token.check();
                    if (!model.isChunked() || model.version() != 272 && model.version() != 274) {
                        throw M2Input.error(
                                "UNSUPPORTED",
                                "Rest draw declarations require a chunked MD21 model of version272 or274");
                    }
                    var skin =
                            M2SkinReader.readCore(
                                    source,
                                    model,
                                    limits == null ? model.limits() : limits,
                                    false,
                                    token);
                    return new M2Types.RestDrawSkin(
                            new M2Types.RestSkin(
                                    model,
                                    skin.vertexLookup(),
                                    skin.triangleLookup(),
                                    skin.sections(),
                                    skin.boneProperties()),
                            skin.batches());
                });
    }
}
