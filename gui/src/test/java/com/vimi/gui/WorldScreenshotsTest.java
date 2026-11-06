package com.vimi.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Screenshot names and PNG files: pixels round trip, no overwrite, no partial files; no FX. */
public final class WorldScreenshotsTest {
    private WorldScreenshotsTest() {}

    public static void main(String[] args) throws Exception {
        var time = ZonedDateTime.of(2026, 11, 6, 21, 5, 9, 0, ZoneOffset.UTC);
        require(WorldScreenshots.fileName("Eastern Kingdoms", time).equals("vimi-world-eastern-kingdoms-20261106-210509.png"),
                "Map and time name the file: " + WorldScreenshots.fileName("Eastern Kingdoms", time));
        require(WorldScreenshots.fileName("../C:\\x?*", time).equals("vimi-world-c-x-20261106-210509.png"),
                "Path characters never reach the name: " + WorldScreenshots.fileName("../C:\\x?*", time));
        require(WorldScreenshots.fileName(null, time).equals("vimi-world-map-20261106-210509.png")
                        && WorldScreenshots.fileName("???", time).equals("vimi-world-map-20261106-210509.png"),
                "No usable map name falls back to map");
        require(WorldScreenshots.fileName("a".repeat(80), time).length() == "vimi-world-".length() + 40 + 20,
                "Long names are bounded");
        require(WorldScreenshots.folder(Path.of("data")).equals(Path.of("data", "screenshots")),
                "The folder is the data directory's screenshots subfolder");

        var root = Files.createTempDirectory("vimi-screenshots-test");
        try {
            // Production wiring: the app's World store puts screenshots in the data directory.
            var store = BuildApplication.worldStore(root, root.resolve("library").resolve("objects.sqlite3"));
            try {
                require(store.screenshots().equals(root.resolve("screenshots")),
                        "Screenshots go to <data>/screenshots, not the library folder: " + store.screenshots());
            } finally {
                store.close();
            }
            require(WorldBookmarkStore.session().screenshots() == null, "A session-only store has no folder");
            var folder = WorldScreenshots.folder(root);
            int[] argb = {0xffff0000, 0xff00ff00, 0xff0000ff, 0x80ffffff, 0xff000000, 0xff123456};
            var first = WorldScreenshots.save(folder, "world-a.png", 3, 2, argb);
            require(first.equals(folder.resolve("world-a.png")) && Files.isRegularFile(first),
                    "Saved under its name, folder created: " + first);
            var image = javax.imageio.ImageIO.read(first.toFile());
            require(image.getWidth() == 3 && image.getHeight() == 2, "Dimensions kept");
            for (int i = 0; i < argb.length; i++) {
                require(image.getRGB(i % 3, i / 3) == argb[i], "Pixel " + i + " round trips");
            }
            long size = Files.size(first);
            var second = WorldScreenshots.save(folder, "world-a.png", 1, 1, new int[] {0xffffffff});
            require(second.equals(folder.resolve("world-a-2.png")) && Files.size(first) == size,
                    "A taken name gets a suffix and the first file is untouched: " + second);
            require(WorldScreenshots.save(folder, "world-a", 1, 1, new int[] {0xff000000})
                            .equals(folder.resolve("world-a-3.png")),
                    "The .png suffix is optional");
            boolean refused = false;
            try {
                WorldScreenshots.save(folder, "bad.png", 2, 2, new int[3]);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            require(refused && !Files.exists(folder.resolve("bad.png")), "Mismatched pixels are refused");
            try (var files = Files.list(folder)) {
                require(files.noneMatch(file -> file.getFileName().toString().endsWith(".part")),
                        "No partial files are left behind");
            }
            var stale = Files.writeString(folder.resolve(".world-dead.png.part"), "x");
            Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(
                    java.time.Instant.now().minus(WorldScreenshots.STALE_PART).minusSeconds(5)));
            var fresh = Files.writeString(folder.resolve(".world-busy.png.part"), "x");
            WorldScreenshots.save(folder, "world-b.png", 1, 1, new int[] {0xff000000});
            require(!Files.exists(stale) && Files.exists(fresh),
                    "A stale partial file from a dead process is removed; a fresh one is left alone");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        System.out.println("World screenshots: names, folder, PNG round trip, no overwrite and no partial files passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
