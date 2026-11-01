package com.vibeminer.generated;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.io.TempDir;

final class DiskGeneratedStoreTest extends GeneratedStoreContract {
    @TempDir Path folder;

    @Override
    protected GeneratedStore create() {
        return DiskGeneratedStore.open(folder).join();
    }

    @Override
    protected void closeStore() {
        ((DiskGeneratedStore) store).closeAsync().join();
    }

    @Override
    protected void damage(GeneratedKey key, byte[] bytes) throws Exception {
        closeStore();
        try (var files = Files.list(folder.resolve("packs"))) {
            Path pack = files.filter(p -> p.toString().endsWith(".vmp")).findFirst().orElseThrow();
            // Locate synthetic payload without depending on internal pack types.
            byte[] data = Files.readAllBytes(pack);
            int at = -1;
            outer:
            for (int i = 0; i <= data.length - 32; i++) {
                for (int j = 0; j < 32; j++) {
                    if (data[i + j] != bytes[j]) {
                        continue outer;
                    }
                }
                at = i;
                break;
            }
            if (at < 0) {
                throw new AssertionError("Payload missing");
            }
            try (FileChannel channel = FileChannel.open(pack, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[] {(byte) (bytes[100] ^ 1)}), at + 100);
            }
        }
        store = DiskGeneratedStore.open(folder).join();
    }
}
