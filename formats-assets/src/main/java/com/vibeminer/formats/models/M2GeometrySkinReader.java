package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Types.ArrayDeclaration;
import com.vibeminer.formats.models.M2Types.GeometrySkin;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Model;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class M2GeometrySkinReader {
    public static final String REVISION = "m2-skin-geometry-v1";

    private M2GeometrySkinReader() {}

    public static CompletableFuture<GeometrySkin> read(
            AssetSource source, Model model, Limits limits, Cancellation token) {
        Objects.requireNonNull(model);
        return AssetTasks.run(
                () -> {
                    token.check();
                    String refusal = profileRefusal(model);
                    if (refusal != null) {
                        throw M2Input.error("UNSUPPORTED", refusal);
                    }
                    Limits actual = limits == null ? model.limits() : limits;
                    var core = M2SkinReader.core(source, model, actual, false, token);
                    core.input().finish();
                    return new GeometrySkin(
                            model,
                            core.lookup(),
                            core.triangles(),
                            core.properties(),
                            core.sections(),
                            core.maximumBones(),
                            core.input().length,
                            core.headerBytes(),
                            new ArrayDeclaration(
                                    "uninterpreted SKIN batches",
                                    core.batch().count(),
                                    core.batch().offset(),
                                    core.batch().stride()),
                            new ArrayDeclaration(
                                    "uninterpreted SKIN shadow batches",
                                    core.shadow().count(),
                                    core.shadow().offset(),
                                    core.shadow().stride()),
                            core.retainedBytes(),
                            actual);
                });
    }

    public static String profileRefusal(Model model) {
        Objects.requireNonNull(model);
        if (!model.isChunked() || model.version() != 272 && model.version() != 274) {
            return "Geometry needs a chunked MD21 model of version272 or274; this one is "
                    + (model.isChunked() ? "chunked" : "unchunked")
                    + " version "
                    + model.version()
                    + ".";
        }
        if (model.skeletonFileDataId() != 0) {
            return "The model keeps its bones in an external skeleton file (SKID "
                    + model.skeletonFileDataId()
                    + "), which geometry does not read.";
        }
        if (model.boneFileDataIds().stream().anyMatch(id -> id != 0)) {
            return "The model declares external bone files (BFID), which geometry does not read.";
        }
        return null;
    }
}
