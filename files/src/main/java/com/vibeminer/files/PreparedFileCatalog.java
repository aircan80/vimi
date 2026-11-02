package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.RecordFile;
import com.vibeminer.generated.RecordFileWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Durable locale-scoped immutable file facts. Names, inferred types and local presence are live.
 */
public final class PreparedFileCatalog {
    public static final String FORMAT = "vimi-prepared-files-v1";
    private final GeneratedStore store;

    public PreparedFileCatalog(GeneratedStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public record Identity(
            BuildTypes.BuildRef build, String locale, BuildTypes.BuildMetadata metadata) {
        public Identity {
            Objects.requireNonNull(build);
            Objects.requireNonNull(metadata);
            FileLocales.bitOf(locale);
            if (!metadata.locales().contains(locale)) {
                throw new IllegalArgumentException("Locale is not in this build");
            }
        }
    }

    public record Catalog(RecordFile records, long files, long unknownSize, boolean restored) {}

    public record Entry(
            long fileDataId,
            Long decodedSize,
            String contentKey,
            String encodedKey,
            Long encodedSize,
            long localeFlags,
            long contentFlags) {}

    public static GeneratedKey key(Identity identity) {
        return GeneratedKey.hash(identityBytes(identity));
    }

    public CompletableFuture<Catalog> restore(Identity identity, Cancellation token) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var generated = new Token(token)) {
                        token.check();
                        var opened = RecordFile.open(store, key(identity), generated.value).join();
                        if (opened.state() == GeneratedTypes.ReadState.ABSENT) {
                            return null;
                        }
                        if (opened.state() != GeneratedTypes.ReadState.PRESENT
                                || opened.file() == null) {
                            store.delete(List.of(key(identity)), generated.value).join();
                            return null;
                        }
                        var records = opened.file();
                        long unknown;
                        try (var input =
                                new DataInputStream(new ByteArrayInputStream(records.metadata()))) {
                            int length = input.readInt();
                            byte[] expected = identityBytes(identity);
                            if (length != expected.length
                                    || !Arrays.equals(input.readNBytes(length), expected)) {
                                throw new IOException("Catalog identity differs");
                            }
                            unknown = input.readLong();
                            if (unknown < 0 || unknown > records.count() || input.read() != -1) {
                                throw new IOException("Invalid catalog counters");
                            }
                        } catch (IOException invalid) {
                            store.delete(List.of(key(identity)), generated.value).join();
                            return null;
                        }
                        // Validate every stored segment's integrity without enumerating files or
                        // probing CDN presence.
                        for (var segment : records.keys()) {
                            token.check();
                            var read = store.read(segment, generated.value).join();
                            if (read.state() != GeneratedTypes.ReadState.PRESENT) {
                                store.delete(
                                                List.of(segment, key(identity)).stream()
                                                        .distinct()
                                                        .toList(),
                                                generated.value)
                                        .join();
                                return null;
                            }
                        }
                        return new Catalog(records, records.count(), unknown, true);
                    }
                },
                command -> Thread.startVirtualThread(command));
    }

    public CompletableFuture<Catalog> prepare(
            com.vibeminer.builds.Build build,
            Identity identity,
            long total,
            BiConsumer<Long, Long> progress,
            Cancellation token) {
        if (!build.build().equals(identity.build())
                || !build.metadata().equals(identity.metadata())) {
            throw new IllegalArgumentException("Build differs from catalog identity");
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var generated = new Token(token);
                            var writer = new RecordFileWriter(store)) {
                        long count = 0, unknown = 0;
                        long locale = FileLocales.bitOf(identity.locale());
                        for (long id : build.fileDataIds(identity.locale())) {
                            token.check();
                            var variant =
                                    BuildFilesHandle.pick(build.resolve(id).variants(), locale);
                            if (variant == null) {
                                throw new IllegalStateException(
                                        "Locale catalog contains an unresolved ID");
                            }
                            writer.add(id, encode(variant), generated.value).join();
                            count++;
                            if (variant.decodedSize() == null) {
                                unknown++;
                            }
                            if (count % 1000 == 0) {
                                progress.accept(count, Math.max(total, count));
                            }
                        }
                        token.check();
                        writer.complete(key(identity), metadata(identity, unknown), generated.value)
                                .join();
                        var opened = RecordFile.open(store, key(identity), generated.value).join();
                        if (opened.file() == null) {
                            throw new IllegalStateException(
                                    "Prepared file catalog was not published");
                        }
                        progress.accept(count, Math.max(total, count));
                        return new Catalog(opened.file(), count, unknown, false);
                    }
                },
                command -> Thread.startVirtualThread(command));
    }

    public CompletableFuture<List<Entry>> page(
            Catalog catalog, long first, int count, Cancellation token) {
        var generated = new Token(token);
        return catalog.records()
                .read(first, count, generated.value)
                .thenApply(
                        records ->
                                records.stream()
                                        .map(record -> decode(record.id(), record.bytes()))
                                        .toList())
                .whenComplete((value, error) -> generated.close());
    }

    private static byte[] encode(BuildTypes.FileVariant variant) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeLong(variant.decodedSize() == null ? -1 : variant.decodedSize());
                out.writeUTF(variant.contentKey().hex());
                out.writeUTF(variant.encodedKey() == null ? "" : variant.encodedKey().hex());
                out.writeLong(variant.encodedSize() == null ? -1 : variant.encodedSize());
                out.writeLong(variant.localeFlags());
                out.writeLong(variant.contentFlags());
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Entry decode(long id, byte[] bytes) {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            long size = in.readLong();
            String content = in.readUTF();
            String encoded = in.readUTF();
            long encodedSize = in.readLong();
            long locales = in.readLong();
            long flags = in.readLong();
            if (size < -1 || encodedSize < -1 || in.read() != -1) {
                throw new IOException("Invalid prepared file record");
            }
            return new Entry(
                    id,
                    size == -1 ? null : size,
                    content,
                    encoded.isEmpty() ? null : encoded,
                    encodedSize == -1 ? null : encodedSize,
                    locales,
                    flags);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] identityBytes(Identity identity) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF(FORMAT);
                out.writeUTF(identity.build().product());
                out.writeUTF(identity.build().buildConfig());
                out.writeUTF(identity.build().cdnConfig());
                out.writeUTF(identity.build().region() == null ? "" : identity.build().region());
                out.writeUTF(identity.locale());
                out.writeUTF(identity.metadata().rootContentKey().hex());
                out.writeUTF(identity.metadata().encodingContentKey().hex());
                out.writeUTF(identity.metadata().encodingEncodedKey().hex());
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] metadata(Identity identity, long unknown) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                byte[] fingerprint = identityBytes(identity);
                out.writeInt(fingerprint.length);
                out.write(fingerprint);
                out.writeLong(unknown);
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static final class Token implements AutoCloseable {
        final com.vibeminer.generated.Cancellation value =
                new com.vibeminer.generated.Cancellation();
        private final Cancellation.Registration registration;

        Token(Cancellation token) {
            registration = token.onCancel(value::cancel);
        }

        public void close() {
            registration.close();
        }
    }
}
