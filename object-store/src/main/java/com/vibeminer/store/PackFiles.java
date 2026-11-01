package com.vibeminer.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable six-digit pack manifest: at most 125,000 bytes of bits, with paths derived lazily. */
final class PackFiles extends AbstractMap<Integer, Path> {
    static final int MAX_PACK = 999_999;
    private static final Pattern NAME = Pattern.compile("pack-[0-9]{6}\\.vmp");
    private final Path directory;
    private final BitSet present;
    private final int size;

    private PackFiles(Path directory, BitSet present) {
        this.directory = directory;
        this.present = present;
        size = present.cardinality();
    }

    static PackFiles discover(Path directory) throws IOException {
        BitSet present = new BitSet(MAX_PACK + 1);
        if (Files.isDirectory(directory)) {
            try (var paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    String name = path.getFileName().toString();
                    if (!NAME.matcher(name).matches()) {
                        continue;
                    }
                    int number = Integer.parseInt(name.substring(5, 11));
                    if (number > 0) {
                        present.set(number);
                    }
                }
            }
        }
        return new PackFiles(directory, present);
    }

    int lastKey() {
        if (present.isEmpty()) {
            throw new NoSuchElementException();
        }
        return present.length() - 1;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Integer number
                && number > 0
                && number <= MAX_PACK
                && present.get(number);
    }

    @Override
    public Path get(Object key) {
        return containsKey(key) ? path((Integer) key) : null;
    }

    private Path path(int number) {
        return directory.resolve(String.format(java.util.Locale.ROOT, "pack-%06d.vmp", number));
    }

    @Override
    public Set<Map.Entry<Integer, Path>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public Iterator<Map.Entry<Integer, Path>> iterator() {
                return new Iterator<>() {
                    private int next = present.nextSetBit(1);

                    @Override
                    public boolean hasNext() {
                        return next >= 0;
                    }

                    @Override
                    public Map.Entry<Integer, Path> next() {
                        if (next < 0) {
                            throw new NoSuchElementException();
                        }
                        int number = next;
                        next = present.nextSetBit(number + 1);
                        return Map.entry(number, path(number));
                    }
                };
            }
        };
    }
}
