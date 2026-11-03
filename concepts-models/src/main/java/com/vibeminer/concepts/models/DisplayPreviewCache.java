package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A scoped derivative always revalidates current content receipts for all model/skin/texture
 * inputs.
 */
@SuppressWarnings("try")
final class DisplayPreviewCache {
    private static final int MAGIC = 0x56445031, MAX_RECEIPTS = 34;
    private final GeneratedStore store;
    private final BuildFiles files;

    DisplayPreviewCache(GeneratedStore store, BuildFiles files) {
        this.store = store;
        this.files = files;
    }

    MeshAppearance restore(
            GeneratedKey key, long modelFileDataId, String modelContent, Cancellation token) {
        if (store == null) {
            return null;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var read = store.read(key, lower).join();
            token.check();
            if (read.state() != GeneratedTypes.ReadState.PRESENT
                    || read.length() > MeshAppearanceCodec.MAX_BYTES + 4096) {
                return null;
            }
            byte[] bytes = read.bytes();
            if (bytes == null
                    || bytes.length > MeshAppearanceCodec.MAX_BYTES + 4096
                    || bytes.length != read.length()) {
                return null;
            }
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != MAGIC) {
                return null;
            }
            int count = input.readInt();
            if (count < 2 || count > MAX_RECEIPTS) {
                return null;
            }
            var receipts = new TreeMap<Long, String>();
            for (int i = 0; i < count; i++) {
                long id = input.readLong();
                String content = input.readUTF();
                if (id <= 0
                        || id > 0xffffffffL
                        || !content.matches("[0-9a-f]{32}")
                        || receipts.put(id, content) != null) {
                    return null;
                }
            }
            if (!modelContent.equals(receipts.get(modelFileDataId))) {
                return null;
            }
            for (var receipt : receipts.entrySet()) {
                token.check();
                var stat = files.stat(FileTypes.FileRef.id(receipt.getKey()), token).join();
                if (stat.file() == null
                        || stat.state() != FileTypes.FileLookupState.FOUND
                        || !receipt.getValue().equals(stat.file().contentKey())) {
                    return null;
                }
            }
            int length = input.readInt();
            if (length < 24
                    || length > MeshAppearanceCodec.MAX_BYTES
                    || input.available() != length) {
                return null;
            }
            return MeshAppearanceCodec.decode(input.readNBytes(length));
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (java.io.IOException | RuntimeException invalid) {
            token.check();
            return null;
        }
    }

    void save(
            GeneratedKey key,
            MeshAppearance value,
            Map<Long, String> receipts,
            Cancellation token,
            List<String> issues) {
        if (store == null) {
            return;
        }
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            if (receipts.size() < 2 || receipts.size() > MAX_RECEIPTS) {
                return;
            }
            byte[] mesh = MeshAppearanceCodec.encode(value);
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(MAGIC);
            out.writeInt(receipts.size());
            for (var receipt : new TreeMap<>(receipts).entrySet()) {
                out.writeLong(receipt.getKey());
                out.writeUTF(receipt.getValue());
            }
            out.writeInt(mesh.length);
            out.write(mesh);
            token.check();
            store.delete(List.of(key), lower).join();
            store.put(List.of(new GeneratedTypes.Put(key, buffer.toByteArray())), lower).join();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (java.io.IOException | RuntimeException unavailable) {
            token.check();
            TableProjection.note(
                    issues, "Preview cache unavailable; original assets remain available.");
        }
    }
}
