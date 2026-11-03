package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Read validated native positions/triangles without evaluating external rig or draw visibility. */
public final class M2RestSkinReader {
    public static final String REVISION = "m2-rest-surface-v1";

    private M2RestSkinReader() {}

    public static CompletableFuture<M2Types.RestSkin> read(
            AssetSource source, M2Types.Model model, M2Types.Limits limits, Cancellation token) {
        Objects.requireNonNull(model);
        return AssetTasks.run(
                () -> {
                    token.check();
                    if (!model.isChunked() || model.version() != 272 && model.version() != 274) {
                        throw M2Input.error(
                                "UNSUPPORTED",
                                "Rest surface needs a chunked MD21 model of version272 or274");
                    }
                    var core =
                            M2SkinReader.core(
                                    source,
                                    model,
                                    limits == null ? model.limits() : limits,
                                    false,
                                    false,
                                    token);
                    core.input().finish();
                    return new M2Types.RestSkin(
                            model, core.lookup(), core.triangles(), core.sections());
                });
    }
}
