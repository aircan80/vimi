package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/** Bounded canonical header/descriptors only; payload IO and ownership live in their callers. */
final class WorldGlobalFileCodec {
    private static final byte[] MAGIC = {'V', 'I', 'M', 'I', 'G', 'W', 'S', '1'};
    private static final HexFormat HEX = HexFormat.of();

    private WorldGlobalFileCodec() {}

    record Header(
            byte[] bytes,
            WorldGlobalLayout.Counts counts,
            WorldGlobalStatus.Identity identity,
            long mapId,
            String recipe,
            String wire,
            String build,
            WorldGlobalHashes.RawModf modf,
            long wdt,
            long root,
            long uid,
            float[] bounds) {}

    record ImageLocation(int offset, int length) {}

    record Meta(
            WorldGlobalSceneWriter.Description description,
            List<WorldGlobalHashes.Group> groups,
            List<WorldGlobalHashes.Batch> batches,
            List<String> groupHashes,
            List<ImageLocation> images) {}

    static byte[] header(
            WorldGlobalSceneWriter.Destination target,
            WorldGlobalSceneWriter.Description d,
            WorldGlobalLayout.Counts c,
            WorldGlobalHashes.MeshDigests hashes,
            float[] bounds) {
        WorldGlobalLayout.require(bounds.length == 6 && d.scope().equals(target.scope()));
        var b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        var o = c.offsets();
        b.put(MAGIC)
                .putInt(WorldGlobalLayout.PROFILE)
                .putInt(1024)
                .putLong(o.actualBytes())
                .putInt(1)
                .putInt(1)
                .putInt(1)
                .putInt(0)
                .putLong(d.mapId())
                .put(HEX.parseHex(target.nonce()))
                .put(HEX.parseHex(d.scope()))
                .put(HEX.parseHex(d.recipe()))
                .put(HEX.parseHex(hashes.nativeWire()))
                .put(new byte[32])
                .put(HEX.parseHex(d.buildLocale()));
        for (int count :
                new int[] {
                    c.sources(),
                    c.materials(),
                    c.groups(),
                    c.batches(),
                    c.images(),
                    c.vertices(),
                    c.indices(),
                    c.rawTriangles(),
                    c.drawnTriangles(),
                    511
                }) {
            b.putInt(count);
        }
        b.putLong(0).putLong(0).putLong(0);
        for (float value : bounds) {
            b.putFloat(value);
        }
        for (int offset :
                new int[] {
                    o.source(),
                    o.material(),
                    o.group(),
                    o.batch(),
                    o.image(),
                    o.vertices(),
                    o.indices(),
                    o.rgba()
                }) {
            b.putInt(offset);
        }
        byte[] raw = d.modf().bytes();
        long uid =
                Integer.toUnsignedLong(
                        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        b.putInt((int) d.sources().get(0).id())
                .putInt((int) d.sources().get(1).id())
                .putInt(0)
                .putInt((int) uid)
                .put(raw);
        return b.array();
    }

    static byte[] metadata(
            WorldGlobalSceneWriter.Description d,
            WorldGlobalLayout.Counts c,
            List<WorldGlobalHashes.Group> groups,
            List<WorldGlobalHashes.Batch> batches,
            List<String> hashes) {
        WorldGlobalLayout.require(
                d.sources().size() == c.sources()
                        && d.materials().size() == c.materials()
                        && d.images().size() == c.images()
                        && groups.size() == c.groups()
                        && batches.size() == c.batches()
                        && hashes.size() == c.groups());
        var b = ByteBuffer.allocate(c.offsets().vertices() - 1024).order(ByteOrder.LITTLE_ENDIAN);
        for (var source : d.sources()) {
            b.put(WorldGlobalHashes.sourceBytes(source));
        }
        for (var material : d.materials()) {
            for (long word : material.words()) {
                b.putInt((int) word);
            }
        }
        for (int n = 0; n < groups.size(); n++) {
            var g = groups.get(n);
            b.putInt(g.vertexFirst())
                    .putInt(g.vertexCount())
                    .putInt(g.indexFirst())
                    .putInt(g.indexCount())
                    .putInt(g.batchFirst())
                    .putInt(g.batchCount())
                    .putShort((short) n)
                    .putShort((short) (2 + n))
                    .putInt((int) g.flags())
                    .put(HEX.parseHex(hashes.get(n)));
        }
        for (var batch : batches) {
            b.put((byte) batch.group())
                    .put((byte) batch.material())
                    .putShort((short) 0)
                    .putInt(batch.firstIndex())
                    .putInt(batch.indexCount())
                    .putInt(0);
        }
        int rgba = c.offsets().rgba();
        for (int n = 0; n < d.images().size(); n++) {
            var im = d.images().get(n);
            int length = im.width() * im.height() * 4;
            b.putInt((int) im.id())
                    .putShort((short) n)
                    .putShort((short) im.width())
                    .putShort((short) im.height())
                    .put((byte) im.encoding())
                    .put((byte) im.alphaDepth())
                    .put((byte) im.alphaEncoding())
                    .put((byte) 1)
                    .put((byte) 0)
                    .put((byte) im.mipCount())
                    .putInt(rgba)
                    .putInt(length)
                    .putShort((short) (2 + c.groups() + n))
                    .put((byte) im.alphaMin())
                    .put((byte) im.alphaMax())
                    .putInt(im.nonopaque())
                    .put(HEX.parseHex(im.opaqueRgbaSha256()))
                    .put(HEX.parseHex(im.originalRgbaSha256()));
            rgba += length;
        }
        WorldGlobalLayout.require(rgba == c.offsets().actualBytes() && !b.hasRemaining());
        return b.array();
    }

    static Header header(byte[] raw, WorldGlobalSceneWriter.InputExpectation expected)
            throws IOException {
        check(raw.length == 1024);
        var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int n = 0; n < 8; n++) {
            check(raw[n] == MAGIC[n]);
        }
        check(
                b.getInt(8) == WorldGlobalLayout.PROFILE
                        && b.getInt(12) == 1024
                        && b.getLong(16) == expected.length()
                        && b.getInt(24) == 1
                        && b.getInt(28) == 1
                        && b.getInt(32) == 1
                        && b.getInt(36) == 0);
        check(Integer.toUnsignedLong(b.getInt(428)) == WorldGlobalHashes.headerCrc(raw));
        var identity =
                checked(
                        () ->
                                new WorldGlobalStatus.Identity(
                                        HEX.formatHex(raw, 48, 64),
                                        HEX.formatHex(raw, 64, 96),
                                        HEX.formatHex(raw, 160, 192)));
        check(identity.equals(expected.identity()));
        long map = b.getLong(40);
        check(map >= 0 && map <= 0xffff_ffffL);
        int images = number(b.getInt(240), GlobalInteriorLimits.IMAGES),
                rgba = number(b.getInt(340), expected.length());
        int imageBytes = expected.length() - rgba;
        check(imageBytes >= 0 && imageBytes % 4 == 0);
        var counts =
                checked(
                        () ->
                                new WorldGlobalLayout.Counts(
                                        numberUnchecked(
                                                b.getInt(228), GlobalInteriorLimits.MATERIALS),
                                        numberUnchecked(b.getInt(232), GlobalInteriorLimits.GROUPS),
                                        numberUnchecked(
                                                b.getInt(236), GlobalInteriorLimits.BATCHES),
                                        images,
                                        numberUnchecked(
                                                b.getInt(244), GlobalInteriorLimits.VERTICES),
                                        numberUnchecked(
                                                b.getInt(248), GlobalInteriorLimits.INDICES),
                                        numberUnchecked(
                                                b.getInt(256), GlobalInteriorLimits.TRIANGLES),
                                        imageBytes / 4));
        check(
                b.getInt(224) == counts.sources()
                        && b.getInt(252) == counts.rawTriangles()
                        && b.getInt(260) == 511
                        && counts.offsets().actualBytes() == expected.length());
        for (int at = 264; at < 288; at += 8) {
            check(b.getLong(at) == 0);
        }
        var bounds = new float[6];
        for (int axis = 0; axis < 3; axis++) {
            float lo = b.getFloat(288 + axis * 4), hi = b.getFloat(300 + axis * 4);
            check(position(lo) && position(hi) && lo <= hi);
            bounds[axis] = lo;
            bounds[axis + 3] = hi;
        }
        var o = counts.offsets();
        int[] offsets = {
            o.source(),
            o.material(),
            o.group(),
            o.batch(),
            o.image(),
            o.vertices(),
            o.indices(),
            o.rgba()
        };
        for (int n = 0; n < 8; n++) {
            check(b.getInt(312 + n * 4) == offsets[n]);
        }
        long wdt = Integer.toUnsignedLong(b.getInt(344)),
                root = Integer.toUnsignedLong(b.getInt(348)),
                uid = Integer.toUnsignedLong(b.getInt(356));
        check(wdt > 0 && root > 0 && wdt != root && b.getInt(352) == 0);
        var modf =
                checked(
                        () ->
                                WorldGlobalHashes.RawModf.copyOf(
                                        Arrays.copyOfRange(raw, 360, 424), root, uid));
        for (int at = 432; at < 448; at++) {
            check(raw[at] == 0);
        }
        for (int at = 448; at < 1024; at++) {
            check(raw[at] == 0);
        }
        return new Header(
                raw,
                counts,
                identity,
                map,
                HEX.formatHex(raw, 96, 128),
                HEX.formatHex(raw, 128, 160),
                HEX.formatHex(raw, 192, 224),
                modf,
                wdt,
                root,
                uid,
                bounds);
    }

    static Meta metadata(Header h, byte[] raw) throws IOException {
        var c = h.counts();
        check(raw.length == c.offsets().vertices() - 1024);
        var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        var sources = new ArrayList<WorldGlobalHashes.Source>(c.sources());
        for (int n = 0; n < c.sources(); n++) {
            long id = Integer.toUnsignedLong(b.getInt());
            int role = Byte.toUnsignedInt(b.get()),
                    ordinal = Byte.toUnsignedInt(b.get()),
                    flags = Short.toUnsignedInt(b.getShort());
            long length = Integer.toUnsignedLong(b.getInt()),
                    declared = Integer.toUnsignedLong(b.getInt());
            check(flags == 0 || flags == 1);
            if (flags == 1) {
                check(declared == 0);
            }
            String key = hex(b, 16), sha = hex(b, 32);
            var kind =
                    switch (role) {
                        case 1 -> WorldGlobalHashes.Role.WDT;
                        case 2 -> WorldGlobalHashes.Role.ROOT;
                        case 3 -> WorldGlobalHashes.Role.GROUP;
                        case 4 -> WorldGlobalHashes.Role.IMAGE;
                        default -> throw invalid();
                    };
            sources.add(
                    checked(
                            () ->
                                    new WorldGlobalHashes.Source(
                                            id,
                                            kind,
                                            ordinal,
                                            length,
                                            flags == 1 ? null : declared,
                                            key,
                                            sha)));
        }
        check(sources.get(0).id() == h.wdt() && sources.get(1).id() == h.root());
        var materials = new ArrayList<WorldGlobalHashes.Material>(c.materials());
        for (int n = 0; n < c.materials(); n++) {
            long[] words = new long[16];
            for (int word = 0; word < 16; word++) {
                words[word] = Integer.toUnsignedLong(b.getInt());
            }
            materials.add(checked(() -> new WorldGlobalHashes.Material(words)));
        }
        var groups = new ArrayList<WorldGlobalHashes.Group>(c.groups());
        var hashes = new ArrayList<String>(c.groups());
        for (int n = 0; n < c.groups(); n++) {
            int vertex = number(b.getInt(), GlobalInteriorLimits.VERTICES),
                    vertices = number(b.getInt(), GlobalInteriorLimits.GROUP_VERTICES),
                    index = number(b.getInt(), GlobalInteriorLimits.INDICES),
                    indices = number(b.getInt(), GlobalInteriorLimits.GROUP_INDICES),
                    batch = number(b.getInt(), GlobalInteriorLimits.BATCHES),
                    batches = number(b.getInt(), GlobalInteriorLimits.GROUP_BATCHES);
            check(
                    Short.toUnsignedInt(b.getShort()) == n
                            && Short.toUnsignedInt(b.getShort()) == 2 + n);
            long flags = Integer.toUnsignedLong(b.getInt());
            groups.add(
                    checked(
                            () ->
                                    new WorldGlobalHashes.Group(
                                            vertex, vertices, index, indices, batch, batches,
                                            flags)));
            hashes.add(hex(b, 32));
        }
        var batches = new ArrayList<WorldGlobalHashes.Batch>(c.batches());
        for (int n = 0; n < c.batches(); n++) {
            int group = Byte.toUnsignedInt(b.get()), material = Byte.toUnsignedInt(b.get());
            check(b.getShort() == 0);
            int first = number(b.getInt(), GlobalInteriorLimits.INDICES),
                    count = number(b.getInt(), GlobalInteriorLimits.INDICES);
            check(b.getInt() == 0);
            batches.add(checked(() -> new WorldGlobalHashes.Batch(group, material, first, count)));
        }
        validateGroups(c, groups, batches);
        var images = new ArrayList<WorldGlobalHashes.Image>(c.images());
        var locations = new ArrayList<ImageLocation>(c.images());
        int offset = c.offsets().rgba();
        for (int n = 0; n < c.images(); n++) {
            long id = Integer.toUnsignedLong(b.getInt());
            check(Short.toUnsignedInt(b.getShort()) == n);
            int width = Short.toUnsignedInt(b.getShort()),
                    height = Short.toUnsignedInt(b.getShort());
            int encoding = Byte.toUnsignedInt(b.get()),
                    depth = Byte.toUnsignedInt(b.get()),
                    alpha = Byte.toUnsignedInt(b.get());
            check(b.get() == 1 && b.get() == 0);
            int mips = Byte.toUnsignedInt(b.get()),
                    at = number(b.getInt(), WorldGlobalLayout.MAX_ENCODED_BYTES),
                    length = number(b.getInt(), GlobalInteriorLimits.IMAGE_PIXELS * 4);
            check(
                    at == offset
                            && width >= 1
                            && width <= GlobalInteriorLimits.IMAGE_SIDE
                            && height >= 1
                            && height <= GlobalInteriorLimits.IMAGE_SIDE
                            && length == width * height * 4
                            && Short.toUnsignedInt(b.getShort()) == 2 + c.groups() + n);
            int min = Byte.toUnsignedInt(b.get()),
                    max = Byte.toUnsignedInt(b.get()),
                    nonopaque = number(b.getInt(), GlobalInteriorLimits.IMAGE_PIXELS);
            String opaque = hex(b, 32), original = hex(b, 32);
            images.add(
                    checked(
                            () ->
                                    new WorldGlobalHashes.Image(
                                            id, width, height, encoding, depth, alpha, mips, min,
                                            max, nonopaque, original, opaque)));
            locations.add(new ImageLocation(at, length));
            offset += length;
        }
        check(offset == c.offsets().actualBytes() && !b.hasRemaining());
        var d =
                checked(
                        () ->
                                new WorldGlobalSceneWriter.Description(
                                        h.mapId(),
                                        h.identity().scope(),
                                        h.build(),
                                        h.modf(),
                                        sources,
                                        materials,
                                        images));
        check(d.recipe().equals(h.recipe()));
        return new Meta(
                d,
                List.copyOf(groups),
                List.copyOf(batches),
                List.copyOf(hashes),
                List.copyOf(locations));
    }

    static void validateGroups(
            WorldGlobalLayout.Counts c,
            List<WorldGlobalHashes.Group> groups,
            List<WorldGlobalHashes.Batch> batches)
            throws IOException {
        int vertex = 0, index = 0, batch = 0, drawn = 0;
        for (int n = 0; n < groups.size(); n++) {
            var g = groups.get(n);
            check(g.vertexFirst() == vertex && g.indexFirst() == index && g.batchFirst() == batch);
            vertex += g.vertexCount();
            index += g.indexCount();
            batch += g.batchCount();
            check(vertex <= c.vertices() && index <= c.indices() && batch <= c.batches());
            for (int k = g.batchFirst(); k < batch; k++) {
                var value = batches.get(k);
                check(
                        value.group() == n
                                && value.material() < c.materials()
                                && value.firstIndex() >= g.indexFirst()
                                && (long) value.firstIndex() + value.indexCount() <= index);
                for (int p = g.batchFirst(); p < k; p++) {
                    var prior = batches.get(p);
                    check(
                            (long) value.firstIndex() + value.indexCount() <= prior.firstIndex()
                                    || (long) prior.firstIndex() + prior.indexCount()
                                            <= value.firstIndex());
                }
                drawn += value.indexCount() / 3;
            }
        }
        check(
                vertex == c.vertices()
                        && index == c.indices()
                        && batch == c.batches()
                        && drawn == c.drawnTriangles());
    }

    static boolean position(float value) {
        return Float.isFinite(value)
                && Math.abs(value) <= GlobalInteriorLimits.POSITION
                && (value != 0 || Float.floatToRawIntBits(value) == 0);
    }

    static boolean uv(float value) {
        return Float.isFinite(value) && Math.abs(value) <= GlobalInteriorLimits.UV;
    }

    private static String hex(ByteBuffer b, int count) {
        byte[] value = new byte[count];
        b.get(value);
        return HEX.formatHex(value);
    }

    private static int number(int value, int max) throws IOException {
        check(value >= 0 && value <= max);
        return value;
    }

    private static int numberUnchecked(int value, int max) {
        WorldGlobalLayout.require(value >= 0 && value <= max);
        return value;
    }

    private static <T> T checked(Supplier<T> work) throws IOException {
        try {
            return work.get();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid canonical global metadata", invalid);
        }
    }

    static void check(boolean valid) throws IOException {
        if (!valid) {
            throw invalid();
        }
    }

    static IOException invalid() {
        return new IOException("Invalid canonical global scene");
    }
}
