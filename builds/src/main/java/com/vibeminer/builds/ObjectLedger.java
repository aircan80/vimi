package com.vibeminer.builds;

import com.vibeminer.builds.formats.EncodingTable;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.ObjectKey;
import java.util.Arrays;

/** Immutable ascending encoded-key ledger for downloaded figures and download-all jobs. */
final class ObjectLedger {
    private final CdnKey[] keys;
    private final long[] sizes;
    private final long totalBytes;

    ObjectLedger(CdnKey[] keys, long[] sizes) {
        if (keys.length != sizes.length) {
            throw new IllegalArgumentException("One ledger size per key is required");
        }
        long total = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null || i > 0 && keys[i].compareTo(keys[i - 1]) <= 0 || sizes[i] < 0) {
                throw new IllegalArgumentException(
                        "Ledger keys must ascend and sizes cannot be negative");
            }
            total = Math.addExact(total, sizes[i]);
        }
        this.keys = keys.clone();
        this.sizes = sizes.clone();
        totalBytes = total;
    }

    static ObjectLedger from(EncodingTable encoding) {
        CdnKey[] keys = new CdnKey[Math.toIntExact(encoding.encodedCount())];
        long[] sizes = new long[keys.length];
        int at = 0;
        for (EncodingTable.Encoded row : encoding.encodedRows()) {
            if (at == keys.length) {
                throw new BuildFormatException(
                        BuildFailure.CORRUPT, "EN_INTEGRITY", "EN ledger count changed");
            }
            keys[at] = row.key();
            sizes[at++] = row.encodedSize();
        }
        if (at != keys.length) {
            throw new BuildFormatException(
                    BuildFailure.CORRUPT, "EN_INTEGRITY", "EN ledger count changed");
        }
        return new ObjectLedger(keys, sizes);
    }

    int count() {
        return keys.length;
    }

    long totalBytes() {
        return totalBytes;
    }

    CdnKey key(int index) {
        return keys[index];
    }

    long size(int index) {
        return sizes[index];
    }

    int indexOf(CdnKey key) {
        int at = Arrays.binarySearch(keys, key);
        return at < 0 ? -1 : at;
    }

    int indexOf(ObjectKey key) {
        return key.type() != ObjectKey.ENCODED || key.namespace() != 0
                ? -1
                : indexOf(new CdnKey(key.bytes()));
    }
}
