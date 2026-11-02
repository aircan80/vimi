package com.vibeminer.formats.images;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileHoleException;
import com.vibeminer.files.FileReadException;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.files.FileTypes.FileHole;
import com.vibeminer.files.FileTypes.FileOpen;
import com.vibeminer.files.FileTypes.FileRead;
import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.files.FileTypes.FileRef;
import com.vibeminer.files.FileTypes.FileStat;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.images.Blp2Types.Image;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.Blp2Types.Mip;
import com.vibeminer.formats.images.Blp2Types.MipStorage;
import com.vibeminer.formats.images.BlpImageTypes.Cache;
import com.vibeminer.formats.images.BlpImageTypes.Header;
import com.vibeminer.formats.images.BlpImageTypes.Inspection;
import com.vibeminer.formats.images.BlpImageTypes.Result;
import com.vibeminer.formats.images.BlpImageTypes.State;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Layer5 image entry point. Shared/scoped generated identities preserve exact decoder and
 * key-status revisions.
 */
@SuppressWarnings("try")
public final class BlpImages {
    public static final String IDENTITY_FORMAT = "vibeminer-generated-image v1";
    public static final int HEADER_BYTES = 148;
    private final GeneratedStore store;
    private final Limits limits;
    private final Function<BuildRef, Function<String, String>> keys;
    private final String limitsHash;

    public BlpImages(GeneratedStore store) {
        this(store, new Limits(), null);
    }

    public BlpImages(
            GeneratedStore store,
            Limits limits,
            Function<BuildRef, Function<String, String>> keys) {
        this.store = Objects.requireNonNull(store);
        this.limits = limits == null ? new Limits() : limits;
        this.keys = keys == null ? build -> name -> null : keys;
        String json =
                "{\"MaxSourceBytes\":"
                        + this.limits.maxSourceBytes()
                        + ",\"MaxDimension\":"
                        + this.limits.maxDimension()
                        + ",\"MaxPixels\":"
                        + this.limits.maxPixels()
                        + ",\"MaxMipBytes\":"
                        + this.limits.maxMipBytes()
                        + ",\"MaxRgbaBytes\":"
                        + this.limits.maxRgbaBytes()
                        + "}";
        try {
            limitsHash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public Limits limits() {
        return limits;
    }

    public String limitsHash() {
        return limitsHash;
    }

    public String identityText(String contentKey, int mipLevel) {
        return String.join(
                "\n",
                IDENTITY_FORMAT,
                contentKey,
                Blp2Decoder.REVISION,
                "mip=" + mipLevel,
                "limits=" + limitsHash);
    }

    public static String scopedIdentityText(
            String identity,
            BuildRef build,
            List<String> chunkKeys,
            Function<String, String> status) {
        Objects.requireNonNull(build);
        Objects.requireNonNull(status);
        StringBuilder text =
                new StringBuilder(identity)
                        .append("\nencoding-in=")
                        .append(build.product())
                        .append(' ')
                        .append(build.buildConfig())
                        .append(' ')
                        .append(build.cdnConfig());
        for (String name : chunkKeys.stream().distinct().sorted().toList()) {
            if (!name.matches("[0-9A-F]{16}")) {
                throw new IllegalArgumentException("A TACT key name is16 upper-case hex digits");
            }
            String fingerprint = status.apply(name);
            if (fingerprint != null
                    && (fingerprint.isEmpty()
                            || fingerprint.length() > 128
                            || fingerprint.equals("?")
                            || fingerprint.chars().anyMatch(c -> c <= ' ' || c > '~'))) {
                throw new IllegalStateException("Invalid TACT key fingerprint");
            }
            text.append("\nkey ")
                    .append(name)
                    .append('=')
                    .append(fingerprint == null ? "?" : fingerprint);
        }
        return text.toString();
    }

    public CompletableFuture<Inspection> inspect(
            BuildFiles files, FileRef file, Cancellation token) {
        Objects.requireNonNull(files);
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileStat stat = files.stat(file, token).join();
                    Inspection failed = unlisted(stat);
                    return failed != null ? failed : inspectListed(files, stat.file(), token);
                });
    }

    public CompletableFuture<Result> decode(
            BuildFiles files, FileRef file, int mip, Cancellation token) {
        Objects.requireNonNull(files);
        if (mip < 0 || mip > 15) {
            throw new IllegalArgumentException("mip must be0..15");
        }
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileStat stat = files.stat(file, token).join();
                    Inspection failed = unlisted(stat);
                    return failed != null
                            ? Result.from(failed)
                            : decodeListed(files, stat.file(), mip, token);
                });
    }

    public CompletableFuture<Result> decodeLargestWithin(
            BuildFiles files, FileRef file, int maxSide, Cancellation token) {
        Objects.requireNonNull(files);
        if (maxSide < 1) {
            throw new IllegalArgumentException("maxSide must be positive");
        }
        return AssetTasks.run(
                () -> {
                    token.check();
                    FileStat stat = files.stat(file, token).join();
                    Inspection failed = unlisted(stat);
                    if (failed != null) {
                        return Result.from(failed);
                    }
                    Inspection inspection = inspectListed(files, stat.file(), token);
                    if (inspection.state() != State.READY) {
                        return Result.from(inspection);
                    }
                    int level = -1;
                    for (Mip mip : inspection.mips()) {
                        if (mip.width() <= maxSide
                                && mip.height() <= maxSide
                                && mip.storage() != MipStorage.COMPACT) {
                            level = mip.level();
                            break;
                        }
                    }
                    if (level < 0) {
                        return Result.from(
                                refused(
                                        null,
                                        new BlpFormatException(
                                                "BLP_MIP_UNAVAILABLE",
                                                "No declared mip fits requested size; no level resized or substituted.")));
                    }
                    return decodeListed(files, stat.file(), level, token);
                });
    }

    private Inspection inspectListed(BuildFiles files, BuildFile listed, Cancellation token) {
        Long size = listed.size();
        if (size == null) {
            return fileFailed(
                    FileReadState.CORRUPT,
                    "FILES_NO_DECLARED_SIZE",
                    "ENCODING lists file without decoded size.",
                    null);
        }
        if (size < HEADER_BYTES) {
            return refused(
                    null, new BlpFormatException("BLP_INVALID", "BLP2 core header is truncated."));
        }
        FileRead read =
                files.readRange(FileRef.id(listed.fileDataId()), 0, HEADER_BYTES, token).join();
        if (read.state() == FileReadState.PARTIAL || read.state() == FileReadState.PROTECTED) {
            return protectedResult(read.holes().stream().map(FileHole::keyName).toList());
        }
        if (read.state() != FileReadState.COMPLETE) {
            return fileFailed(read.state(), read.code(), read.message(), read.observedAt());
        }
        byte[] header = read.bytes();
        if (header.length < HEADER_BYTES) {
            return refused(
                    null, new BlpFormatException("BLP_INVALID", "BLP2 core header is truncated."));
        }
        Header facts = facts(header);
        AssetSource view = headerView(header, size);
        try {
            List<Mip> mips = Blp2Decoder.readMips(view, limits, token).join();
            return new Inspection(
                    State.READY,
                    facts,
                    Blp2Decoder.profile(
                            facts.colorEncoding(), facts.alphaDepth(), facts.alphaEncoding()),
                    mips,
                    List.of(),
                    null,
                    null,
                    null,
                    null);
        } catch (RuntimeException e) {
            Throwable cause = AssetTasks.unwrap(e);
            if (cause instanceof BlpFormatException refused) {
                return refused(facts, refused);
            }
            throw e;
        }
    }

    private Result decodeListed(BuildFiles files, BuildFile listed, int level, Cancellation token) {
        var generatedToken = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(generatedToken::cancel)) {
            GeneratedKey shared = keyOf(identityText(listed.contentKey(), level));
            Image hit = cached(shared, level, generatedToken);
            if (hit != null) {
                return Result.decoded(hit, Cache.HIT);
            }
            FileOpen open = files.openRead(FileRef.id(listed.fileDataId()), token).join();
            if (open.stream() == null) {
                return Result.from(
                        fileFailed(open.state(), open.code(), open.message(), open.observedAt()));
            }
            try (FileReadStream stream = open.stream()) {
                GeneratedKey key = shared;
                if (!stream.chunkKeys().isEmpty()) {
                    key =
                            keyOf(
                                    scopedIdentityText(
                                            identityText(listed.contentKey(), level),
                                            files.build(),
                                            stream.chunkKeys(),
                                            keys.apply(files.build())));
                    hit = cached(key, level, generatedToken);
                    if (hit != null) {
                        return Result.decoded(hit, Cache.HIT);
                    }
                }
                Image image;
                try {
                    image =
                            Blp2Decoder.decode(AssetSources.file(stream), level, limits, token)
                                    .join();
                } catch (RuntimeException e) {
                    Throwable cause = AssetTasks.unwrap(e);
                    if (cause instanceof FileHoleException hole) {
                        try {
                            return Result.from(
                                    protectedResult(
                                            neededHoleKeys(stream, level, hole.hole(), token)));
                        } catch (RuntimeException failed) {
                            Throwable actual = AssetTasks.unwrap(failed);
                            if (actual instanceof FileReadException readFailed) {
                                return Result.from(
                                        fileFailed(
                                                readFailed.state(),
                                                readFailed.code(),
                                                readFailed.getMessage(),
                                                readFailed.observedAt()));
                            }
                            throw failed;
                        }
                    }
                    if (cause instanceof BlpFormatException refused) {
                        return Result.from(refused(null, refused));
                    }
                    if (cause instanceof FileReadException failed) {
                        return Result.from(
                                fileFailed(
                                        failed.state(),
                                        failed.code(),
                                        failed.getMessage(),
                                        failed.observedAt()));
                    }
                    throw e;
                }
                return Result.decoded(image, store(key, image, generatedToken));
            }
        }
    }

    private Image cached(GeneratedKey key, int level, com.vibeminer.generated.Cancellation token) {
        var read = store.read(key, token).join();
        if (read.state() == GeneratedTypes.ReadState.ABSENT) {
            return null;
        }
        if (read.state() == GeneratedTypes.ReadState.PRESENT) {
            Image image = BlpImagePayload.read(read.bytes(), level, limits);
            if (image != null) {
                return image;
            }
        }
        store.delete(List.of(key), token).join();
        return null;
    }

    private Cache store(GeneratedKey key, Image image, com.vibeminer.generated.Cancellation token) {
        if (BlpImagePayload.size(image) > GeneratedTypes.MAX_OBJECT_BYTES) {
            return Cache.TOO_LARGE;
        }
        try {
            store.put(List.of(new GeneratedTypes.Put(key, BlpImagePayload.write(image))), token)
                    .join();
            return Cache.STORED;
        } catch (RuntimeException e) {
            if (AssetTasks.unwrap(e) instanceof GeneratedStoreException) {
                return Cache.STORE_FAILED;
            }
            throw e;
        }
    }

    private static List<String> neededHoleKeys(
            FileReadStream stream, int level, FileHole hit, Cancellation token) {
        List<long[]> needed = new ArrayList<>();
        needed.add(new long[] {0, HEADER_BYTES});
        if (!overlaps(stream.holes(), 0, HEADER_BYTES)) {
            byte[] header;
            try {
                header = AssetSources.file(stream).readAt(0, HEADER_BYTES, token).join();
            } catch (RuntimeException e) {
                if (AssetTasks.unwrap(e) instanceof FileHoleException) {
                    return List.of(hit.keyName());
                }
                throw e;
            }
            if (header[8] == 1) {
                needed.add(new long[] {HEADER_BYTES, HEADER_BYTES + 1024});
            }
            long offset = Blp2Decoder.u(header, 20 + level * 4),
                    length = Blp2Decoder.u(header, 84 + level * 4);
            needed.add(new long[] {offset, offset + length});
        }
        List<String> names =
                stream.holes().stream()
                        .filter(
                                h ->
                                        needed.stream()
                                                .anyMatch(
                                                        r ->
                                                                h.offset() < r[1]
                                                                        && r[0]
                                                                                < h.offset()
                                                                                        + h
                                                                                                .length()))
                        .map(FileHole::keyName)
                        .distinct()
                        .sorted()
                        .toList();
        return names.isEmpty() ? List.of(hit.keyName()) : names;
    }

    private static boolean overlaps(List<FileHole> holes, long from, long to) {
        return holes.stream().anyMatch(h -> h.offset() < to && from < h.offset() + h.length());
    }

    private static GeneratedKey keyOf(String identity) {
        return GeneratedKey.hash(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static Inspection unlisted(FileStat stat) {
        return switch (stat.state()) {
            case FOUND -> null;
            case NOT_IN_LOCALE ->
                    fileFailed(
                            FileReadState.NOT_IN_LOCALE,
                            null,
                            "Build lists file but not for this locale.",
                            null);
            case NOT_IN_ENCODING ->
                    fileFailed(
                            FileReadState.NOT_IN_ENCODING,
                            null,
                            "Build has no encoded copy of file.",
                            null);
            default ->
                    fileFailed(FileReadState.UNKNOWN_FILE, null, "Build does not list file.", null);
        };
    }

    private static Inspection fileFailed(
            FileReadState state, String code, String message, Instant time) {
        return new Inspection(
                State.FILE_FAILED, null, null, null, List.of(), state, code, message, time);
    }

    private static Inspection protectedResult(List<String> names) {
        return new Inspection(
                State.PROTECTED,
                null,
                null,
                null,
                names.stream().distinct().sorted().toList(),
                null,
                null,
                "Bytes this image needs are encrypted with TACT keys that are not known.",
                null);
    }

    private static Inspection refused(Header header, BlpFormatException refused) {
        State state =
                switch (refused.code()) {
                    case "BLP_UNSUPPORTED" -> State.UNSUPPORTED;
                    case "BLP_MIP_UNAVAILABLE" -> State.MIP_UNAVAILABLE;
                    case "BLP_LIMIT" -> State.LIMIT_EXCEEDED;
                    default -> State.INVALID;
                };
        return new Inspection(
                state,
                header,
                null,
                null,
                List.of(),
                null,
                refused.code(),
                refused.getMessage(),
                null);
    }

    private static Header facts(byte[] header) {
        if (Blp2Decoder.u(header, 0) != 0x32504c42L || Blp2Decoder.u(header, 4) != 1) {
            return null;
        }
        int declared = 0;
        while (declared < 16
                && Blp2Decoder.u(header, 20 + declared * 4) != 0
                && Blp2Decoder.u(header, 84 + declared * 4) != 0) {
            declared++;
        }
        return new Header(
                header[8] & 255,
                header[9] & 255,
                header[10] & 255,
                header[11] & 255,
                Blp2Decoder.u(header, 12),
                Blp2Decoder.u(header, 16),
                declared);
    }

    private static AssetSource headerView(byte[] header, long length) {
        return new AssetSource() {
            public long length() {
                return length;
            }

            public CompletableFuture<byte[]> readAt(long offset, int count, Cancellation token) {
                token.check();
                if (offset < 0 || count < 0 || offset > header.length - count) {
                    throw new IllegalStateException("Only header was read");
                }
                return CompletableFuture.completedFuture(
                        Arrays.copyOfRange(header, (int) offset, (int) offset + count));
            }
        };
    }
}
