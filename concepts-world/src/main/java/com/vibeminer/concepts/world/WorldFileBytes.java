package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;

/** Shared bounded source read; identity-bound navigation additionally rechecks its receipt. */
final class WorldFileBytes {
    private WorldFileBytes() {}

    record Source(String contentKey, byte[] bytes) {}

    static FileTypes.FileRef reference(WorldService.MapInfo map, WorldFormats.Tile tile) {
        if (tile.rootId() > 0) {
            return FileTypes.FileRef.id(tile.rootId());
        }
        if (map.directory() != null) {
            String directory = map.directory();
            return FileTypes.FileRef.forPath(
                    "world/maps/"
                            + directory
                            + "/"
                            + directory
                            + "_"
                            + tile.x()
                            + "_"
                            + tile.y()
                            + ".adt");
        }
        throw new IllegalStateException("No terrain source reference");
    }

    static Source read(BuildFiles files, FileTypes.FileRef ref, Cancellation token, boolean bound) {
        if (files == null) {
            throw new IllegalStateException("Build files are unavailable in this workspace");
        }
        var stat = files.stat(ref, token).join();
        if (stat.file() == null
                || stat.file().size() != null && stat.file().size() > WorldFormats.MAX_FILE_BYTES) {
            throw new IllegalStateException("File absent or exceeds world preview limit");
        }
        String contentKey = stat.file().contentKey();
        if (bound && (contentKey == null || !contentKey.matches("[0-9a-fA-F]{32}"))) {
            throw new IllegalStateException("Terrain source has no verified content key");
        }
        var opened = files.openRead(ref, token).join();
        if (opened.stream() == null) {
            throw new IllegalStateException("File is " + opened.state());
        }
        try (var stream = opened.stream()) {
            if (bound && opened.state() != FileTypes.FileReadState.COMPLETE) {
                throw new IllegalStateException("Terrain source is not complete");
            }
            if (!stream.holes().isEmpty()) {
                throw new IllegalStateException("File has protected sections");
            }
            if (stream.length() < 12 || stream.length() > WorldFormats.MAX_FILE_BYTES) {
                throw new IllegalStateException("World file size outside bounds");
            }
            var target = ByteBuffer.allocate((int) stream.length());
            while (target.hasRemaining()) {
                token.check();
                if (stream.read(target, token).join() <= 0) {
                    throw new IllegalStateException("Truncated world file");
                }
            }
            if (bound) {
                var current = files.stat(ref, token).join();
                if (current.file() == null
                        || !contentKey.equals(current.file().contentKey())
                        || stat.file().fileDataId() != current.file().fileDataId()) {
                    throw new IllegalStateException("Terrain source changed during read");
                }
            }
            token.check();
            return new Source(contentKey, target.array());
        }
    }
}
