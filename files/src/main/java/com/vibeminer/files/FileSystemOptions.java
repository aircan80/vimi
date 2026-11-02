package com.vibeminer.files;

import com.vibeminer.files.names.FileNames;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public record FileSystemOptions(
        int presenceBatch,
        Duration progressInterval,
        Clock clock,
        long maxReadBytes,
        TactKeySource keys,
        FileNames names) {
    public FileSystemOptions {
        if (presenceBatch < 1
                || presenceBatch > 65536
                || maxReadBytes < 0
                || maxReadBytes > Integer.MAX_VALUE - 8
                || progressInterval.isNegative()
                || progressInterval.isZero()) {
            throw new IllegalArgumentException("Invalid Files bounds");
        }
        Objects.requireNonNull(clock);
        Objects.requireNonNull(keys);
        Objects.requireNonNull(names);
    }

    public static FileSystemOptions defaults() {
        return new FileSystemOptions(
                4096,
                Duration.ofMillis(100),
                Clock.systemUTC(),
                256L << 20,
                TactKeys.NONE,
                FileNames.OFF);
    }
}
