package com.vibeminer.formats.images;

import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.formats.images.Blp2Types.Encoding;
import com.vibeminer.formats.images.Blp2Types.Image;
import com.vibeminer.formats.images.Blp2Types.Mip;
import java.time.Instant;
import java.util.List;

public final class BlpImageTypes {
    private BlpImageTypes() {}

    public enum State {
        READY,
        UNSUPPORTED,
        INVALID,
        MIP_UNAVAILABLE,
        LIMIT_EXCEEDED,
        PROTECTED,
        FILE_FAILED
    }

    public enum Cache {
        NONE,
        HIT,
        STORED,
        TOO_LARGE,
        STORE_FAILED
    }

    public record Header(
            int colorEncoding,
            int alphaDepth,
            int alphaEncoding,
            int mipFlags,
            long width,
            long height,
            int declaredMips) {}

    public record Inspection(
            State state,
            Header header,
            Encoding encoding,
            List<Mip> mips,
            List<String> holeKeys,
            FileReadState fileState,
            String code,
            String message,
            Instant observedAt) {
        public Inspection {
            mips = mips == null ? null : List.copyOf(mips);
            holeKeys = List.copyOf(holeKeys);
        }
    }

    public record Result(
            State state,
            Image image,
            Cache cache,
            List<String> holeKeys,
            FileReadState fileState,
            String code,
            String message,
            Instant observedAt) {
        public Result {
            holeKeys = List.copyOf(holeKeys);
        }

        static Result decoded(Image image, Cache cache) {
            return new Result(State.READY, image, cache, List.of(), null, null, null, null);
        }

        static Result from(Inspection failed) {
            return new Result(
                    failed.state(),
                    null,
                    Cache.NONE,
                    failed.holeKeys(),
                    failed.fileState(),
                    failed.code(),
                    failed.message(),
                    failed.observedAt());
        }
    }
}
