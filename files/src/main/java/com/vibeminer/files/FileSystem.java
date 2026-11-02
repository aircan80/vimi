package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.Builds;
import com.vibeminer.files.FileTypes.FilesOpenResult;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.files.names.FileNames;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** File operations over build resolution and object access only. */
public final class FileSystem implements Files {
    private final Builds builds;
    private final ObjectSource objects;
    private final FileSystemOptions options;

    public FileSystem(Builds builds, ObjectSource objects) {
        this(builds, objects, FileSystemOptions.defaults());
    }

    public FileSystem(Builds builds, ObjectSource objects, FileSystemOptions options) {
        this.builds = Objects.requireNonNull(builds);
        this.objects = Objects.requireNonNull(objects);
        this.options = Objects.requireNonNull(options);
    }

    @Override
    public FileNames names() {
        return options.names();
    }

    @Override
    public CompletableFuture<FilesOpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        long bit = FileLocales.bitOf(locale);
        Objects.requireNonNull(build);
        return builds.open(build, token)
                .thenApply(
                        opened -> {
                            if (opened.opened() == null) {
                                return new FilesOpenResult(
                                        build,
                                        locale,
                                        FilesOpenState.valueOf(opened.state().name()),
                                        null,
                                        opened.code(),
                                        opened.message(),
                                        opened.observedAt());
                            }
                            if (!opened.opened().metadata().locales().contains(locale)) {
                                return new FilesOpenResult(
                                        build,
                                        locale,
                                        FilesOpenState.LOCALE_NOT_IN_BUILD,
                                        null,
                                        "FILES_LOCALE_NOT_IN_BUILD",
                                        "No files for locale",
                                        null);
                            }
                            return new FilesOpenResult(
                                    build,
                                    locale,
                                    FilesOpenState.OPEN,
                                    new BuildFilesHandle(
                                            opened.opened(), locale, bit, objects, options),
                                    null,
                                    null,
                                    null);
                        });
    }
}
