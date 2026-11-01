package com.vibeminer.builds;

import java.util.ArrayList;
import java.util.List;

final class BuildLocales {
    private static final long[] BITS = {
        2, 4, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
    };
    private static final String[] NAMES = {
        "enUS", "koKR", "frFR", "deDE", "zhCN", "esES", "zhTW", "enGB", "enCN", "enTW", "esMX",
        "ruRU", "ptBR", "itIT", "ptPT"
    };

    private BuildLocales() {}

    static List<String> of(long union) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < BITS.length; i++) {
            if ((union & BITS[i]) != 0) {
                names.add(NAMES[i]);
            }
        }
        return List.copyOf(names);
    }

    static long bit(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return BITS[i];
            }
        }
        throw new IllegalArgumentException("Unknown locale: " + name);
    }
}
