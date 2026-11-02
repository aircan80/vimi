package com.vibeminer.files;

import java.util.List;

public final class FileLocales {
    public static final List<String> NAMES =
            List.of(
                    "enUS", "koKR", "frFR", "deDE", "zhCN", "esES", "zhTW", "enGB", "enCN", "enTW",
                    "esMX", "ruRU", "ptBR", "itIT", "ptPT");
    private static final long[] BITS = {
        2, 4, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
    };

    private FileLocales() {}

    public static boolean isKnown(String locale) {
        return locale != null && NAMES.contains(locale);
    }

    public static long bitOf(String locale) {
        int index = locale == null ? -1 : NAMES.indexOf(locale);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown locale");
        }
        return BITS[index];
    }
}
