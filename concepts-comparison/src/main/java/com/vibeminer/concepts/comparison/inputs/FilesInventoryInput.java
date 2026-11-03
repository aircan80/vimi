package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Borrowed metadata-only listing. Exhaustion does not certify selected-locale membership. */
public final class FilesInventoryInput extends SerialInventoryInput {
    public static final String NAMESPACE = "files.FileDataID.v1";
    public static final String SCOPE = "selected-locale-root-file-inventory-v1";
    private String source = "BuildFiles.listMetadata";
    private final BuildFiles files;
    private final String pinnedNames;
    private AsyncCursor<FileTypes.BuildFile> cursor;
    private AsyncCursor<FileTypes.ContentFile> contentCursor;
    private boolean initialized;
    private long previous = -1;

    /** A supplied name identity certifies that the caller injected a frozen name publication. */
    public FilesInventoryInput(
            BuildFiles files, ComparisonTypes.Descriptor descriptor, String pinnedNameIdentity) {
        super(checked(files, descriptor, pinnedNameIdentity));
        this.files = files;
        pinnedNames = pinnedNameIdentity;
    }

    private static ComparisonTypes.Descriptor checked(
            BuildFiles files, ComparisonTypes.Descriptor descriptor, String names) {
        Objects.requireNonNull(files);
        Objects.requireNonNull(descriptor);
        if (!files.build().product().equals(descriptor.product())
                || !files.locale().equals(descriptor.locale())
                || !NAMESPACE.equals(descriptor.namespace())
                || !SCOPE.equals(descriptor.scope())
                || names != null && names.isBlank()) {
            throw new IllegalArgumentException(
                    "Files inventory descriptor or pinned names differ from the borrowed context");
        }
        var dependencies = new LinkedHashMap<>(descriptor.dependencies());
        pin(dependencies, "inventory.adapter", "files-content-inventory-v2");
        pin(dependencies, "files.buildConfig", files.build().buildConfig());
        pin(dependencies, "files.cdnConfig", files.build().cdnConfig());
        pin(dependencies, "files.region", files.build().region());
        pin(dependencies, "files.names", names == null ? "unassessed-per-batch" : names);
        pin(dependencies, "files.storage", "unassessed-per-batch");
        pin(
                dependencies,
                "files.cursor.policy",
                names == null
                        ? "prefer-content-inventory;explicit-unsupported-metadata-fallback"
                        : "metadata-for-frozen-name-assessment");
        return new ComparisonTypes.Descriptor(
                descriptor.identity(),
                descriptor.product(),
                descriptor.locale(),
                descriptor.namespace(),
                descriptor.scope(),
                dependencies);
    }

    private static void pin(java.util.Map<String, String> values, String key, String value) {
        var previous = values.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new IllegalArgumentException("Conflicting dependency " + key);
        }
    }

    @Override
    protected ComparisonTypes.InputPage page(int limit, Cancellation token) {
        if (!initialized) {
            initialized = true;
            if (pinnedNames == null) {
                try {
                    contentCursor =
                            Objects.requireNonNull(
                                    files.listContentInventory(FileTypes.FileIdRange.ALL, token));
                    source = "BuildFiles.listContentInventory";
                } catch (UnsupportedOperationException unavailable) {
                    // Explicit legacy refusal only; no payload-reading fallback and no speed claim.
                }
            }
            if (contentCursor == null) {
                cursor = files.listMetadata(FileTypes.FileFilter.ALL, token);
            }
        }
        var names = new LinkedHashMap<Long, FileTypes.BuildFile>();
        List<FileTypes.ContentFile> batch;
        if (contentCursor != null) {
            batch = contentCursor.next(limit, token).join();
        } else {
            var metadata = cursor.next(limit, token).join();
            batch =
                    metadata.stream()
                            .map(
                                    file ->
                                            new FileTypes.ContentFile(
                                                    file.fileDataId(),
                                                    file.size(),
                                                    file.contentKey()))
                            .toList();
            if (pinnedNames != null) {
                metadata.forEach(file -> names.put(file.fileDataId(), file));
            }
        }
        token.check();
        if (batch.size() > limit) {
            throw new IllegalStateException("Files metadata page exceeds requested bound");
        }
        var records = new ArrayList<ComparisonTypes.Record>();
        for (var file : batch) {
            token.check();
            var key = InventoryFields.key(file.fileDataId(), true);
            if (file.fileDataId() <= previous) {
                throw new IllegalStateException("Files metadata IDs did not advance");
            }
            previous = file.fileDataId();
            var fields = new LinkedHashMap<String, ComparisonTypes.Field>();
            String identity = descriptor().identity() + ":" + key;
            fields.put(
                    "contentKey", InventoryFields.contentKey(file.contentKey(), source, identity));
            fields.put(
                    "decodedSize",
                    InventoryFields.number(
                            "root-decoded-size-bytes-v1",
                            file.size(),
                            ComparisonTypes.Aspect.CONTENT,
                            source,
                            identity,
                            "size"));
            if (pinnedNames != null) {
                var named = names.get(file.fileDataId());
                boolean pinned = named.pathSource() == FileTypes.FilePathSource.LISTFILE;
                fields.put(
                        "path",
                        InventoryFields.text(
                                "pinned-listfile-path-v1",
                                pinned ? named.path() : null,
                                ComparisonTypes.Aspect.EXTERNAL_EVIDENCE,
                                source,
                                identity,
                                "path"));
                fields.put(
                        "pathSource",
                        InventoryFields.text(
                                "file-name-origin-v1",
                                named.pathSource() == null ? null : named.pathSource().name(),
                                ComparisonTypes.Aspect.EXTERNAL_EVIDENCE,
                                source,
                                identity,
                                "pathSource"));
                boolean extension =
                        pinned && named.formatSource() == FileTypes.FileFormatSource.EXTENSION;
                fields.put(
                        "format",
                        InventoryFields.text(
                                "name-extension-format-v1",
                                extension && named.format() != null ? named.format().name() : null,
                                ComparisonTypes.Aspect.INTERPRETATION,
                                source,
                                identity,
                                "format"));
            }
            records.add(
                    new ComparisonTypes.Record(
                            key,
                            fields,
                            List.of(
                                    new ComparisonTypes.Evidence(
                                            source,
                                            identity,
                                            "FileDataID",
                                            Long.toString(file.fileDataId())))));
        }
        var issues = new ArrayList<String>();
        issues.add(
                "Selected-locale root membership completeness is not certified by the public metadata cursor.");
        if (pinnedNames == null) {
            issues.add(
                    "Names refresh per source batch; path/extension fields are unassessed without a frozen name publication.");
        }
        issues.add(
                "Local storage may change per batch and is not captured in this frozen content comparison.");
        return new ComparisonTypes.InputPage(
                descriptor().identity(),
                records,
                batch.isEmpty() ? null : "continue",
                new ComparisonTypes.Coverage(ComparisonTypes.Completeness.UNKNOWN, SCOPE, issues));
    }

    @Override
    protected void release() {
        if (cursor != null) {
            cursor.close();
        }
        if (contentCursor != null) {
            contentCursor.close();
        }
    }
}
