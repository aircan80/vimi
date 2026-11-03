package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.i16;
import static com.vibeminer.formats.models.M2Input.i32;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;
import static com.vibeminer.formats.models.M2Input.v3;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Bounded raw own-track absence facts; does not evaluate a rig or ancestor animation. */
public final class M2BindPoseFacts {
    public static final String REVISION = "m2-bind-own-track-facts-v1";
    private static final int MAX_BONES = 4096;

    private M2BindPoseFacts() {}

    public static final class Facts {
        private final M2Types.Model model;
        private final List<Boolean> absentOwnTracks;

        private Facts(M2Types.Model model, List<Boolean> absentOwnTracks) {
            this.model = model;
            this.absentOwnTracks = List.copyOf(absentOwnTracks);
        }

        public M2Types.Model model() {
            return model;
        }

        /** True proves absence in the reviewed profile; false means absence is not established. */
        public List<Boolean> absentOwnTracks() {
            return absentOwnTracks;
        }
    }

    public static CompletableFuture<Facts> read(
            AssetSource source, M2Types.Model model, Cancellation token) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(token);
        return AssetTasks.run(() -> readCore(source, model, token));
    }

    private static Facts readCore(AssetSource source, M2Types.Model model, Cancellation token) {
        token.check();
        if (!model.isChunked()
                || model.version() != 272 && model.version() != 274
                || model.skeletonFileDataId() != 0
                || model.boneFileDataIds().stream().anyMatch(id -> id != 0)) {
            throw error("UNSUPPORTED", "Bind-pose facts require local chunked272/274 bones.");
        }
        var input = new M2Input(source, model.limits(), token);
        if (input.length != model.sourceBytes()) {
            throw error("INVALID", "Bone-fact source differs from the parsed model.");
        }
        var header = input.at(model.baseOffset(), 52);
        if (i32(header, 0) != 0x3032444d
                || u32(header, 4) != model.version()
                || u32(header, 16) != model.flags()) {
            throw error("INVALID", "Bone-fact source header differs from the parsed model.");
        }
        var declarations =
                model.declarations().stream().filter(d -> d.name().equals("bones")).toList();
        if (declarations.size() != 1) {
            throw error("INVALID", "Bone-fact declaration is missing or ambiguous.");
        }
        var bones = declarations.getFirst();
        if (bones.stride() != 88
                || bones.count() != model.bones().size()
                || u32(header, 44) != bones.count()
                || u32(header, 48) != bones.offset()) {
            throw error("INVALID", "Bone-fact declaration differs from parsed bones.");
        }
        var absent = new ArrayList<Boolean>();
        var span =
                input.span(
                        "bones",
                        bones.count(),
                        bones.offset(),
                        88,
                        MAX_BONES,
                        model.baseOffset(),
                        model.baseBytes(),
                        (model.flags() & 8) == 0 ? 304 : 312);
        input.records(
                span,
                (row, index) -> {
                    var bone = model.bones().get(index);
                    if (bone.keyBoneId() != i32(row, 0)
                            || bone.flags() != u32(row, 4)
                            || bone.parent() != i16(row, 8)
                            || bone.submeshId() != u16(row, 10)
                            || !bone.pivot().equals(v3(row, 76))) {
                        throw error("INVALID", "Bone-fact row differs from parsed bone metadata.");
                    }
                    boolean empty = true;
                    for (int offset : new int[] {16, 36, 56}) {
                        empty &=
                                u16(row, offset) == 0
                                        && i16(row, offset + 2) == -1
                                        && u32(row, offset + 4) == 0
                                        && u32(row, offset + 12) == 0;
                    }
                    absent.add(empty);
                });
        input.finish();
        return new Facts(model, absent);
    }
}
