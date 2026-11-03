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
                var children = new ArrayList<Path>();
                boolean recognized = true;
                try (var entries = Files.newDirectoryStream(directory)) {
                    for (var child : entries) {
                        if (!child.getFileName().toString().equals("results.tmp")
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
