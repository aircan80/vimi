package com.vimi.gui;

import java.util.List;
import java.util.Locale;

/** Authored file presentation fixtures. No ROOT parsing, content sniffing, or downloads. */
public final class MockFileCatalog {
    private MockFileCatalog() {}

    public enum LocalState { STORED, NOT_STORED, UNKNOWN }

    public record Variant(String locale, Long encodedBytes, Long decodedBytes, LocalState local) {}

    public record FileEntry(long id, String path, String type, List<Variant> variants) {
        public FileEntry {
            variants = List.copyOf(variants);
        }

        public String name() {
            return path == null ? "Name not known" : path.substring(path.lastIndexOf('/') + 1);
        }

        public String downloaded() {
            long stored = variants.stream().filter(v -> v.local() == LocalState.STORED).count();
            if (variants.stream().anyMatch(v -> v.local() == LocalState.UNKNOWN)) {
                return stored == 0 ? "Unknown" : stored + " stored · rest unknown";
            }
            if (stored == variants.size()) {
                return "Stored locally";
            }
            return stored == 0 ? "Not stored" : stored + " of " + variants.size() + " stored";
        }

        public String size() {
            var lengths = variants.stream().map(Variant::encodedBytes).filter(java.util.Objects::nonNull).toList();
            if (lengths.isEmpty()) {
                return "Unknown";
            }
            long min = lengths.stream().mapToLong(Long::longValue).min().orElseThrow();
            long max = lengths.stream().mapToLong(Long::longValue).max().orElseThrow();
            String value = min == max ? bytes(min) : bytes(min) + "–" + bytes(max);
            return lengths.size() < variants.size() ? value + " + unknown" : value;
        }
    }

    public static String bytes(Long value) {
        if (value == null) {
            return "Unknown";
        }
        if (value < 1024) {
            return value + " B";
        }
        return value < 1024 * 1024 ? String.format(Locale.ROOT, "%.0f KiB", value / 1024.0)
                : String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
    }

    private static FileEntry file(long id, String path, String type, long size, LocalState state) {
        return new FileEntry(id, path, type, List.of(new Variant("enUS", size, size * 2, state)));
    }

    public static List<FileEntry> entries() {
        return List.of(
                file(800001, "interface/icons/example_sword.blp", "Texture · BLP", 131072, LocalState.STORED),
                file(800002, "creature/examplewolf/examplewolf.m2", "Model · M2", 524288, LocalState.NOT_STORED),
                new FileEntry(800003, null, "Unknown", List.of(new Variant("enUS", null, null, LocalState.UNKNOWN))),
                new FileEntry(800004, "interface/glues/example_background.blp", "Texture · BLP", List.of(
                        new Variant("enUS", 131072L, 262144L, LocalState.STORED),
                        new Variant("deDE", 262144L, 524288L, LocalState.NOT_STORED))),
                file(800005, "dbfilesclient/item.db2", "Table · DB2", 1572864, LocalState.STORED),
                file(800006, "dbfilesclient/itemsparse.db2", "Table · DB2", 8388608, LocalState.NOT_STORED),
                file(800007, "dbfilesclient/creature.db2", "Table · DB2", 2097152, LocalState.STORED),
                file(800008, "world/example/example_building.wmo", "World model · WMO", 3145728, LocalState.NOT_STORED),
                file(800009, "interface/icons/example_robe.blp", "Texture · BLP", 65536, LocalState.STORED),
                file(800010, "creature/exampleguardian/exampleguardian.m2", "Model · M2", 1048576, LocalState.NOT_STORED),
                file(800011, "interface/icons/example_shield.blp", "Texture · BLP", 98304, LocalState.STORED),
                file(800012, "dbfilesclient/itemclass.db2", "Table · DB2", 8192, LocalState.STORED),
                file(800013, "world/example/example_tower.wmo", "World model · WMO", 4194304, LocalState.NOT_STORED),
                file(800014, "interface/icons/example_potion.blp", "Texture · BLP", 32768, LocalState.NOT_STORED),
                file(800015, "creature/examplebird/examplebird.m2", "Model · M2", 393216, LocalState.STORED),
                file(800016, "interface/maps/example_map.blp", "Texture · BLP", 1048576, LocalState.NOT_STORED),
                new FileEntry(800017, null, "Unknown", List.of(new Variant("enUS", 262144L, null, LocalState.NOT_STORED))),
                file(800018, "dbfilesclient/creaturedisplayinfo.db2", "Table · DB2", 3145728, LocalState.STORED),
                new FileEntry(800019, "interface/icons/example_quest.blp", "Texture · BLP", List.of(
                        new Variant("enUS", 65536L, 131072L, LocalState.STORED),
                        new Variant("frFR", null, null, LocalState.UNKNOWN))),
                file(800020, "world/example/example_bridge.wmo", "World model · WMO", 2097152, LocalState.NOT_STORED));
    }
}
