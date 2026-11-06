package com.vimi.gui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Viewport screenshots: the presented World frame only (no HUD), as a PNG in the {@code
 * screenshots} folder of the application's data directory (the {@code vimi.dataDirectory}
 * setting or the platform default, as {@link BuildApplication#defaultDataDirectory} resolves it). Names carry the map and the local time; an existing file is never
 * overwritten. The file is written beside its final name and moved into place, so a reader never
 * sees a half-written image. No JavaFX; callers copy the pixels on FX and save off it.
 */
final class WorldScreenshots {
    private WorldScreenshots() {}

    static final String FOLDER = "screenshots";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** The screenshots folder of a library data directory. */
    static Path folder(Path dataDirectory) {
        return dataDirectory.resolve(FOLDER);
    }

    /** {@code vimi-world-<map>-<yyyyMMdd-HHmmss>.png}; the map part keeps letters and digits only. */
    static String fileName(String mapName, ZonedDateTime time) {
        String slug = mapName == null ? "" : mapName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-+$", "");
        return "vimi-world-" + (slug.isEmpty() ? "map" : slug) + "-" + STAMP.format(time) + ".png";
    }

    /**
     * Writes {@code argb} ({@code width} x {@code height}, row-major) as {@code name} in {@code
     * folder}, creating the folder; a taken name gets "-2", "-3", ... Returns the written file.
     */
    static Path save(Path folder, String name, int width, int height, int[] argb) throws IOException {
        Objects.requireNonNull(folder);
        Objects.requireNonNull(name);
        if (width <= 0 || height <= 0 || argb.length != width * height)
            throw new IllegalArgumentException("Screenshot pixels do not match " + width + "x" + height);
        Files.createDirectories(folder);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        String stem = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        var temporary = Files.createTempFile(folder, ".world-", ".png.part");
        try {
            if (!javax.imageio.ImageIO.write(image, "png", temporary.toFile()))
                throw new IOException("No PNG writer is available");
            for (int n = 1; ; n++) {
                var target = folder.resolve(n == 1 ? stem + ".png" : stem + "-" + n + ".png");
                if (Files.exists(target)) continue;
                try {
                    // A rename within the folder, without REPLACE_EXISTING: a file that appeared
                    // meanwhile is never overwritten.
                    return Files.move(temporary, target);
                } catch (java.nio.file.FileAlreadyExistsException taken) {
                    // Raced with another writer: try the next suffix.
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
