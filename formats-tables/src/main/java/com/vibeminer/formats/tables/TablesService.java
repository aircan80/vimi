package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.files.Files;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.definitions.DbdTypes.BuildVersion;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.generated.GeneratedTableStore;
import java.util.concurrent.CompletableFuture;

/** Layer5 composition over Files, definitions and derived generated rows. */
public final class TablesService implements Tables {
    private final Files files;
    private final TableDefinitions definitions;
    private final GeneratedTableStore generated;
    private final TablesOptions options;
    private final BackgroundBuilds backgroundBuilds = new BackgroundBuilds();
    private final ProtectedIds protectedIds = new ProtectedIds();

    public TablesService(
            Files files,
            TableDefinitions definitions,
            GeneratedTableStore generated,
            TablesOptions options) {
        this.files = java.util.Objects.requireNonNull(files);
        this.definitions = java.util.Objects.requireNonNull(definitions);
        this.generated = java.util.Objects.requireNonNull(generated);
        this.options = java.util.Objects.requireNonNull(options);
    }

    @Override
    public CompletableFuture<TableTypes.OpenResult> forBuild(
            BuildRef build, String locale, Cancellation token) {
        WowLocale.parse(locale);
        token.check();
        return files.forBuild(build, locale, token)
                .thenCompose(
                        open -> {
                            if (open.state() != FilesOpenState.OPEN || open.files() == null) {
                                return CompletableFuture.completedFuture(
                                        new TableTypes.OpenResult(
                                                build,
                                                locale,
                                                open.state(),
                                                null,
                                                open.code(),
                                                open.message(),
                                                open.observedAt()));
                            }
                            CompletableFuture<String> versionFuture;
                            try {
                                versionFuture =
                                        java.util.Objects.requireNonNull(
                                                options.buildVersion().apply(build, token),
                                                "Build version future");
                            } catch (Throwable error) {
                                open.files().close();
                                return CompletableFuture.failedFuture(error);
                            }
                            return versionFuture.handle(
                                    (version, error) -> {
                                        try {
                                            if (error != null) {
                                                throw new java.util.concurrent.CompletionException(
                                                        error);
                                            }
                                            try {
                                                BuildVersion.parse(version);
                                            } catch (Db2FormatException e) {
                                                open.files().close();
                                                return new TableTypes.OpenResult(
                                                        build,
                                                        locale,
                                                        FilesOpenState.UNSUPPORTED,
                                                        null,
                                                        "TABLES_BUILD_VERSION_UNKNOWN",
                                                        "Exact build version is unknown",
                                                        null);
                                            }
                                            return new TableTypes.OpenResult(
                                                    build,
                                                    locale,
                                                    FilesOpenState.OPEN,
                                                    create(open.files(), version),
                                                    null,
                                                    null,
                                                    null);
                                        } catch (RuntimeException e) {
                                            open.files().close();
                                            throw e;
                                        }
                                    });
                        });
    }

    private BuildTables create(com.vibeminer.files.BuildFiles opened, String version) {
        var base =
                new ClientBuildTables(
                        files,
                        opened,
                        version,
                        definitions,
                        generated,
                        options,
                        backgroundBuilds,
                        protectedIds);
        var hotfix = options.hotfix();
        if (hotfix == null) {
            return base;
        }
        if (!hotfix.build().equals(opened.build())
                || !hotfix.locale().equals(opened.locale())
                || !hotfix.version().equals(version)) {
            throw new IllegalArgumentException("Hotfix binding differs from selected workspace");
        }
        return new HotfixBuildTables(base, options);
    }
}
