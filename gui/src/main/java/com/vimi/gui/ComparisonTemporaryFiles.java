package com.vimi.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;

/** Called only after exclusive application-library ownership; never traverses symbolic links. */
final class ComparisonTemporaryFiles {
    private ComparisonTemporaryFiles() {}

    static void cleanStale(Path parent) throws IOException {
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent))
            throw new IOException("Comparison work directory is a symbolic link");
        try (var directories = Files.newDirectoryStream(parent, "comparison-*")) {
            for (var directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                boolean sorted = directory.getFileName().toString().startsWith("comparison-sort-");
                boolean items = directory.getFileName().toString().startsWith("comparison-items-");
                var children = new ArrayList<Path>();
                boolean recognized = true;
                try (var entries = Files.newDirectoryStream(directory)) {
                    for (var child : entries) {
                        if (!(items
                                        ? child.getFileName()
                                                .toString()
                                                .matches("candidates-[0-9]+\\.tmp")
                                        : sorted
                                                ? child.getFileName()
                                                        .toString()
                                                        .matches("run-[0-9]+\\.tmp")
                                                : (child.getFileName()
                                                                .toString()
                                                                .equals("results.tmp")
                                                        || child.getFileName()
                                                                .toString()
                                                                .matches("results[0-9]+\\.tmp")))
                                || children.size() >= 2048
                                || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                            recognized = false;
                            break;
                        }
                        children.add(child);
                    }
                }
                if (!recognized) continue;
                for (var child : children) Files.delete(child);
                Files.delete(directory);
            }
        }
    }
}
